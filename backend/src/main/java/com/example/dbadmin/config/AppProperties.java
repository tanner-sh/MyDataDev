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
    private final SqlFile sqlFile = new SqlFile();
    private final NativeTools nativeTools = new NativeTools();
    private final BackgroundTasks backgroundTasks = new BackgroundTasks();
    private final Maintenance maintenance = new Maintenance();
    private final RemotePool remotePool = new RemotePool();
    private final Mcp mcp = new Mcp();

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

    public SqlFile getSqlFile() {
        return sqlFile;
    }

    public NativeTools getNativeTools() {
        return nativeTools;
    }

    public BackgroundTasks getBackgroundTasks() {
        return backgroundTasks;
    }

    public Maintenance getMaintenance() {
        return maintenance;
    }

    public RemotePool getRemotePool() {
        return remotePool;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public static class Sql {
        private int maxRows = 1000;
        private int maxPageOffset = 1_000_000;
        private int maxStatements = 500;
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

        public int getMaxStatements() {
            return maxStatements;
        }

        public void setMaxStatements(int maxStatements) {
            this.maxStatements = maxStatements;
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
        private int sqlInsertBatchSize = 100;
        private int failedUploadRetentionDays = 7;

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

        public int getSqlInsertBatchSize() {
            return sqlInsertBatchSize;
        }

        public void setSqlInsertBatchSize(int sqlInsertBatchSize) {
            this.sqlInsertBatchSize = sqlInsertBatchSize;
        }

        public int getFailedUploadRetentionDays() {
            return failedUploadRetentionDays;
        }

        public void setFailedUploadRetentionDays(int failedUploadRetentionDays) {
            this.failedUploadRetentionDays = failedUploadRetentionDays;
        }
    }

    public static class Restore {
        private long maxUploadBytes = 20L * 1024 * 1024 * 1024;
        private int uploadTtlHours = 24;
        private int remoteCacheTtlHours = 24;

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

        public int getRemoteCacheTtlHours() { return remoteCacheTtlHours; }
        public void setRemoteCacheTtlHours(int remoteCacheTtlHours) { this.remoteCacheTtlHours = remoteCacheTtlHours; }
    }

    public static class SqlFile {
        private String directory = "./sql-files";
        private long maxUploadBytes = 20L * 1024 * 1024 * 1024;
        private int readyTtlHours = 24;
        private int statementTimeoutSeconds = 7200;
        private int maxStatementChars = 128 * 1024 * 1024;
        private int commitBatchSize = 100;

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
        public long getMaxUploadBytes() { return maxUploadBytes; }
        public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
        public int getReadyTtlHours() { return readyTtlHours; }
        public void setReadyTtlHours(int readyTtlHours) { this.readyTtlHours = readyTtlHours; }
        public int getStatementTimeoutSeconds() { return statementTimeoutSeconds; }
        public void setStatementTimeoutSeconds(int statementTimeoutSeconds) { this.statementTimeoutSeconds = statementTimeoutSeconds; }
        public int getMaxStatementChars() { return maxStatementChars; }
        public void setMaxStatementChars(int maxStatementChars) { this.maxStatementChars = maxStatementChars; }
        public int getCommitBatchSize() { return commitBatchSize; }
        public void setCommitBatchSize(int commitBatchSize) { this.commitBatchSize = commitBatchSize; }
    }

    public static class BackgroundTasks {
        private int cancelPollIntervalMs = 1_000;
        private int progressIntervalMs = 500;
        private int maxConcurrentUploads = 2;
        private int maxPerConnection = 1;
        private int backupWorkerThreads = 2;
        private int sqlFileWorkerThreads = 2;
        private int queueCapacity = 20;

        public int getCancelPollIntervalMs() { return cancelPollIntervalMs; }
        public void setCancelPollIntervalMs(int cancelPollIntervalMs) { this.cancelPollIntervalMs = cancelPollIntervalMs; }
        public int getProgressIntervalMs() { return progressIntervalMs; }
        public void setProgressIntervalMs(int progressIntervalMs) { this.progressIntervalMs = progressIntervalMs; }
        public int getMaxConcurrentUploads() { return maxConcurrentUploads; }
        public void setMaxConcurrentUploads(int maxConcurrentUploads) { this.maxConcurrentUploads = maxConcurrentUploads; }
        public int getMaxPerConnection() { return maxPerConnection; }
        public void setMaxPerConnection(int maxPerConnection) { this.maxPerConnection = maxPerConnection; }
        public int getBackupWorkerThreads() { return backupWorkerThreads; }
        public void setBackupWorkerThreads(int backupWorkerThreads) { this.backupWorkerThreads = backupWorkerThreads; }
        public int getSqlFileWorkerThreads() { return sqlFileWorkerThreads; }
        public void setSqlFileWorkerThreads(int sqlFileWorkerThreads) { this.sqlFileWorkerThreads = sqlFileWorkerThreads; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    public static class Maintenance {
        private int sqlHistoryRetentionDays = 90;
        private int auditRetentionDays = 180;
        private int jobRetentionDays = 90;
        private int cleanupBatchSize = 500;

        public int getSqlHistoryRetentionDays() { return sqlHistoryRetentionDays; }
        public void setSqlHistoryRetentionDays(int sqlHistoryRetentionDays) { this.sqlHistoryRetentionDays = sqlHistoryRetentionDays; }
        public int getAuditRetentionDays() { return auditRetentionDays; }
        public void setAuditRetentionDays(int auditRetentionDays) { this.auditRetentionDays = auditRetentionDays; }
        public int getJobRetentionDays() { return jobRetentionDays; }
        public void setJobRetentionDays(int jobRetentionDays) { this.jobRetentionDays = jobRetentionDays; }
        public int getCleanupBatchSize() { return cleanupBatchSize; }
        public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }
    }

    public static class RemotePool {
        private int maxPools = 20;
        private int maximumPoolSize = 3;
        private int connectionTimeoutMs = 10_000;
        private long idleTimeoutMs = 300_000;
        private long maxLifetimeMs = 1_800_000;

        public int getMaxPools() { return maxPools; }
        public void setMaxPools(int maxPools) { this.maxPools = maxPools; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(int connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }
        public long getIdleTimeoutMs() { return idleTimeoutMs; }
        public void setIdleTimeoutMs(long idleTimeoutMs) { this.idleTimeoutMs = idleTimeoutMs; }
        public long getMaxLifetimeMs() { return maxLifetimeMs; }
        public void setMaxLifetimeMs(long maxLifetimeMs) { this.maxLifetimeMs = maxLifetimeMs; }
    }

    public static class Mcp {
        private boolean enabled = true;
        private List<String> allowedOrigins = new ArrayList<>();
        private int defaultQueryRows = 100;
        private int maxQueryRows = 500;
        private int maxResultCells = 20_000;
        private long maxResultTextChars = 1_000_000;
        private int maxCellTextChars = 20_000;
        private int maxSqlChars = 200_000;
        private int queryTimeoutSeconds = 30;
        private int metadataPageSize = 50;
        private int maxMetadataPageSize = 200;
        private int tablePageSize = 50;
        private int maxTablePageSize = 100;
        private int sessionTtlMinutes = 120;
        private List<McpAgent> agents = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = copy(allowedOrigins); }
        public int getDefaultQueryRows() { return defaultQueryRows; }
        public void setDefaultQueryRows(int defaultQueryRows) { this.defaultQueryRows = defaultQueryRows; }
        public int getMaxQueryRows() { return maxQueryRows; }
        public void setMaxQueryRows(int maxQueryRows) { this.maxQueryRows = maxQueryRows; }
        public int getMaxResultCells() { return maxResultCells; }
        public void setMaxResultCells(int maxResultCells) { this.maxResultCells = maxResultCells; }
        public long getMaxResultTextChars() { return maxResultTextChars; }
        public void setMaxResultTextChars(long maxResultTextChars) { this.maxResultTextChars = maxResultTextChars; }
        public int getMaxCellTextChars() { return maxCellTextChars; }
        public void setMaxCellTextChars(int maxCellTextChars) { this.maxCellTextChars = maxCellTextChars; }
        public int getMaxSqlChars() { return maxSqlChars; }
        public void setMaxSqlChars(int maxSqlChars) { this.maxSqlChars = maxSqlChars; }
        public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
        public int getMetadataPageSize() { return metadataPageSize; }
        public void setMetadataPageSize(int metadataPageSize) { this.metadataPageSize = metadataPageSize; }
        public int getMaxMetadataPageSize() { return maxMetadataPageSize; }
        public void setMaxMetadataPageSize(int maxMetadataPageSize) { this.maxMetadataPageSize = maxMetadataPageSize; }
        public int getTablePageSize() { return tablePageSize; }
        public void setTablePageSize(int tablePageSize) { this.tablePageSize = tablePageSize; }
        public int getMaxTablePageSize() { return maxTablePageSize; }
        public void setMaxTablePageSize(int maxTablePageSize) { this.maxTablePageSize = maxTablePageSize; }
        public int getSessionTtlMinutes() { return sessionTtlMinutes; }
        public void setSessionTtlMinutes(int sessionTtlMinutes) { this.sessionTtlMinutes = sessionTtlMinutes; }
        public List<McpAgent> getAgents() { return agents; }
        public void setAgents(List<McpAgent> agents) { this.agents = agents == null ? new ArrayList<>() : new ArrayList<>(agents); }

        private static List<String> copy(List<String> values) {
            return values == null ? new ArrayList<>() : new ArrayList<>(values);
        }
    }

    public static class McpAgent {
        private String id;
        private String keyHash;
        private List<Long> connectionIds = new ArrayList<>();
        private boolean allowProduction;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getKeyHash() { return keyHash; }
        public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
        public List<Long> getConnectionIds() { return connectionIds; }
        public void setConnectionIds(List<Long> connectionIds) { this.connectionIds = connectionIds == null ? new ArrayList<>() : new ArrayList<>(connectionIds); }
        public boolean isAllowProduction() { return allowProduction; }
        public void setAllowProduction(boolean allowProduction) { this.allowProduction = allowProduction; }
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
