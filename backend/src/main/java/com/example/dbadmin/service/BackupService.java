package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.BackupTaskRequest;
import com.example.dbadmin.model.BackupHistory;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class BackupService {
    private final BackupTaskRepository repository;
    private final BackupHistoryRepository historyRepository;
    private final ConnectionService connections;
    private final AuditRepository audit;
    private final AppProperties properties;

    public BackupService(BackupTaskRepository repository, BackupHistoryRepository historyRepository, ConnectionService connections, AuditRepository audit, AppProperties properties) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.connections = connections;
        this.audit = audit;
        this.properties = properties;
    }

    public List<BackupTask> list() {
        return repository.findAll();
    }

    public List<BackupTask> list(Long connectionId) {
        return connectionId == null ? repository.findAll() : repository.findByConnectionId(connectionId);
    }

    public BackupTask create(BackupTaskRequest request, String actor) {
        DbConnection connection = connections.require(request.connectionId());
        BackupTask task = taskFromRequest(0, request, connection, null, null, null, null, null);
        long id = repository.insert(task);
        audit.log(actor, "BACKUP_TASK_CREATE", request.name(), request.scope());
        return repository.findById(id).orElseThrow();
    }

    public BackupTask update(long id, BackupTaskRequest request, String actor) {
        repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        DbConnection connection = connections.require(request.connectionId());
        BackupTask task = taskFromRequest(id, request, connection, null, null, null, null, null);
        repository.update(id, task);
        audit.log(actor, "BACKUP_TASK_UPDATE", request.name(), request.scope());
        return repository.findById(id).orElseThrow();
    }

    public BackupTask setEnabled(long id, boolean enabled, String actor) {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        if (enabled) {
            validateCron(task.cron(), true);
        }
        repository.updateEnabled(id, enabled);
        audit.log(actor, enabled ? "BACKUP_TASK_ENABLE" : "BACKUP_TASK_DISABLE", task.name(), task.cron());
        return repository.findById(id).orElseThrow();
    }

    public void delete(long id, boolean deleteFile, String actor) throws Exception {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        List<BackupHistory> histories = historyRepository.findByTaskId(id);
        if (deleteFile) {
            for (BackupHistory history : histories) {
                deleteHistoryFile(history);
            }
        }
        historyRepository.deleteByTaskId(id);
        repository.delete(id);
        audit.log(actor, "BACKUP_TASK_DELETE", task.name(), deleteFile ? "deleteFile=true" : "deleteFile=false");
    }

    public BackupTask run(long id, String actor) throws Exception {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        DbConnection connection = connections.require(task.connectionId());
        Instant startedAt = Instant.now();
        try {
            BackupFile backup = runBackup(task, connection);
            String message = methodLabel(task.backupMethod()) + " 备份已生成：" + backup.path().getFileName();
            String filePath = backup.path().toAbsolutePath().normalize().toString();
            historyRepository.insert(new BackupHistory(0, id, connection.id(), "SUCCESS", message, filePath, backup.size(), startedAt, Instant.now()));
            repository.updateStatus(id, "SUCCESS", message, filePath, backup.size());
            audit.log(actor, "BACKUP_TASK_RUN", task.name(), message);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            historyRepository.insert(new BackupHistory(0, id, connection.id(), "FAILED", message, null, null, startedAt, Instant.now()));
            repository.updateStatus(id, "FAILED", message);
            audit.log(actor, "BACKUP_TASK_RUN_FAILED", task.name(), message);
            throw e;
        }
        return repository.findById(id).orElseThrow();
    }

    public Path backupFile(long id) {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        if (task.lastFilePath() == null || task.lastFilePath().isBlank()) {
            throw new IllegalStateException("该备份任务还没有生成可下载文件。");
        }
        Path path = checkedBackupPath(task.lastFilePath());
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalStateException("备份文件不存在，请重新执行备份任务。");
        }
        return path;
    }

    public List<BackupHistory> history(long taskId) {
        repository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + taskId));
        return historyRepository.findByTaskId(taskId);
    }

    public Path historyFile(long taskId, long historyId) {
        BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
        if (history.filePath() == null || history.filePath().isBlank()) {
            throw new IllegalStateException("该备份历史没有生成可下载文件。");
        }
        Path path = checkedBackupPath(history.filePath());
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalStateException("备份文件不存在，请重新执行备份任务。");
        }
        return path;
    }

    public void deleteHistory(long taskId, long historyId, boolean deleteFile, String actor) throws Exception {
        BackupTask task = repository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + taskId));
        BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
        if (deleteFile) {
            deleteHistoryFile(history);
        }
        historyRepository.delete(historyId);
        refreshTaskSummary(taskId);
        audit.log(actor, "BACKUP_HISTORY_DELETE", task.name(), deleteFile ? "deleteFile=true" : "deleteFile=false");
    }

    private void deleteHistoryFile(BackupHistory history) throws Exception {
        if (history.filePath() == null || history.filePath().isBlank()) {
            return;
        }
        Files.deleteIfExists(checkedBackupPath(history.filePath()));
    }

    private void refreshTaskSummary(long taskId) {
        List<BackupHistory> histories = historyRepository.findByTaskId(taskId);
        if (histories.isEmpty()) {
            repository.updateSummary(taskId, null, null, null, null, null);
            return;
        }
        BackupHistory latest = histories.get(0);
        repository.updateSummary(taskId, latest.status(), latest.message(), latest.filePath(), latest.fileSize(), latest.finishedAt());
    }

    private BackupTask taskFromRequest(long id, BackupTaskRequest request, DbConnection connection, String lastStatus, String lastMessage, String lastFilePath, Long lastFileSize, Instant lastRunAt) {
        String scope = validateScope(request.scope(), request.tableName());
        String cron = blankToNull(request.cron());
        validateCron(cron, request.enabled());
        String backupMethod = validateBackupMethod(request.backupMethod(), connection);
        String toolPath = blankToNull(request.toolPath());
        String extraArgs = validateExtraArgs(request.extraArgs());
        String nativeConnectName = blankToNull(request.nativeConnectName());
        if (!"SQL".equals(backupMethod) && toolPath == null) {
            throw new IllegalArgumentException("原生备份需要填写工具路径。");
        }
        String schemaName = "TABLE".equals(scope) ? blankToNull(request.schemaName()) : null;
        String tableName = "TABLE".equals(scope) ? blankToNull(request.tableName()) : null;
        return new BackupTask(id, request.name().trim(), request.connectionId(), scope, schemaName, tableName, backupMethod, toolPath, extraArgs, nativeConnectName, cron, request.enabled(), lastStatus, lastMessage, lastFilePath, lastFileSize, lastRunAt);
    }

    private void validateCron(String cron, boolean enabled) {
        if (cron == null) {
            if (enabled) {
                throw new IllegalArgumentException("启用定时备份需要填写 cron 表达式。");
            }
            return;
        }
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cron 表达式不合法：" + cron);
        }
    }

    private Path checkedBackupPath(String filePath) {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        Path backupRoot = Path.of(properties.getBackup().getDirectory()).toAbsolutePath().normalize();
        if (!path.startsWith(backupRoot)) {
            throw new IllegalStateException("备份文件路径不在允许目录内。");
        }
        return path;
    }

    private BackupFile runBackup(BackupTask task, DbConnection connection) throws Exception {
        return switch (normalizeBackupMethod(task.backupMethod())) {
            case "MYSQLDUMP" -> runMysqlDump(task, connection);
            case "ORACLE_EXP" -> runOracleExp(task, connection);
            default -> writeSqlBackup(task, connection);
        };
    }

    private BackupFile runMysqlDump(BackupTask task, DbConnection connection) throws Exception {
        MysqlJdbcTarget target = mysqlTarget(connection.jdbcUrl());
        String database = "TABLE".equals(normalizeScope(task.scope())) && task.schemaName() != null && !task.schemaName().isBlank()
                ? task.schemaName()
                : target.database();
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("mysqldump 备份需要 JDBC URL 中包含数据库名，或在单表任务中填写 schema。");
        }
        Path file = backupPath(task, "mysqldump", ".sql");
        List<String> command = new ArrayList<>();
        command.add(task.toolPath());
        command.add("--host=" + target.host());
        command.add("--port=" + target.port());
        if (connection.username() != null && !connection.username().isBlank()) {
            command.add("--user=" + connection.username());
        }
        command.add("--result-file=" + file.toAbsolutePath().normalize());
        command.addAll(extraArgs(task.extraArgs()));
        command.add(database);
        if ("TABLE".equals(normalizeScope(task.scope()))) {
            command.add(task.tableName());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        String password = connections.password(connection.id());
        if (password != null) {
            builder.environment().put("MYSQL_PWD", password);
        }
        runNativeProcess(builder, file, "mysqldump", password);
        return new BackupFile(file, Files.size(file));
    }

    private BackupFile runOracleExp(BackupTask task, DbConnection connection) throws Exception {
        Path file = backupPath(task, "oracle-exp", ".dmp");
        Path log = file.resolveSibling(file.getFileName() + ".log");
        String password = connections.password(connection.id());
        String connectName = task.nativeConnectName() == null || task.nativeConnectName().isBlank()
                ? oracleConnectName(connection.jdbcUrl())
                : task.nativeConnectName();
        String userid = connection.username() + "/" + (password == null ? "" : password) + (connectName == null || connectName.isBlank() ? "" : "@" + connectName);
        List<String> command = new ArrayList<>();
        command.add(task.toolPath());
        command.add("userid=" + userid);
        command.add("file=" + file.toAbsolutePath().normalize());
        command.add("log=" + log.toAbsolutePath().normalize());
        if ("TABLE".equals(normalizeScope(task.scope()))) {
            String table = task.schemaName() == null || task.schemaName().isBlank()
                    ? task.tableName()
                    : task.schemaName() + "." + task.tableName();
            command.add("tables=(" + table + ")");
        } else if (task.schemaName() != null && !task.schemaName().isBlank()) {
            command.add("owner=" + task.schemaName());
        } else {
            command.add("full=y");
        }
        command.addAll(extraArgs(task.extraArgs()));
        runNativeProcess(new ProcessBuilder(command), file, "Oracle exp", password);
        return new BackupFile(file, Files.size(file));
    }

    private void runNativeProcess(ProcessBuilder builder, Path outputFile, String label, String secret) throws Exception {
        Files.createDirectories(outputFile.getParent());
        Path processLog = outputFile.resolveSibling(outputFile.getFileName() + ".out");
        builder.redirectErrorStream(true);
        builder.redirectOutput(processLog.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.MINUTES);
        String output = Files.exists(processLog) ? Files.readString(processLog, StandardCharsets.UTF_8) : "";
        if (output.length() > 4000) {
            output = output.substring(0, 4000);
        }
        if (!finished) {
            process.destroyForcibly();
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(processLog);
            throw new IllegalStateException(label + " 执行超时。");
        }
        if (process.exitValue() != 0) {
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(processLog);
            throw new IllegalStateException(label + " 执行失败，退出码 " + process.exitValue() + (output.isBlank() ? "" : "：" + scrub(output, secret)));
        }
        Files.deleteIfExists(processLog);
        if (!Files.exists(outputFile) || !Files.isRegularFile(outputFile)) {
            throw new IllegalStateException(label + " 未生成备份文件。");
        }
    }

    private Path backupPath(BackupTask task, String prefix, String extension) throws Exception {
        Path dir = Path.of(properties.getBackup().getDirectory());
        Files.createDirectories(dir);
        return dir.resolve(prefix + "-task-" + task.id() + "-" + Instant.now().toEpochMilli() + extension);
    }

    private String validateBackupMethod(String method, DbConnection connection) {
        String normalized = normalizeBackupMethod(method);
        if (!Set.of("SQL", "MYSQLDUMP", "ORACLE_EXP").contains(normalized)) {
            throw new IllegalArgumentException("不支持的备份方式：" + method);
        }
        String dbType = connection.dbType() == null ? "" : connection.dbType().toLowerCase(Locale.ROOT);
        if ("MYSQLDUMP".equals(normalized) && !Set.of("mysql", "mariadb").contains(dbType)) {
            throw new IllegalArgumentException("mysqldump 备份仅支持 MySQL/MariaDB 连接。");
        }
        if ("ORACLE_EXP".equals(normalized) && !"oracle".equals(dbType)) {
            throw new IllegalArgumentException("Oracle exp 备份仅支持 Oracle 连接。");
        }
        return normalized;
    }

    private String normalizeBackupMethod(String method) {
        return method == null || method.isBlank() ? "SQL" : method.toUpperCase(Locale.ROOT);
    }

    private String methodLabel(String method) {
        return switch (normalizeBackupMethod(method)) {
            case "MYSQLDUMP" -> "mysqldump";
            case "ORACLE_EXP" -> "Oracle exp";
            default -> "SQL";
        };
    }

    private String validateExtraArgs(String extraArgs) {
        List<String> args = extraArgs(extraArgs);
        return args.isEmpty() ? null : String.join("\n", args);
    }

    private List<String> extraArgs(String extraArgs) {
        if (extraArgs == null || extraArgs.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (String raw : extraArgs.split("\\R")) {
            String arg = raw.trim();
            if (arg.isEmpty()) {
                continue;
            }
            validateExtraArg(arg);
            args.add(arg);
        }
        return args;
    }

    private void validateExtraArg(String arg) {
        if (arg.matches(".*[|&;<>`$].*")) {
            throw new IllegalArgumentException("备份额外参数包含不允许的 shell 控制字符：" + arg);
        }
        String lower = arg.toLowerCase(Locale.ROOT);
        List<String> blockedPrefixes = List.of(
                "--result-file", "-r", "--host", "-h", "--port", "-p", "--user", "-u", "--password",
                "--databases", "--all-databases", "--tables", "file=", "log=", "userid=", "owner=", "tables=", "full="
        );
        for (String blocked : blockedPrefixes) {
            if (lower.equals(blocked) || lower.startsWith(blocked + "=")) {
                throw new IllegalArgumentException("备份额外参数不能覆盖系统控制参数：" + arg);
            }
        }
    }

    private MysqlJdbcTarget mysqlTarget(String jdbcUrl) {
        try {
            String raw = jdbcUrl.replaceFirst("^jdbc:", "");
            URI uri = URI.create(raw);
            String database = uri.getPath() == null ? null : uri.getPath().replaceFirst("^/", "");
            if (database != null && database.contains("/")) {
                database = database.substring(0, database.indexOf('/'));
            }
            return new MysqlJdbcTarget(uri.getHost() == null ? "localhost" : uri.getHost(), uri.getPort() == -1 ? 3306 : uri.getPort(), database == null || database.isBlank() ? null : database);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 MySQL JDBC URL：" + jdbcUrl);
        }
    }

    private String oracleConnectName(String jdbcUrl) {
        String prefix = "jdbc:oracle:thin:@";
        if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
            return null;
        }
        return jdbcUrl.substring(prefix.length());
    }

    private String scrub(String message, String secret) {
        if (secret == null || secret.isBlank()) {
            return message;
        }
        return message.replace(secret, "******");
    }

    private BackupFile writeSqlBackup(BackupTask task, DbConnection dbConnection) throws Exception {
        Path dir = Path.of(properties.getBackup().getDirectory());
        Files.createDirectories(dir);
        Path file = dir.resolve("backup-task-" + task.id() + "-" + Instant.now().toEpochMilli() + ".sql");
        try (Connection connection = connections.open(task.connectionId());
             BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            List<TableRef> tables = backupTables(task, connection);
            if (tables.isEmpty()) {
                throw new IllegalArgumentException("没有找到可备份的表。");
            }
            writer.write("-- MyDataDev SQL Backup\n");
            writer.write("-- Task: " + task.name() + "\n");
            writer.write("-- Connection: " + dbConnection.name() + "\n");
            writer.write("-- Generated At: " + Instant.now() + "\n\n");
            for (TableRef table : tables) {
                writeTableBackup(connection, writer, table);
            }
        } catch (Exception e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return new BackupFile(file, Files.size(file));
    }

    private List<TableRef> backupTables(BackupTask task, Connection connection) throws Exception {
        String scope = normalizeScope(task.scope());
        if ("TABLE".equals(scope)) {
            if (task.tableName() == null || task.tableName().isBlank()) {
                throw new IllegalArgumentException("单表备份需要指定表名。");
            }
            return List.of(new TableRef(task.schemaName(), task.tableName()));
        }
        if (!"DATABASE".equals(scope)) {
            throw new IllegalArgumentException("不支持的备份范围：" + task.scope());
        }
        DatabaseMetaData meta = connection.getMetaData();
        List<TableRef> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String name = rs.getString("TABLE_NAME");
                if (name != null && !isSystemSchema(schema)) {
                    tables.add(new TableRef(schema, name));
                }
            }
        }
        return tables;
    }

    private void writeTableBackup(Connection connection, BufferedWriter writer, TableRef table) throws Exception {
        String quoteString = identifierQuote(connection);
        String tableName = table(table.schema(), table.name(), quoteString);
        writer.write("-- Table: " + tableName + "\n");
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM " + tableName)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                columns.add(md.getColumnLabel(i));
            }
            String columnSql = columns.stream().map(column -> quote(column, quoteString)).reduce((a, b) -> a + ", " + b).orElse("");
            long rows = 0;
            while (rs.next()) {
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    values.add(literal(rs.getObject(i), tableName, columns.get(i - 1)));
                }
                writer.write("INSERT INTO " + tableName + " (" + columnSql + ") VALUES (" + String.join(", ", values) + ");\n");
                rows++;
            }
            writer.write("-- Rows: " + rows + "\n\n");
        }
    }

    private String validateScope(String scope, String tableName) {
        String normalized = normalizeScope(scope);
        if (!Set.of("DATABASE", "TABLE").contains(normalized)) {
            throw new IllegalArgumentException("不支持的备份范围：" + scope);
        }
        if ("TABLE".equals(normalized) && (tableName == null || tableName.isBlank())) {
            throw new IllegalArgumentException("单表备份需要指定表名。");
        }
        return normalized;
    }

    private String normalizeScope(String scope) {
        return scope == null ? "" : scope.toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isSystemSchema(String schema) {
        if (schema == null) {
            return false;
        }
        String s = schema.toUpperCase(Locale.ROOT);
        return s.equals("INFORMATION_SCHEMA")
                || s.equals("SYS")
                || s.equals("SYSTEM")
                || s.equals("PG_CATALOG")
                || s.equals("SQLJ")
                || s.startsWith("SYS_")
                || s.startsWith("MYSQL");
    }

    private String table(String schema, String table, String quoteString) {
        return schema == null || schema.isBlank()
                ? quote(table, quoteString)
                : quote(schema, quoteString) + "." + quote(table, quoteString);
    }

    private String quote(String identifier, String quoteString) {
        if (quoteString == null || quoteString.isBlank()) {
            return identifier;
        }
        return quoteString + identifier.replace(quoteString, quoteString + quoteString) + quoteString;
    }

    private String identifierQuote(Connection connection) throws Exception {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        return quote == null || quote.isBlank() ? "" : quote.trim();
    }

    private String literal(Object value, String tableName, String columnName) throws Exception {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Clob clob) {
            return quoteLiteral(readClob(clob));
        }
        if (value instanceof Blob blob) {
            throw unsupportedBinary(tableName, columnName, "BLOB " + blob.length() + " bytes");
        }
        if (value instanceof byte[] bytes) {
            throw unsupportedBinary(tableName, columnName, "BINARY " + bytes.length + " bytes");
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";
        }
        if (value instanceof java.sql.Date date) {
            return quoteLiteral(date.toLocalDate().toString());
        }
        if (value instanceof java.sql.Time time) {
            return quoteLiteral(time.toLocalTime().toString());
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return quoteLiteral(timestamp.toLocalDateTime().toString().replace('T', ' '));
        }
        if (value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime || value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof Instant) {
            return quoteLiteral(value.toString());
        }
        return quoteLiteral(value.toString());
    }

    private String readClob(Clob clob) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (Reader reader = clob.getCharacterStream()) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    private IllegalArgumentException unsupportedBinary(String tableName, String columnName, String detail) {
        return new IllegalArgumentException("SQL 备份暂不支持二进制字段：" + tableName + "." + quoteForMessage(columnName) + " (" + detail + ")");
    }

    private String quoteForMessage(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private record TableRef(String schema, String name) {
    }

    private record BackupFile(Path path, long size) {
    }

    private record MysqlJdbcTarget(String host, int port, String database) {
    }
}
