package com.example.dbadmin.storage;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongConsumer;

final class StoragePaths {
    private StoragePaths() {
    }

    static String remotePath(StorageConnection connection, String objectKey, String separator) {
        String combined = relativeRemotePath(connection, objectKey);
        return "/".equals(separator) ? "/" + combined : combined.replace("/", separator);
    }

    /**
     * FTP/SFTP 登录后的工作目录就是账户被授予的存储根目录。基础目录在配置保存时也会
     * 被规范为相对路径，因此不能再人为加上 / 并逃逸到服务器文件系统根目录。
     */
    static String relativeRemotePath(StorageConnection connection, String objectKey) {
        String base = normalize(connection.basePath());
        String key = normalize(objectKey);
        return base.isBlank() ? key : base + "/" + key;
    }

    static String temporary(String finalPath) {
        return finalPath + ".part-" + UUID.randomUUID();
    }

    static List<String> parentDirectories(String path) {
        String normalized = path.replace('\\', '/').replaceFirst("^/", "");
        String[] segments = normalized.split("/");
        List<String> result = new ArrayList<>();
        String current = "";
        for (int index = 0; index < segments.length - 1; index++) {
            if (segments[index].isBlank()) continue;
            current = current.isBlank() ? segments[index] : current + "/" + segments[index];
            result.add(current);
        }
        return result;
    }

    static InputStream progressInput(Path source, LongConsumer progress) throws IOException {
        return progressInput(Files.newInputStream(source), progress);
    }

    static InputStream progressInput(InputStream input, LongConsumer progress) {
        return new FilterInputStream(input) {
            private long total;

            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0) report(1);
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                if (read > 0) report(read);
                return read;
            }

            private void report(long amount) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new IOException("文件传输已取消。");
                total += amount;
                progress.accept(total);
            }
        };
    }

    static void copy(InputStream input, OutputStream output, LongConsumer progress) throws IOException {
        try (InputStream tracked = progressInput(input, progress)) {
            tracked.transferTo(output);
        }
    }

    static OutputStream progressOutput(OutputStream output, LongConsumer progress) {
        return new FilterOutputStream(output) {
            private long total;
            @Override public void write(int value) throws IOException { out.write(value); report(1); }
            @Override public void write(byte[] value, int offset, int length) throws IOException { out.write(value, offset, length); report(length); }
            @Override public void close() throws IOException { flush(); }
            private void report(int amount) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new IOException("文件传输已取消。");
                total += amount;
                progress.accept(total);
            }
        };
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || "/".equals(value.trim())) return "";
        String normalized = value.trim().replace('\\', '/').replaceAll("/+", "/").replaceAll("^/|/$", "");
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("文件服务路径包含不安全的目录片段。");
            }
        }
        return normalized;
    }
}
