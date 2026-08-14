package com.example.dbadmin.storage;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

@Component
public class SmbBackupStorage implements BackupStorage {
    @Override public String type() { return "SMB"; }

    @Override
    public void test(StorageConnection connection) throws Exception {
        byte[] marker = ("mydatadev-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String path = StoragePaths.remotePath(connection, ".mydatadev-test-" + UUID.randomUUID(), "\\");
        withShare(connection, share -> {
            mkdirs(share, path);
            try (com.hierynomus.smbj.share.File file = openWrite(share, path); OutputStream output = file.getOutputStream()) { output.write(marker); }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (com.hierynomus.smbj.share.File file = openRead(share, path); InputStream input = file.getInputStream()) { input.transferTo(output); }
            if (!java.util.Arrays.equals(marker, output.toByteArray())) throw new IllegalStateException("文件服务读写校验失败。");
            share.rm(path);
            return null;
        });
    }

    @Override
    public void upload(StorageConnection connection, Path source, String objectKey, LongConsumer progress) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "\\");
        String temporary = StoragePaths.temporary(target);
        withShare(connection, share -> {
            mkdirs(share, target);
            try (com.hierynomus.smbj.share.File file = openWrite(share, temporary);
                 InputStream input = StoragePaths.progressInput(source, progress); OutputStream output = file.getOutputStream()) {
                input.transferTo(output);
            } catch (Exception error) {
                try { if (share.fileExists(temporary)) share.rm(temporary); } catch (Exception ignored) { }
                throw error;
            }
            try (com.hierynomus.smbj.share.File file = openRename(share, temporary)) { file.rename(target, true); }
            catch (Exception error) {
                try { if (share.fileExists(temporary)) share.rm(temporary); } catch (Exception ignored) { }
                throw error;
            }
            return null;
        });
    }

    @Override
    public void download(StorageConnection connection, String objectKey, OutputStream output, LongConsumer progress) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "\\");
        withShare(connection, share -> {
            try (com.hierynomus.smbj.share.File file = openRead(share, target); OutputStream tracked = StoragePaths.progressOutput(output, progress)) { file.read(tracked); }
            return null;
        });
    }

    @Override
    public long size(StorageConnection connection, String objectKey) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "\\");
        return withShare(connection, share -> {
            if (!share.fileExists(target)) throw new IllegalStateException("远端备份文件不存在。");
            return share.getFileInformation(target, FileStandardInformation.class).getEndOfFile();
        });
    }

    @Override
    public void delete(StorageConnection connection, String objectKey) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "\\");
        withShare(connection, share -> { if (share.fileExists(target)) share.rm(target); return null; });
    }

    private <T> T withShare(StorageConnection profile, ShareWork<T> work) throws Exception {
        SmbConfig config = SmbConfig.builder().withTimeout(60, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build();
        try (SMBClient client = new SMBClient(config);
             Connection connection = client.connect(profile.host(), profile.port());
             Session session = connection.authenticate(new AuthenticationContext(profile.username(), profile.password().toCharArray(), profile.smbDomain() == null ? "" : profile.smbDomain()));
             DiskShare share = (DiskShare) session.connectShare(profile.smbShare())) {
            return work.run(share);
        }
    }

    private void mkdirs(DiskShare share, String filePath) {
        for (String directory : StoragePaths.parentDirectories(filePath)) {
            String smbDirectory = directory.replace('/', '\\');
            if (!share.folderExists(smbDirectory)) share.mkdir(smbDirectory);
        }
    }

    private com.hierynomus.smbj.share.File openWrite(DiskShare share, String path) {
        return share.openFile(path, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ, AccessMask.DELETE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    }

    private com.hierynomus.smbj.share.File openRead(DiskShare share, String path) {
        return share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    }

    private com.hierynomus.smbj.share.File openRename(DiskShare share, String path) {
        return share.openFile(path, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    }

    private interface ShareWork<T> { T run(DiskShare share) throws Exception; }
}
