package com.example.dbadmin.storage;

import com.emc.ecs.nfsclient.nfs.io.Nfs3File;
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream;
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream;
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3;
import com.emc.ecs.nfsclient.rpc.CredentialUnix;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.LongConsumer;

@Component
public class NfsBackupStorage implements BackupStorage {
    @Override public String type() { return "NFS"; }

    @Override
    public void test(StorageConnection connection) throws Exception {
        byte[] marker = ("mydatadev-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String path = StoragePaths.remotePath(connection, ".mydatadev-test-" + UUID.randomUUID(), "/");
        Nfs3 nfs = client(connection);
        Nfs3File file = nfs.newFile(path);
        file.getParentFile().mkdirs();
        try (OutputStream output = new NfsFileOutputStream(file)) { output.write(marker); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new NfsFileInputStream(file)) { input.transferTo(output); }
        if (!java.util.Arrays.equals(marker, output.toByteArray())) throw new IllegalStateException("文件服务读写校验失败。");
        file.delete();
    }

    @Override
    public void upload(StorageConnection connection, Path source, String objectKey, LongConsumer progress) throws Exception {
        Nfs3 nfs = client(connection);
        String targetPath = StoragePaths.remotePath(connection, objectKey, "/");
        Nfs3File target = nfs.newFile(targetPath);
        Nfs3File temporary = nfs.newFile(StoragePaths.temporary(targetPath));
        target.getParentFile().mkdirs();
        try (InputStream input = StoragePaths.progressInput(source, progress); OutputStream output = new NfsFileOutputStream(temporary)) {
            input.transferTo(output);
        } catch (Exception error) {
            try { if (temporary.exists()) temporary.delete(); } catch (Exception ignored) { }
            throw error;
        }
        try {
            if (target.exists()) target.delete();
            if (!temporary.renameTo(target)) throw new IllegalStateException("提交 NFS 备份文件失败。");
        } catch (Exception error) {
            try { if (temporary.exists()) temporary.delete(); } catch (Exception ignored) { }
            throw error;
        }
    }

    @Override
    public void download(StorageConnection connection, String objectKey, OutputStream output, LongConsumer progress) throws Exception {
        Nfs3File file = client(connection).newFile(StoragePaths.remotePath(connection, objectKey, "/"));
        if (!file.exists() || !file.isFile()) throw new IllegalStateException("远端备份文件不存在。");
        try (InputStream input = new NfsFileInputStream(file)) { StoragePaths.copy(input, output, progress); }
    }

    @Override
    public long size(StorageConnection connection, String objectKey) throws Exception {
        Nfs3File file = client(connection).newFile(StoragePaths.remotePath(connection, objectKey, "/"));
        if (!file.exists() || !file.isFile()) throw new IllegalStateException("远端备份文件不存在。");
        return file.lengthEx();
    }

    @Override
    public void delete(StorageConnection connection, String objectKey) throws Exception {
        Nfs3File file = client(connection).newFile(StoragePaths.remotePath(connection, objectKey, "/"));
        if (file.exists()) file.delete();
    }

    private Nfs3 client(StorageConnection connection) throws Exception {
        return new Nfs3(connection.host(), connection.nfsExportPath(),
                new CredentialUnix(connection.nfsUid(), connection.nfsGid(), new HashSet<>(connection.nfsGroups())), 2);
    }
}
