package com.example.dbadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String cryptoKey;
    private final Sql sql = new Sql();
    private final Backup backup = new Backup();
    private final Restore restore = new Restore();
    private final NativeTools nativeTools = new NativeTools();

    public String getCryptoKey() {
        return cryptoKey;
    }

    public void setCryptoKey(String cryptoKey) {
        this.cryptoKey = cryptoKey;
    }

    public Sql getSql() {
        return sql;
    }

    public Backup getBackup() {
        return backup;
    }

    public Restore getRestore() {
        return restore;
    }

    public NativeTools getNativeTools() {
        return nativeTools;
    }

    public static class Sql {
        private int maxRows = 1000;
        private int maxPageOffset = 1_000_000;
        private int timeoutSeconds = 60;

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }

        public int getMaxPageOffset() {
            return maxPageOffset;
        }

        public void setMaxPageOffset(int maxPageOffset) {
            this.maxPageOffset = maxPageOffset;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Backup {
        private String directory = "./backups";
        private int timeoutSeconds = 7200;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Restore {
        private long maxUploadBytes = 20L * 1024 * 1024 * 1024;
        private int uploadTtlHours = 24;

        public long getMaxUploadBytes() {
            return maxUploadBytes;
        }

        public void setMaxUploadBytes(long maxUploadBytes) {
            this.maxUploadBytes = maxUploadBytes;
        }

        public int getUploadTtlHours() {
            return uploadTtlHours;
        }

        public void setUploadTtlHours(int uploadTtlHours) {
            this.uploadTtlHours = uploadTtlHours;
        }
    }

    public static class NativeTools {
        private String mysqldumpPath;
        private String mysqlPath;
        private String oracleExpPath;
        private String oracleImpPath;
        private List<String> extraSearchPaths = new ArrayList<>();
        private int probeTimeoutSeconds = 3;

        public String getMysqldumpPath() { return mysqldumpPath; }
        public void setMysqldumpPath(String mysqldumpPath) { this.mysqldumpPath = mysqldumpPath; }
        public String getMysqlPath() { return mysqlPath; }
        public void setMysqlPath(String mysqlPath) { this.mysqlPath = mysqlPath; }
        public String getOracleExpPath() { return oracleExpPath; }
        public void setOracleExpPath(String oracleExpPath) { this.oracleExpPath = oracleExpPath; }
        public String getOracleImpPath() { return oracleImpPath; }
        public void setOracleImpPath(String oracleImpPath) { this.oracleImpPath = oracleImpPath; }
        public List<String> getExtraSearchPaths() { return extraSearchPaths; }
        public void setExtraSearchPaths(List<String> extraSearchPaths) { this.extraSearchPaths = extraSearchPaths == null ? new ArrayList<>() : new ArrayList<>(extraSearchPaths); }
        public int getProbeTimeoutSeconds() { return probeTimeoutSeconds; }
        public void setProbeTimeoutSeconds(int probeTimeoutSeconds) { this.probeTimeoutSeconds = probeTimeoutSeconds; }
    }
}
