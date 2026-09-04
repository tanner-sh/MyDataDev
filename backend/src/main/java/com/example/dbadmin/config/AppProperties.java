package com.example.dbadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    /**
     * 主密钥来源。常规 Web/开发模式使用受保护文件，桌面发行版由 Electron 从系统安全存储
     * 解密后通过子进程标准输入交付，避免把明文密钥放进环境变量或命令行。
     */
    private String cryptoKeySource = "FILE";
    private String cryptoKeyFile = "./secrets/mydatadev-master.key";
    private final Sql sql = new Sql();
    private final Backup backup = new Backup();
    private final Restore restore = new Restore();
    private final SqlFile sqlFile = new SqlFile();
    private final NativeTools nativeTools = new NativeTools();
    private final BackgroundTasks backgroundTasks = new BackgroundTasks();
    private final Maintenance maintenance = new Maintenance();
    private final RemotePool remotePool = new RemotePool();
    private final Ssh ssh = new Ssh();
    private final Cors cors = new Cors();
    private final Mcp mcp = new Mcp();
    private final Auth auth = new Auth();
    private final AuditAlert auditAlert = new AuditAlert();

    public String getCryptoKeySource() {
        return cryptoKeySource;
    }

    public void setCryptoKeySource(String cryptoKeySource) {
        this.cryptoKeySource = cryptoKeySource;
    }

    public String getCryptoKeyFile() {
        return cryptoKeyFile;
    }

    public void setCryptoKeyFile(String cryptoKeyFile) {
        this.cryptoKeyFile = cryptoKeyFile;
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

    public Ssh getSsh() {
        return ssh;
    }

    public Cors getCors() {
        return cors;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Auth getAuth() {
        return auth;
    }

    public AuditAlert getAuditAlert() {
        return auditAlert;
    }

    /** Web 发行包的本地账号认证；桌面端和本地开发默认关闭。 */
    public static class Auth {
        private String mode = "DISABLED";
        private String username = "admin";
        private String password;
        private boolean cookieSecure;
        private int maxFailedAttempts = 5;
        private int lockSeconds = 30;
        private final Oidc oidc = new Oidc();

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public boolean isCookieSecure() { return cookieSecure; }
        public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
        public int getMaxFailedAttempts() { return maxFailedAttempts; }
        public void setMaxFailedAttempts(int maxFailedAttempts) { this.maxFailedAttempts = maxFailedAttempts; }
        public int getLockSeconds() { return lockSeconds; }
        public void setLockSeconds(int lockSeconds) { this.lockSeconds = lockSeconds; }
        public Oidc getOidc() { return oidc; }
    }

    /** 通用 OpenID Connect 配置，不绑定具体身份平台。 */
    public static class Oidc {
        private String issuerUri;
        private String clientId;
        private String clientSecret;
        private List<String> scopes = new ArrayList<>(List.of("openid", "profile", "email"));
        private String usernameClaim = "preferred_username";
        private String displayNameClaim = "name";
        private String groupsClaim = "groups";
        private List<String> adminGroups = new ArrayList<>();
        private Map<String, String> groupMappings = new LinkedHashMap<>();

        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes); }
        public String getUsernameClaim() { return usernameClaim; }
        public void setUsernameClaim(String usernameClaim) { this.usernameClaim = usernameClaim; }
        public String getDisplayNameClaim() { return displayNameClaim; }
        public void setDisplayNameClaim(String displayNameClaim) { this.displayNameClaim = displayNameClaim; }
        public String getGroupsClaim() { return groupsClaim; }
        public void setGroupsClaim(String groupsClaim) { this.groupsClaim = groupsClaim; }
        public List<String> getAdminGroups() { return adminGroups; }
        public void setAdminGroups(List<String> adminGroups) { this.adminGroups = adminGroups == null ? new ArrayList<>() : new ArrayList<>(adminGroups); }
        public Map<String, String> getGroupMappings() { return groupMappings; }
        public void setGroupMappings(Map<String, String> groupMappings) { this.groupMappings = groupMappings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(groupMappings); }
    }

    /** 安全事件 Webhook。默认关闭，发送失败不会影响数据库操作。 */
    public static class AuditAlert {
        private boolean enabled;
        private String webhookUrl;
        private String signingSecret;
        private int timeoutSeconds = 5;
        private int cooldownSeconds = 30;
        private int maxEventsPerMinute = 60;
        private int maxCooldownEntries = 2_000;
        private int workerThreads = 2;
        private int queueCapacity = 100;
        private List<String> actions = new ArrayList<>(List.of(
                "AUTH_LOGIN_FAILED", "AUTHORIZATION_DENIED", "CONNECTION_ACCESS_DENIED",
                "USER_ROLE_CHANGE", "USER_DISABLE", "CONNECTION_ACCESS_UPDATE", "AUDIT_ALERT_TEST"
        ));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
        public String getSigningSecret() { return signingSecret; }
        public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
        public int getMaxEventsPerMinute() { return maxEventsPerMinute; }
        public void setMaxEventsPerMinute(int maxEventsPerMinute) { this.maxEventsPerMinute = maxEventsPerMinute; }
        public int getMaxCooldownEntries() { return maxCooldownEntries; }
        public void setMaxCooldownEntries(int maxCooldownEntries) { this.maxCooldownEntries = maxCooldownEntries; }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public List<String> getActions() { return actions; }
        public void setActions(List<String> actions) { this.actions = actions == null ? new ArrayList<>() : new ArrayList<>(actions); }
    }

    /** SSH 隧道的等待上限。跳板机不可达时这些值决定用户要等多久才看到报错。 */
    public static class Ssh {
        private int connectTimeoutSeconds = 10;
        private int authTimeoutSeconds = 10;
        /** 心跳间隔：连接池里的连接可以闲置很久，隧道不能被中间设备当成空闲会话掐掉。 */
        private int heartbeatSeconds = 30;

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getAuthTimeoutSeconds() {
            return authTimeoutSeconds;
        }

        public void setAuthTimeoutSeconds(int authTimeoutSeconds) {
            this.authTimeoutSeconds = authTimeoutSeconds;
        }

        public int getHeartbeatSeconds() {
            return heartbeatSeconds;
        }

        public void setHeartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
        }
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
        /** SSE 推送的扫描间隔：只有存在订阅者时才会真的查库。 */
        private int streamIntervalMs = 1_000;
        /** 单条 SSE 连接的寿命上限，到点后浏览器的 EventSource 会自动重连。 */
        private int streamTimeoutMinutes = 30;

        public int getStreamIntervalMs() { return streamIntervalMs; }
        public void setStreamIntervalMs(int streamIntervalMs) { this.streamIntervalMs = streamIntervalMs; }
        public int getStreamTimeoutMinutes() { return streamTimeoutMinutes; }
        public void setStreamTimeoutMinutes(int streamTimeoutMinutes) { this.streamTimeoutMinutes = streamTimeoutMinutes; }
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

    public static class Cors {
        private List<String> allowedOriginPatterns = new ArrayList<>(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173"
        ));

        public List<String> getAllowedOriginPatterns() { return allowedOriginPatterns; }
        public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
            this.allowedOriginPatterns = allowedOriginPatterns == null
                    ? new ArrayList<>()
                    : new ArrayList<>(allowedOriginPatterns);
        }
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
        private String pgDumpPath;
        private String pgRestorePath;
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
        public String getPgDumpPath() { return pgDumpPath; }
        public void setPgDumpPath(String pgDumpPath) { this.pgDumpPath = pgDumpPath; }
        public String getPgRestorePath() { return pgRestorePath; }
        public void setPgRestorePath(String pgRestorePath) { this.pgRestorePath = pgRestorePath; }
        public List<String> getExtraSearchPaths() { return extraSearchPaths; }
        public void setExtraSearchPaths(List<String> extraSearchPaths) { this.extraSearchPaths = extraSearchPaths == null ? new ArrayList<>() : new ArrayList<>(extraSearchPaths); }
        public int getProbeTimeoutSeconds() { return probeTimeoutSeconds; }
        public void setProbeTimeoutSeconds(int probeTimeoutSeconds) { this.probeTimeoutSeconds = probeTimeoutSeconds; }
    }
}
