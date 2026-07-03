package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.BackupTaskRequest;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

@Service
public class BackupService {
    private final BackupTaskRepository repository;
    private final ConnectionService connections;
    private final AuditRepository audit;
    private final AppProperties properties;

    public BackupService(BackupTaskRepository repository, ConnectionService connections, AuditRepository audit, AppProperties properties) {
        this.repository = repository;
        this.connections = connections;
        this.audit = audit;
        this.properties = properties;
    }

    public List<BackupTask> list() {
        return repository.findAll();
    }

    public BackupTask create(BackupTaskRequest request, String actor) {
        connections.require(request.connectionId());
        validateScope(request.scope(), request.tableName());
        long id = repository.insert(new BackupTask(0, request.name(), request.connectionId(), request.scope(), request.schemaName(), request.tableName(), request.cron(), request.enabled(), null, null, null, null, null));
        audit.log(actor, "BACKUP_TASK_CREATE", request.name(), request.scope());
        return repository.findById(id).orElseThrow();
    }

    public BackupTask run(long id, String actor) throws Exception {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        DbConnection connection = connections.require(task.connectionId());
        try {
            BackupFile backup = writeSqlBackup(task, connection);
            String message = "SQL 备份已生成：" + backup.path().getFileName();
            repository.updateStatus(id, "SUCCESS", message, backup.path().toAbsolutePath().normalize().toString(), backup.size());
            audit.log(actor, "BACKUP_TASK_RUN", task.name(), message);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
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
        Path path = Path.of(task.lastFilePath()).toAbsolutePath().normalize();
        Path backupRoot = Path.of(properties.getBackup().getDirectory()).toAbsolutePath().normalize();
        if (!path.startsWith(backupRoot)) {
            throw new IllegalStateException("备份文件路径不在允许目录内。");
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalStateException("备份文件不存在，请重新执行备份任务。");
        }
        return path;
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

    private void validateScope(String scope, String tableName) {
        String normalized = normalizeScope(scope);
        if (!Set.of("DATABASE", "TABLE").contains(normalized)) {
            throw new IllegalArgumentException("不支持的备份范围：" + scope);
        }
        if ("TABLE".equals(normalized) && (tableName == null || tableName.isBlank())) {
            throw new IllegalArgumentException("单表备份需要指定表名。");
        }
    }

    private String normalizeScope(String scope) {
        return scope == null ? "" : scope.toUpperCase(Locale.ROOT);
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
}
