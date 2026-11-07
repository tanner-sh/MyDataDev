package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.BackupService.BackupFile;
import com.example.dbadmin.service.BackupTargets.ResolvedTarget;
import com.example.dbadmin.service.BackupTargets.TableRef;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.example.dbadmin.service.BackupTargets.metadataExactPattern;
import static com.example.dbadmin.service.BackupTargets.tableKey;

/**
 * SQL 逻辑备份：建表语句、逐行 INSERT、索引与外键，写成一份能在另一个会话里恢复的脚本。
 *
 * <p>从 BackupService 拆出来。字面量写法（时间、二进制、布尔）与标识符引用都交给方言，
 * 这里只管顺序：先全部建表、再写数据、最后补约束 —— 外键放在最后，恢复时才不用管表的先后。</p>
 */
final class SqlBackupWriter {
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final AppProperties properties;
    private final BackupTargets targets;

    SqlBackupWriter(ConnectionService connections, DialectRegistry dialectRegistry, AppProperties properties, BackupTargets targets) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.properties = properties;
        this.targets = targets;
    }

    BackupFile write(BackupTask task, DbConnection dbConnection) throws Exception {
        Path dir = Path.of(properties.getBackup().getDirectory());
        Files.createDirectories(dir);
        Path file = dir.resolve("backup-task-" + task.id() + "-" + Instant.now().toEpochMilli() + ".sql");
        try (Connection connection = connections.open(task.connectionId());
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true)) {
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            ResolvedTarget resolved = targets.resolveTarget(connection, dialect, task.scope(), task.schemaName(), task.tableNames());
            if (resolved.tables().isEmpty()) {
                throw new IllegalArgumentException("没有找到可备份的表。");
            }
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write("-- MyDataDev SQL Backup\n");
                writer.write("-- Task: " + sqlCommentValue(task.name()) + "\n");
                writer.write("-- Connection: " + sqlCommentValue(dbConnection.name()) + "\n");
                writer.write("-- Format-Version: 2\n");
                writer.write("-- Source-Db-Type: " + sqlCommentValue(dbConnection.dbType()) + "\n");
                writer.write("-- Generated At: " + Instant.now() + "\n\n");
                for (TableRef table : resolved.tables()) {
                    ensureBackupNotInterrupted();
                    writeCreateTable(connection, writer, table, dialect, dbConnection.dbType());
                }
                for (TableRef table : resolved.tables()) {
                    ensureBackupNotInterrupted();
                    writeTableBackup(connection, writer, table, dialect, dbConnection.dbType());
                }
                Set<String> selectedTables = resolved.tables().stream().map(BackupTargets::tableKey).collect(java.util.stream.Collectors.toSet());
                for (TableRef table : resolved.tables()) {
                    ensureBackupNotInterrupted();
                    writeTableConstraints(connection, writer, table, dialect, selectedTables);
                }
            }
        } catch (Exception e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return new BackupFile(file, Files.size(file));
    }

    private void writeCreateTable(Connection connection, BufferedWriter writer, TableRef table, DatabaseDialect dialect, String dbType) throws Exception {
        // 标识符引用走方言，不用 JDBC 元数据的 getIdentifierQuoteString()。
        // SQL Server 的元数据返回的是双引号，而它的方言用的是方括号 —— 备份文件里写成
        // "dbo"."t" 之后，恢复时 SqlRestoreTranslator 的 SQL Server 解析器把它当字符串而不是
        // 标识符，第一条语句就报 multi-statement be found，整份备份恢复不回去。
        // CLAUDE.md 那条「标识符引用收敛到方言接口」说的就是这里。
        String qualified = dialect.qualifiedName(table.schema(), table.name());
        DatabaseMetaData meta = connection.getMetaData();
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, table.schema());
        String schema = scope.schemaPattern();
        String schemaPattern = schema == null ? null : metadataExactPattern(meta, schema);
        List<String> definitions = new ArrayList<>();
        try (ResultSet columns = meta.getColumns(scope.catalog(), schemaPattern, table.name(), "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                String type = columns.getString("TYPE_NAME");
                int size = columns.getInt("COLUMN_SIZE");
                int scale = columns.getInt("DECIMAL_DIGITS");
                boolean nullable = columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                String defaultValue = columns.getString("COLUMN_DEF");
                boolean autoIncrement = "YES".equalsIgnoreCase(safeMetadataString(columns, "IS_AUTOINCREMENT"));
                StringBuilder definition = new StringBuilder(dialect.quoteIdentifier(name)).append(' ').append(columnType(type, size, scale));
                if (defaultValue != null && !defaultValue.isBlank() && !autoIncrement) definition.append(" DEFAULT ").append(defaultValue.trim());
                if (!nullable) definition.append(" NOT NULL");
                if (autoIncrement) definition.append(identityClause(dbType));
                definitions.add(definition.toString());
            }
        }
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet keys = meta.getPrimaryKeys(scope.catalog(), schema, table.name())) {
            java.util.TreeMap<Short, String> ordered = new java.util.TreeMap<>();
            while (keys.next()) ordered.put(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME"));
            primaryKeys.addAll(ordered.values());
        }
        if (!primaryKeys.isEmpty()) definitions.add("PRIMARY KEY (" + primaryKeys.stream().map(name -> dialect.quoteIdentifier(name)).collect(java.util.stream.Collectors.joining(", ")) + ")");
        writer.write("-- Table structure: " + sqlCommentValue(qualified) + "\n");
        writer.write("CREATE TABLE " + qualified + " (\n  " + String.join(",\n  ", definitions) + "\n)" + dialect.scriptStatementSeparator() + "\n\n");
    }

    private void writeTableConstraints(Connection connection, BufferedWriter writer, TableRef table, DatabaseDialect dialect, Set<String> selectedTables) throws Exception {
        // 标识符引用走方言，不用 JDBC 元数据的 getIdentifierQuoteString()。
        // SQL Server 的元数据返回的是双引号，而它的方言用的是方括号 —— 备份文件里写成
        // "dbo"."t" 之后，恢复时 SqlRestoreTranslator 的 SQL Server 解析器把它当字符串而不是
        // 标识符，第一条语句就报 multi-statement be found，整份备份恢复不回去。
        // CLAUDE.md 那条「标识符引用收敛到方言接口」说的就是这里。
        String qualified = dialect.qualifiedName(table.schema(), table.name());
        DatabaseMetaData meta = connection.getMetaData();
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, table.schema());
        String schema = scope.schemaPattern();
        Set<String> primaryIndexNames = new HashSet<>();
        try (ResultSet keys = meta.getPrimaryKeys(scope.catalog(), schema, table.name())) {
            while (keys.next()) {
                String name = keys.getString("PK_NAME");
                if (name != null) primaryIndexNames.add(name);
            }
        }
        Map<String, IndexBackup> indexes = new LinkedHashMap<>();
        // approximate=true：这里只取索引名与列，精确统计用不上，而 approximate=false 会让
        // Oracle 驱动先跑一遍 DBMS_STATS.GATHER_TABLE_STATS —— 备份是定时跑在生产库上的，
        // 顺手收一次全表统计不是它该做的事，缺 ANALYZE 权限时还会让整次备份失败。
        try (ResultSet rows = meta.getIndexInfo(scope.catalog(), schema, table.name(), false, true)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null || column == null || primaryIndexNames.contains(name)) continue;
                IndexBackup index = indexes.get(name);
                if (index == null) {
                    index = new IndexBackup(!rows.getBoolean("NON_UNIQUE"), new java.util.TreeMap<>());
                    indexes.put(name, index);
                }
                index.columns().put(rows.getShort("ORDINAL_POSITION"), column);
            }
        }
        for (Map.Entry<String, IndexBackup> entry : indexes.entrySet()) {
            var backupName = dialect.backupIndexName(connection, table.schema(), table.name(), entry.getKey());
            if (backupName.isEmpty()) continue;
            writer.write("CREATE " + (entry.getValue().unique() ? "UNIQUE " : "") + "INDEX " + dialect.quoteIdentifier(backupName.get()) + " ON " + qualified + " ("
                    + entry.getValue().columns().values().stream().map(name -> dialect.quoteIdentifier(name)).collect(java.util.stream.Collectors.joining(", ")) + ")" + dialect.scriptStatementSeparator() + "\n");
        }
        Map<String, ForeignKeyBackup> foreignKeys = new LinkedHashMap<>();
        try (ResultSet rows = meta.getImportedKeys(scope.catalog(), schema, table.name())) {
            while (rows.next()) {
                String name = rows.getString("FK_NAME");
                String pkSchema = rows.getString("PKTABLE_SCHEM");
                String pkCatalog = rows.getString("PKTABLE_CAT");
                String referencedNamespace = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG ? pkCatalog : pkSchema;
                String referencedTable = rows.getString("PKTABLE_NAME");
                if (!selectedTables.contains(tableKey(new TableRef(referencedNamespace, referencedTable)))) continue;
                ForeignKeyBackup key = foreignKeys.computeIfAbsent(name == null ? "fk_" + table.name() + "_" + foreignKeys.size() : name,
                        ignored -> new ForeignKeyBackup(referencedNamespace, referencedTable, new java.util.TreeMap<>(), new java.util.TreeMap<>()));
                short sequence = rows.getShort("KEY_SEQ");
                key.localColumns().put(sequence, rows.getString("FKCOLUMN_NAME"));
                key.referencedColumns().put(sequence, rows.getString("PKCOLUMN_NAME"));
            }
        }
        for (Map.Entry<String, ForeignKeyBackup> entry : foreignKeys.entrySet()) {
            ForeignKeyBackup key = entry.getValue();
            writer.write("ALTER TABLE " + qualified + " ADD CONSTRAINT " + dialect.quoteIdentifier(dialect.backupConstraintName(table.schema(), table.name(), entry.getKey())) + " FOREIGN KEY ("
                    + key.localColumns().values().stream().map(name -> dialect.quoteIdentifier(name)).collect(java.util.stream.Collectors.joining(", ")) + ") REFERENCES "
                    + dialect.qualifiedName(key.referencedNamespace(), key.referencedTable()) + " ("
                    + key.referencedColumns().values().stream().map(name -> dialect.quoteIdentifier(name)).collect(java.util.stream.Collectors.joining(", ")) + ")" + dialect.scriptStatementSeparator() + "\n");
        }
        if (!indexes.isEmpty() || !foreignKeys.isEmpty()) writer.write("\n");
    }

    private void writeTableBackup(Connection connection, BufferedWriter writer, TableRef table, DatabaseDialect dialect, String dbType) throws Exception {
        String tableName = dialect.qualifiedName(table.schema(), table.name());
        writer.write("-- Table: " + sqlCommentValue(tableName) + "\n");
        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            dialect.configureStreamingStatement(connection, statement, 500, properties.getBackup().getTimeoutSeconds());
            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + tableName)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                columns.add(md.getColumnLabel(i));
            }
            String columnSql = columns.stream().map(column -> dialect.quoteIdentifier(column)).reduce((a, b) -> a + ", " + b).orElse("");
            int batchSize = dialect.supportsMultiRowValues()
                    ? Math.min(Math.max(1, properties.getBackup().getSqlInsertBatchSize()), 1_000)
                    : 1;
            long rows = 0;
            int batchRows = 0;
            while (rs.next()) {
                ensureBackupNotInterrupted();
                if (batchRows == 0) writer.write("INSERT INTO " + tableName + " (" + columnSql + ") VALUES\n");
                else writer.write(",\n");
                writer.write("  (");
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (i > 1) writer.write(", ");
                    writeLiteral(writer, rs.getObject(i), tableName, columns.get(i - 1), dbType, dialect);
                }
                writer.write(")");
                rows++;
                batchRows++;
                if (batchRows >= batchSize) {
                    writer.write(dialect.scriptStatementSeparator() + "\n");
                    batchRows = 0;
                }
            }
            if (batchRows > 0) writer.write(dialect.scriptStatementSeparator() + "\n");
            writer.write("-- Rows: " + rows + "\n\n");
            }
        }
    }

    private void writeLiteral(BufferedWriter writer, Object value, String tableName, String columnName, String dbType, DatabaseDialect dialect) throws Exception {
        if (value == null) {
            writer.write("NULL");
            return;
        }
        if (value instanceof Clob clob) {
            writer.write('\'');
            try (Reader reader = clob.getCharacterStream()) {
                char[] buffer = new char[8_192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    for (int index = 0; index < read; index++) {
                        char ch = buffer[index];
                        if (ch == '\'') writer.write("''");
                        else writer.write(ch);
                    }
                }
            }
            writer.write('\'');
            return;
        }
        if (value instanceof Blob blob) {
            try (InputStream input = blob.getBinaryStream()) {
                writeBinary(writer, input, dbType);
            }
            return;
        }
        if (value instanceof byte[] bytes) {
            try (InputStream input = new ByteArrayInputStream(bytes)) {
                writeBinary(writer, input, dbType);
            }
            return;
        }
        if (value instanceof Number) {
            writer.write(value.toString());
            return;
        }
        if (value instanceof Boolean bool) {
            writer.write(booleanLiteral(bool, dbType));
            return;
        }
        // 时间值交给方言写：带引号的 ISO 串不是所有库都认。Oracle 按会话的 NLS_DATE_FORMAT
        // 解析（默认 DD-MON-RR），'2026-09-09 12:34:56' 直接 ORA-01843 —— 也就是说备份文件
        // 里的时间列在 Oracle 上恢复不回去。方言负责套上 TO_TIMESTAMP 之类的显式转换。
        if (value instanceof java.sql.Date date) {
            writer.write(dialect.scriptTemporalLiteral(date.toString()));
            return;
        }
        if (value instanceof java.sql.Time time) {
            writer.write(dialect.scriptTemporalLiteral(time.toString()));
            return;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            writer.write(dialect.scriptTemporalLiteral(timestamp.toLocalDateTime().toString().replace('T', ' ')));
            return;
        }
        if (value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime || value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof Instant) {
            writer.write(dialect.scriptTemporalLiteral(value.toString()));
            return;
        }
        if (value instanceof CharSequence text) {
            // 备份会在另一个会话恢复；使用方言的脚本字面量，不能假设 MySQL 的 sql_mode 相同。
            writer.write(dialect.scriptLiteral(text));
            return;
        }
        writer.write(dialect.scriptLiteral(value.toString()));
    }

    private void writeBinary(BufferedWriter writer, InputStream input, String dbType) throws Exception {
        String normalized = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        if (normalized.equals("postgresql")) writer.write("decode('");
        else if (normalized.equals("sqlserver")) writer.write("0x");
        else if (Set.of("oracle", "dm", "oceanbase-oracle").contains(normalized)) writer.write("hextoraw('");
        else writer.write("X'");
        byte[] bytes = new byte[8_192];
        char[] hex = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        int read;
        while ((read = input.read(bytes)) >= 0) {
            ensureBackupNotInterrupted();
            for (int index = 0; index < read; index++) {
                int value = bytes[index] & 0xff;
                hex[index * 2] = digits[value >>> 4];
                hex[index * 2 + 1] = digits[value & 0x0f];
            }
            writer.write(hex, 0, read * 2);
        }
        if (normalized.equals("postgresql")) writer.write("', 'hex')");
        else if (Set.of("oracle", "dm", "oceanbase-oracle").contains(normalized)) writer.write("')");
        else if (!normalized.equals("sqlserver")) writer.write("'");
    }

    private void ensureBackupNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("备份已取消。");
    }

    private String safeMetadataString(ResultSet rows, String column) {
        try { return rows.getString(column); } catch (Exception ignored) { return null; }
    }

    String columnType(String raw, int size, int scale) {
        String type = raw == null || raw.isBlank() ? "VARCHAR" : raw;
        String upper = type.toUpperCase(Locale.ROOT);
        if (type.contains("(")) return type;
        if ((upper.contains("CHAR") || upper.contains("BINARY")) && size > 0 && size < 1_000_000) return type + "(" + size + ")";
        if ((upper.contains("DECIMAL") || upper.contains("NUMERIC") || upper.equals("NUMBER")) && size > 0) return type + "(" + size + "," + Math.max(0, scale) + ")";
        return type;
    }

    private String identityClause(String dbType) {
        String normalized = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        if (Set.of("mysql", "mariadb", "oceanbase-mysql").contains(normalized)) return " AUTO_INCREMENT";
        if (normalized.equals("sqlserver")) return " IDENTITY(1,1)";
        if (!normalized.equals("sqlite")) return " GENERATED BY DEFAULT AS IDENTITY";
        return "";
    }

    private String booleanLiteral(boolean value, String dbType) {
        String normalized = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        return normalized.equals("postgresql") ? (value ? "TRUE" : "FALSE") : (value ? "1" : "0");
    }

    private String sqlCommentValue(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
    }

    private record IndexBackup(boolean unique, java.util.TreeMap<Short, String> columns) {
    }

    private record ForeignKeyBackup(String referencedNamespace, String referencedTable,
                                    java.util.TreeMap<Short, String> localColumns,
                                    java.util.TreeMap<Short, String> referencedColumns) {
    }
}
