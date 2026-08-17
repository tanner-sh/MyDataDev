package com.example.dbadmin.storage;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;
import java.util.function.LongConsumer;

@Component
public class SftpBackupStorage implements BackupStorage {
    @Override
    public String type() {
        return "SFTP";
    }

    @Override
    public void test(StorageConnection connection) throws Exception {
        byte[] marker = ("mydatadev-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String path = StoragePaths.remotePath(connection, ".mydatadev-test-" + UUID.randomUUID(), "/");
        withClient(connection, sftp -> {
            mkdirs(sftp, path);
            try (OutputStream output = sftp.write(path)) { output.write(marker); }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = sftp.read(path)) { input.transferTo(output); }
            if (!java.util.Arrays.equals(marker, output.toByteArray())) throw new IllegalStateException("文件服务读写校验失败。");
            sftp.remove(path);
            return null;
        });
    }

    @Override
    public void upload(StorageConnection connection, Path source, String objectKey, LongConsumer progress) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "/");
        String temporary = StoragePaths.temporary(target);
        withClient(connection, sftp -> {
            mkdirs(sftp, target);
            try (InputStream input = StoragePaths.progressInput(source, progress);
                 OutputStream output = sftp.write(temporary, EnumSet.of(SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate))) {
                input.transferTo(output);
            } catch (Exception error) {
                try { sftp.remove(temporary); } catch (Exception ignored) { }
                throw error;
            }
            try {
                moveIntoPlace(sftp, temporary, target);
            } catch (Exception error) {
                try { sftp.remove(temporary); } catch (Exception ignored) { }
                throw error;
            }
            return null;
        });
    }

    static void moveIntoPlace(SftpClient sftp, String temporary, String target) throws IOException {
        if (sftp.getVersion() >= SftpConstants.SFTP_V5) {
            sftp.rename(temporary, target, SftpClient.CopyMode.Overwrite);
            return;
        }
        removeIfExists(sftp, target);
        sftp.rename(temporary, target);
    }

    private static void removeIfExists(SftpClient sftp, String path) throws IOException {
        try {
            sftp.stat(path);
            sftp.remove(path);
        } catch (SftpException error) {
            if (error.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE
                    && error.getStatus() != SftpConstants.SSH_FX_NO_SUCH_PATH) throw error;
        }
    }

    @Override
    public void download(StorageConnection connection, String objectKey, OutputStream output, LongConsumer progress) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "/");
        withClient(connection, sftp -> {
            try (InputStream input = sftp.read(target)) {
                StoragePaths.copy(input, output, progress);
            }
            return null;
        });
    }

    @Override
    public long size(StorageConnection connection, String objectKey) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "/");
        return withClient(connection, sftp -> sftp.stat(target).getSize());
    }

    @Override
    public void delete(StorageConnection connection, String objectKey) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "/");
        withClient(connection, sftp -> {
            try {
                sftp.remove(target);
            } catch (SftpException error) {
                if (error.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE
                        && error.getStatus() != SftpConstants.SSH_FX_NO_SUCH_PATH) throw error;
            }
            return null;
        });
    }

    private <T> T withClient(StorageConnection connection, SftpWork<T> work) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, remoteAddress, serverKey) -> connection.skipServerVerification()
                || KeyUtils.checkFingerPrint(connection.serverFingerprint(), serverKey).getKey());
        client.start();
        try (ClientSession session = client.connect(connection.username(), connection.host(), connection.port())
                .verify(Duration.ofSeconds(10)).getSession()) {
            if ("PRIVATE_KEY".equalsIgnoreCase(connection.sftpAuthMode())) {
                FilePasswordProvider passwordProvider = FilePasswordProvider.of(connection.privateKeyPassphrase() == null ? "" : connection.privateKeyPassphrase());
                Iterable<java.security.KeyPair> keys = SecurityUtils.loadKeyPairIdentities(
                        session, NamedResource.ofName("storage-profile-" + connection.id()),
                        new ByteArrayInputStream(connection.privateKey().getBytes(StandardCharsets.UTF_8)), passwordProvider);
                for (java.security.KeyPair key : keys) session.addPublicKeyIdentity(key);
            } else {
                session.addPasswordIdentity(connection.password());
            }
            session.auth().verify(Duration.ofSeconds(10));
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                return work.run(sftp);
            }
        } finally {
            client.stop();
        }
    }

    private void mkdirs(SftpClient sftp, String filePath) throws Exception {
        for (String directory : StoragePaths.parentDirectories(filePath)) {
            String absolute = "/" + directory;
            try {
                if (!sftp.stat(absolute).isDirectory()) throw new IllegalStateException("远端路径不是目录：" + absolute);
            } catch (java.io.IOException missing) {
                sftp.mkdir(absolute);
            }
        }
    }

    private interface SftpWork<T> {
        T run(SftpClient sftp) throws Exception;
    }
}
