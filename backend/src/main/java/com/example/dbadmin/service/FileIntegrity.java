package com.example.dbadmin.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

final class FileIntegrity {
    private FileIntegrity() {
    }

    static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static Path checkedPath(Path root, String rawPath) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!path.startsWith(normalizedRoot)) {
            throw new IllegalStateException("文件路径不在允许目录内。");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (!path.toRealPath().startsWith(normalizedRoot.toRealPath())) {
                    throw new IllegalStateException("文件真实路径不在允许目录内。");
                }
            } catch (java.io.IOException error) {
                throw new IllegalStateException("无法验证文件真实路径。", error);
            }
        }
        return path;
    }
}
