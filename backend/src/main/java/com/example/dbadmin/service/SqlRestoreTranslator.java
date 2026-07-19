package com.example.dbadmin.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLDataType;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.ast.statement.SQLCreateIndexStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SqlRestoreTranslator {
    public Analysis analyze(Path path, String sourceDbType, String targetDbType, Map<String, String> namespaceMapping) {
        DbType source = dbType(sourceDbType);
        DbType target = dbType(targetDbType);
        Set<String> namespaces = new LinkedHashSet<>();
        Set<String> tables = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        long[] count = {0};
        try {
            SqlStatementStream.read(path, sql -> {
                count[0]++;
                try {
                    SQLStatement statement = parseAllowed(sql, source);
                    SQLExprTableSource table = tableSource(statement);
                    if (table != null) {
                        String namespace = clean(table.getSchema());
                        String name = clean(table.getTableName());
                        if (namespace != null) namespaces.add(namespace);
                        if (name != null) tables.add(namespace == null ? name : namespace + "." + name);
                        mapTable(table, namespaceMapping, target);
                    }
                    mapTypes(statement, target);
                    SQLUtils.toSQLString(statement, target);
                } catch (Exception error) {
                    if (errors.size() < 50) errors.add("第 " + count[0] + " 条 SQL：" + safeMessage(error));
                }
            });
        } catch (Exception error) {
            errors.add(safeMessage(error));
        }
        if (count[0] == 0 && errors.isEmpty()) errors.add("SQL 文件中没有可执行语句。");
        return new Analysis(count[0], List.copyOf(namespaces), List.copyOf(tables), List.copyOf(errors));
    }

    public void translate(Path path, String sourceDbType, String targetDbType, Map<String, String> namespaceMapping,
                          String conflictMode, TranslatedStatementConsumer consumer) throws Exception {
        DbType source = dbType(sourceDbType);
        DbType target = dbType(targetDbType);
        long[] index = {0};
        SqlStatementStream.read(path, sql -> {
            index[0]++;
            SQLStatement statement = parseAllowed(sql, source);
            boolean ddl = !(statement instanceof SQLInsertStatement);
            if ("APPEND".equalsIgnoreCase(conflictMode) && ddl) return;
            if (target == DbType.clickhouse && ddl) {
                throw new IllegalArgumentException("ClickHouse 目标只支持向已存在表追加数据。");
            }
            SQLExprTableSource table = tableSource(statement);
            if (table != null) mapTable(table, namespaceMapping, target);
            mapTypes(statement, target);
            String translated = SQLUtils.toSQLString(statement, target);
            consumer.accept(index[0], translated, statement instanceof SQLInsertStatement);
        });
    }

    private SQLStatement parseAllowed(String sql, DbType source) {
        SQLStatement statement = SQLUtils.parseSingleStatement(sql, source);
        if (statement instanceof SQLCreateTableStatement
                || statement instanceof SQLCreateIndexStatement
                || statement instanceof SQLAlterTableStatement
                || statement instanceof SQLInsertStatement) {
            return statement;
        }
        throw new IllegalArgumentException("不允许恢复语句 " + statement.getClass().getSimpleName() + "，仅支持建表、索引、约束和 INSERT。");
    }

    private SQLExprTableSource tableSource(SQLStatement statement) {
        if (statement instanceof SQLCreateTableStatement create) return create.getTableSource();
        if (statement instanceof SQLAlterTableStatement alter) return alter.getTableSource();
        if (statement instanceof SQLInsertStatement insert) return insert.getTableSource();
        if (statement instanceof SQLCreateIndexStatement index && index.getTable() instanceof SQLExprTableSource table) return table;
        return null;
    }

    private void mapTable(SQLExprTableSource table, Map<String, String> mappings, DbType target) {
        String sourceNamespace = clean(table.getSchema());
        String mapped = sourceNamespace == null ? null : mappings == null ? sourceNamespace : mappings.getOrDefault(sourceNamespace, sourceNamespace);
        if (target == DbType.sqlite) mapped = null;
        table.setSchema(mapped);
    }

    private void mapTypes(SQLStatement statement, DbType target) {
        if (!(statement instanceof SQLCreateTableStatement create)) return;
        for (SQLColumnDefinition column : create.getColumnDefinitions()) {
            SQLDataType type = column.getDataType();
            if (type == null || type.getName() == null) continue;
            type.setName(mappedType(type.getName(), target));
            type.setDbType(target);
            if (column.isAutoIncrement() && target != DbType.mysql && target != DbType.mariadb) {
                column.setAutoIncrement(false);
                if (target == DbType.postgresql) type.setName(type.getName().equalsIgnoreCase("BIGINT") ? "BIGSERIAL" : "SERIAL");
            }
        }
    }

    private String mappedType(String rawType, DbType target) {
        String type = rawType.toUpperCase(Locale.ROOT).replace("UNSIGNED", "").trim();
        if (Set.of("TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB").contains(type)) {
            return target == DbType.oracle || target == DbType.dm || target == DbType.oceanbase_oracle ? "CLOB" : "TEXT";
        }
        if (Set.of("TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BLOB", "BYTEA", "IMAGE", "VARBINARY", "BINARY").contains(type)) {
            return switch (target) {
                case postgresql -> "BYTEA";
                case oracle, dm, oceanbase_oracle -> "BLOB";
                case sqlserver -> "VARBINARY";
                default -> "BLOB";
            };
        }
        if (type.equals("DATETIME") || type.equals("DATETIME2")) return "TIMESTAMP";
        if (type.equals("NUMBER") && target != DbType.oracle && target != DbType.dm && target != DbType.oceanbase_oracle) return "DECIMAL";
        if ((type.equals("BOOLEAN") || type.equals("BOOL")) && (target == DbType.oracle || target == DbType.dm || target == DbType.oceanbase_oracle)) return "NUMBER";
        if (type.equals("JSON") && target != DbType.mysql && target != DbType.mariadb && target != DbType.postgresql) return "CLOB";
        return type;
    }

    DbType dbType(String dbType) {
        String normalized = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mysql", "oceanbase-mysql" -> DbType.mysql;
            case "mariadb" -> DbType.mariadb;
            case "postgres", "postgresql" -> DbType.postgresql;
            case "oracle" -> DbType.oracle;
            case "oceanbase-oracle" -> DbType.oceanbase_oracle;
            case "dm", "dameng" -> DbType.dm;
            case "sqlserver", "sql-server" -> DbType.sqlserver;
            case "h2" -> DbType.h2;
            case "sqlite" -> DbType.sqlite;
            case "clickhouse" -> DbType.clickhouse;
            default -> throw new IllegalArgumentException("不支持的 SQL 方言：" + dbType);
        };
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record Analysis(long statementCount, List<String> namespaces, List<String> tables, List<String> errors) {
    }

    @FunctionalInterface
    public interface TranslatedStatementConsumer {
        void accept(long index, String sql, boolean dataStatement) throws Exception;
    }
}
