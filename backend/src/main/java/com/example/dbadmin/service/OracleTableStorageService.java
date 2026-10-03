package com.example.dbadmin.service;

import org.springframework.stereotype.Service;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Read-only directory queries; row counts are optimizer statistics, bytes are allocated segments. */
@Service
public class OracleTableStorageService {
    public record Partition(String name, String tablespace, String estimatedRows, String lastAnalyzed) {}
    public record Storage(String schemaName, String tableName, String tablespace, boolean partitioned,
            String estimatedRows, String lastAnalyzed, String tableBytes, String indexBytes, String lobBytes,
            List<Partition> partitions, boolean partitionsTruncated, List<String> warnings) {}
    private final ConnectionService connections;
    private final MetadataService metadata;
    public OracleTableStorageService(ConnectionService connections, MetadataService metadata) {
        this.connections = connections; this.metadata = metadata;
    }
    public Storage inspect(long id, String schema, String table) throws Exception {
        if (!"oracle".equalsIgnoreCase(connections.require(id).dbType())) throw new IllegalArgumentException("存储与分区查询仅支持 Oracle。");
        var detail = metadata.detail(id, schema, table, true);
        if (detail.type().toUpperCase(Locale.ROOT).contains("VIEW")) throw new IllegalArgumentException("视图没有独立表段。");
        try (var connection = connections.open(id)) {
            return read(connection, detail.schemaName(), detail.name());
        }
    }
    static Storage read(Connection connection, String schema, String table) throws Exception {
        String tablespace, estimatedRows, analyzed;
        boolean partitioned;
        try (var statement = connection.prepareStatement("SELECT TABLESPACE_NAME, PARTITIONED, NUM_ROWS, TO_CHAR(LAST_ANALYZED, 'YYYY-MM-DD HH24:MI:SS') FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(10); statement.setString(1, schema); statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("表不存在或当前账号不可见。");
                tablespace = rows.getString(1); partitioned = "YES".equals(rows.getString(2));
                estimatedRows = rows.getString(3); analyzed = rows.getString(4);
            }
        }
        List<Partition> partitions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (partitioned) {
            try (var statement = connection.prepareStatement("SELECT PARTITION_NAME, TABLESPACE_NAME, NUM_ROWS, TO_CHAR(LAST_ANALYZED, 'YYYY-MM-DD HH24:MI:SS') FROM ALL_TAB_PARTITIONS WHERE TABLE_OWNER=? AND TABLE_NAME=? ORDER BY PARTITION_POSITION")) {
                statement.setQueryTimeout(10); statement.setMaxRows(501); statement.setString(1, schema); statement.setString(2, table);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) partitions.add(new Partition(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
        }
        boolean truncated = partitions.size() > 500;
        if (truncated) partitions.remove(500);
        String tableBytes = null, indexBytes = null, lobBytes = null;
        boolean own;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT USER FROM DUAL")) {
            own = rows.next() && schema.equals(rows.getString(1));
        }
        // USER_SEGMENTS needs no catalog role. Other owners need DBA_SEGMENTS; report unavailable instead of zero.
        String segments = own ? "(SELECT USER AS OWNER, SEGMENT_NAME, SEGMENT_TYPE, BYTES FROM USER_SEGMENTS)" : "DBA_SEGMENTS";
        String sql = "SELECT NVL(SUM(CASE WHEN SEGMENT_NAME=? AND SEGMENT_TYPE LIKE 'TABLE%' THEN BYTES ELSE 0 END),0), "
                + "NVL(SUM(CASE WHEN SEGMENT_NAME IN (SELECT INDEX_NAME FROM ALL_INDEXES WHERE TABLE_OWNER=? AND TABLE_NAME=? AND INDEX_TYPE<>'LOB') THEN BYTES ELSE 0 END),0), "
                + "NVL(SUM(CASE WHEN SEGMENT_NAME IN (SELECT SEGMENT_NAME FROM ALL_LOBS WHERE OWNER=? AND TABLE_NAME=? UNION ALL SELECT INDEX_NAME FROM ALL_LOBS WHERE OWNER=? AND TABLE_NAME=?) THEN BYTES ELSE 0 END),0) "
                + "FROM " + segments + " WHERE OWNER=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            statement.setString(1, table);
            for (int i = 2; i <= 6; i += 2) { statement.setString(i, schema); statement.setString(i + 1, table); }
            statement.setString(8, schema);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) { tableBytes = rows.getString(1); indexBytes = rows.getString(2); lobBytes = rows.getString(3); }
            }
        } catch (SQLException error) {
            if (error.getErrorCode() != 942 && error.getErrorCode() != 1031) throw error;
            warnings.add("当前账号不能读取该所有者的段容量目录；容量未知。无需为查看表结构授予 DBA 角色。");
        }
        return new Storage(schema, table, tablespace, partitioned, estimatedRows, analyzed, tableBytes, indexBytes, lobBytes,
                List.copyOf(partitions), truncated, List.copyOf(warnings));
    }
}
