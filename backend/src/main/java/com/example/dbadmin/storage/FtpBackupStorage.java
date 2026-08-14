package com.example.dbadmin.storage;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.stereotype.Component;

import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.LongConsumer;

@Component
public class FtpBackupStorage implements BackupStorage {
    @Override
    public String type() {
        return "FTP";
    }

    @Override
    public void test(StorageConnection connection) throws Exception {
        byte[] marker = ("mydatadev-" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String path = StoragePaths.remotePath(connection, ".mydatadev-test-" + UUID.randomUUID(), "/");
        withClient(connection, client -> {
            mkdirs(client, path);
            if (!client.storeFile(path, new ByteArrayInputStream(marker))) throw failure(client, "写入测试文件失败");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!client.retrieveFile(path, output)) throw failure(client, "读取测试文件失败");
            if (!java.util.Arrays.equals(marker, output.toByteArray())) throw new IllegalStateException("文件服务读写校验失败。");
            if (!client.deleteFile(path)) throw failure(client, "删除测试文件失败");
            return null;
        });
    }

    @Override
    public void upload(StorageConnection connection, Path source, String objectKey, LongConsumer progress) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "/");
        String temporary = StoragePaths.temporary(target);
        withClient(connection, client -> {
            mkdirs(client, target);
            try (InputStream input = StoragePaths.progressInput(source, progress)) {
                if (!client.storeFile(temporary, input)) throw failure(client, "上传备份文件失败");
            } catch (Exception error) {
                client.deleteFile(temporary);
                throw error;
            }
            FTPFile[] existing = client.listFiles(target);
            if (existing.length > 0 && !client.deleteFile(target)) {
                client.deleteFile(temporary);
                throw failure(client, "替换已有备份文件失败");
            }
            if (!client.rename(temporary, target)) {
                client.deleteFile(temporary);
                throw failure(client, "提交备份文件失败");
            }
            return null;
        });
    }

    @Override
    public void download(StorageConnection connection, String objectKey, OutputStream output, LongConsumer progress) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "/");
        withClient(connection, client -> {
            try (OutputStream tracked = new ProgressOutputStream(output, progress)) {
                if (!client.retrieveFile(target, tracked)) throw failure(client, "下载备份文件失败");
                tracked.flush();
            }
            return null;
        });
    }

    @Override
    public long size(StorageConnection connection, String objectKey) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "/");
        return withClient(connection, client -> {
            FTPFile file = client.mlistFile(target);
            if (file == null) {
                FTPFile[] files = client.listFiles(target);
                file = files.length == 0 ? null : files[0];
            }
            if (file == null || !file.isFile()) throw new IllegalStateException("远端备份文件不存在。");
            return file.getSize();
        });
    }

    @Override
    public void delete(StorageConnection connection, String objectKey) throws Exception {
        String target = StoragePaths.remotePath(connection, objectKey, "/");
        withClient(connection, client -> {
            FTPFile[] files = client.listFiles(target);
            if (files.length > 0 && !client.deleteFile(target)) throw failure(client, "删除远端备份文件失败");
            return null;
        });
    }

    private <T> T withClient(StorageConnection connection, ClientWork<T> work) throws Exception {
        FTPClient client = createClient(connection);
        client.setConnectTimeout(10_000);
        client.setDefaultTimeout(10_000);
        client.setDataTimeout(Duration.ofSeconds(60));
        client.setControlKeepAliveTimeout(Duration.ofSeconds(30));
        try {
            client.connect(connection.host(), connection.port());
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw failure(client, "FTP 服务拒绝连接");
            if (!client.login(connection.username(), connection.password())) throw failure(client, "FTP 登录失败");
            if (client instanceof FTPSClient ftps) {
                ftps.execPBSZ(0);
                ftps.execPROT("P");
            }
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            client.setAutodetectUTF8(true);
            return work.run(client);
        } finally {
            if (client.isConnected()) {
                try { client.logout(); } catch (Exception ignored) { }
                try { client.disconnect(); } catch (Exception ignored) { }
            }
        }
    }

    private FTPClient createClient(StorageConnection connection) {
        if (!"EXPLICIT".equalsIgnoreCase(connection.ftpTlsMode())) return new FTPClient();
        FTPSClient client = new FTPSClient("TLS", false);
        if (connection.skipServerVerification()) {
            client.setTrustManager(trustAll());
            client.setEndpointCheckingEnabled(false);
        } else if (connection.serverFingerprint() != null && !connection.serverFingerprint().isBlank()) {
            client.setTrustManager(pinned(connection.serverFingerprint()));
            client.setEndpointCheckingEnabled(true);
        } else {
            client.setEndpointCheckingEnabled(true);
        }
        return client;
    }

    private void mkdirs(FTPClient client, String filePath) throws Exception {
        for (String directory : StoragePaths.parentDirectories(filePath)) {
            String absolute = "/" + directory;
            if (!client.changeWorkingDirectory(absolute) && !client.makeDirectory(absolute)) {
                throw failure(client, "创建远端目录失败：" + absolute);
            }
        }
    }

    private IllegalStateException failure(FTPClient client, String action) {
        return new IllegalStateException(action + "：" + client.getReplyString().trim());
    }

    private X509TrustManager trustAll() {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        };
    }

    private X509TrustManager pinned(String expected) {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                try {
                    if (chain == null || chain.length == 0 || !fingerprintMatches(expected, chain[0].getEncoded())) {
                        throw new java.security.cert.CertificateException("FTPS 证书指纹不匹配。");
                    }
                } catch (java.security.cert.CertificateException error) {
                    throw error;
                } catch (Exception error) {
                    throw new java.security.cert.CertificateException(error);
                }
            }
        };
    }

    private boolean fingerprintMatches(String expected, byte[] encoded) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
        String normalized = expected.trim().replace("SHA256:", "").replace(":", "");
        return normalized.equalsIgnoreCase(HexFormat.of().formatHex(digest))
                || normalized.equals(Base64.getEncoder().withoutPadding().encodeToString(digest));
    }

    private interface ClientWork<T> {
        T run(FTPClient client) throws Exception;
    }

    private static final class ProgressOutputStream extends java.io.FilterOutputStream {
        private final LongConsumer progress;
        private long total;

        private ProgressOutputStream(OutputStream output, LongConsumer progress) {
            super(output);
            this.progress = progress;
        }

        @Override public void write(int value) throws java.io.IOException { super.write(value); report(1); }
        @Override public void write(byte[] value, int offset, int length) throws java.io.IOException { out.write(value, offset, length); report(length); }
        @Override public void close() throws java.io.IOException { flush(); }
        private void report(int amount) throws java.io.IOException {
            if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("文件传输已取消。");
            total += amount;
            progress.accept(total);
        }
    }
}
