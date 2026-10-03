package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.TableDesignResponse;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;

@Service
public class MySqlTablePropertiesService {
    public record Collation(String name, String characterSet) {}
    public record Properties(String schemaName, String tableName, String engine, String characterSet,
            String collation, String autoIncrement, String estimatedRows, String dataBytes, String indexBytes,
            String version, List<String> engines, List<Collation> collations) {}
    public record Change(String schemaName, String tableName, String engine, String characterSet,
            String collation, String autoIncrement, String version, String confirmation) {}

    private final ConnectionService connections;
    private final MetadataService metadata;
    private final DialectRegistry dialects;
    private final MetadataCacheService cache;
    private final ExecutionGuard guard;
    private final AuditRepository audit;

    public MySqlTablePropertiesService(ConnectionService connections, MetadataService metadata, DialectRegistry dialects,
            MetadataCacheService cache, ExecutionGuard guard, AuditRepository audit) {
        this.connections = connections; this.metadata = metadata; this.dialects = dialects;
        this.cache = cache; this.guard = guard; this.audit = audit;
    }

    public Properties inspect(long id, String schema, String table) throws Exception {
        String type = connections.require(id).dbType();
        if (!"mysql".equalsIgnoreCase(type) && !"mariadb".equalsIgnoreCase(type))
            throw new IllegalArgumentException("表属性管理仅支持 MySQL 与 MariaDB。");
        var detail = metadata.detail(id, schema, table, true);
        if (detail.type().toUpperCase(Locale.ROOT).contains("VIEW")) throw new IllegalArgumentException("视图不支持修改表属性。");
        var dialect = dialects.dialectFor(connections.require(id));
        String qualified = dialect.qualifiedName(detail.schemaName(), detail.name());
        try (Connection connection = connections.open(id)) {
            String version;
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                try (var rows = statement.executeQuery("SHOW CREATE TABLE " + qualified)) {
                    if (!rows.next()) throw new IllegalArgumentException("无法读取表定义。");
                    version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rows.getString(2).getBytes(StandardCharsets.UTF_8)));
                }
            }
            List<String> engines = new ArrayList<>();
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                try (var rows = statement.executeQuery("SELECT ENGINE FROM information_schema.ENGINES WHERE SUPPORT IN ('YES','DEFAULT') AND ENGINE IN ('InnoDB','MyISAM','Aria') ORDER BY ENGINE")) {
                    while (rows.next()) engines.add(rows.getString(1));
                }
            }
            List<Collation> collations;
            if ("mariadb".equalsIgnoreCase(type)) {
                try {
                    collations = readCollations(connection, "SELECT FULL_COLLATION_NAME, CHARACTER_SET_NAME FROM information_schema.COLLATION_CHARACTER_SET_APPLICABILITY ORDER BY FULL_COLLATION_NAME");
                } catch (java.sql.SQLException error) {
                    if (error.getErrorCode() != 1054 && !"42S22".equals(error.getSQLState())) throw error;
                    collations = readCollations(connection, "SELECT COLLATION_NAME, CHARACTER_SET_NAME FROM information_schema.COLLATIONS ORDER BY COLLATION_NAME");
                }
            } else collations = readCollations(connection, "SELECT COLLATION_NAME, CHARACTER_SET_NAME FROM information_schema.COLLATIONS ORDER BY COLLATION_NAME");
            try (var statement = connection.prepareStatement("""
                    SELECT t.ENGINE, t.TABLE_COLLATION, t.AUTO_INCREMENT,
                           t.TABLE_ROWS, t.DATA_LENGTH, t.INDEX_LENGTH
                    FROM information_schema.TABLES t
                    WHERE t.TABLE_SCHEMA=? AND t.TABLE_NAME=?
                    """)) {
                statement.setQueryTimeout(10); statement.setString(1, detail.schemaName()); statement.setString(2, detail.name());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("未找到表属性，请刷新。");
                    String tableCollation = rows.getString(2);
                    String characterSet = collations.stream().filter(c -> c.name().equals(tableCollation)).map(Collation::characterSet).findFirst().orElse(null);
                    return new Properties(detail.schemaName(), detail.name(), rows.getString(1), characterSet, tableCollation,
                            rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), version,
                            List.copyOf(engines), List.copyOf(collations));
                }
            }
        }
    }

    private List<Collation> readCollations(Connection connection, String sql) throws Exception {
        List<Collation> result = new ArrayList<>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10); statement.setMaxRows(10_000);
            try (var rows = statement.executeQuery(sql)) {
                while (rows.next()) if (rows.getString(1) != null && rows.getString(2) != null)
                    result.add(new Collation(rows.getString(1), rows.getString(2)));
            }
        }
        return result;
    }

    public TableDesignResponse preview(long id, Change change) throws Exception {
        var current = inspect(id, change.schemaName(), change.tableName());
        var sql = ddl(current, change, dialects.dialectFor(connections.require(id)).qualifiedName(current.schemaName(), current.tableName()));
        return new TableDesignResponse(sql, sql.isEmpty() ? "没有属性变更。" : "已生成 DDL；更换引擎可能重建并锁定表。默认字符集只影响后续新增列。");
    }

    public TableDesignResponse execute(long id, Change change, String actor, String productionConfirmation) throws Exception {
        guard.requireMutationAllowed(connections.require(id), productionConfirmation);
        connections.requireNoManualTransaction(id);
        var current = inspect(id, change.schemaName(), change.tableName());
        String expected = current.schemaName() + "." + current.tableName();
        if (!expected.equals(change.confirmation())) throw new IllegalArgumentException("请输入完整表名确认：" + expected);
        var sql = ddl(current, change, dialects.dialectFor(connections.require(id)).qualifiedName(current.schemaName(), current.tableName()));
        if (sql.isEmpty()) return new TableDesignResponse(sql, "没有属性变更。");
        try (var connection = connections.open(id); var statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            statement.execute(sql.get(0));
        } finally { cache.evictObject(id, current.schemaName(), current.tableName()); }
        audit.onConnection(actor, "TABLE_PROPERTIES_UPDATE", id, expected, sql.get(0));
        return new TableDesignResponse(sql, "表属性已更新。");
    }

    static List<String> ddl(Properties current, Change change, String qualified) {
        if (change.version() == null || !change.version().equals(current.version()))
            throw new ApiProblemException(HttpStatus.CONFLICT, "STALE_TABLE_PROPERTIES", "表定义或自增状态已变化，请刷新属性后重试。");
        List<String> options = new ArrayList<>();
        if (!Objects.equals(current.engine(), change.engine())) {
            if (!current.engines().contains(change.engine())) throw new IllegalArgumentException("存储引擎不可用。");
            options.add("ENGINE=" + token(change.engine()));
        }
        if (!Objects.equals(current.characterSet(), change.characterSet()) || !Objects.equals(current.collation(), change.collation())) {
            if (current.collations().stream().noneMatch(c -> c.name().equals(change.collation()) && c.characterSet().equals(change.characterSet())))
                throw new IllegalArgumentException("字符集与排序规则不匹配或不可用。");
            options.add("DEFAULT CHARACTER SET " + token(change.characterSet()) + " COLLATE " + token(change.collation()));
        }
        String requested = change.autoIncrement() == null || change.autoIncrement().isBlank() ? null : change.autoIncrement().trim();
        if (!Objects.equals(current.autoIncrement(), requested)) {
            if (current.autoIncrement() == null) throw new IllegalArgumentException("当前表没有自增字段。");
            if (requested == null || !requested.matches("[1-9][0-9]{0,19}")
                    || new BigInteger(requested).compareTo(new BigInteger("18446744073709551615")) > 0)
                throw new IllegalArgumentException("自增起点必须是正整数，且不超过无符号 64 位整数范围。");
            if (new BigInteger(requested).compareTo(new BigInteger(current.autoIncrement())) < 0)
                throw new IllegalArgumentException("自增起点只允许提高，不能低于当前下一自增值。");
            options.add("AUTO_INCREMENT=" + requested);
        }
        return options.isEmpty() ? List.of() : List.of("ALTER TABLE " + qualified + " " + String.join(", ", options));
    }

    private static String token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("属性名称包含非法字符。");
        return value;
    }
}
