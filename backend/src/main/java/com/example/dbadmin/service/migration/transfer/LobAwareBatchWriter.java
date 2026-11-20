package com.example.dbadmin.service.migration.transfer;

import com.example.dbadmin.service.migration.artifact.CanonicalCell;
import com.example.dbadmin.service.migration.mapping.TypeMappingRegistry.MappedColumn;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 面向 10 GB 重度 BLOB/CLOB 表的双阈值自适应批量写入器。
 *
 * <p>两条硬性保护机制：
 * <ol>
 *   <li><b>标量与小行快路径</b>：同时受 {@code maxBatchRows}（默认 500 行）与 {@code maxBatchBytes}（默认 16 MB）双阈值约束，
 *       任一触顶立即 {@code executeBatch()}；</li>
 *   <li><b>大 LOB 流式直通路径</b>：单行任一列为流式大对象或估算字节超过 {@code largeLobThresholdBytes}（默认 256 KB），
 *       先冲刷已攒批次，再对该行直接走 {@code executeUpdate()} 绑定流式 {@code InputStream/Reader}，
 *       彻底规避 JDBC 驱动批量缓冲 OOM 与流句柄被覆盖的经典 Bug。</li>
 * </ol></p>
 */
public final class LobAwareBatchWriter implements AutoCloseable {
    private final Connection targetConnection;
    private final DbTargetDialect targetDialect;
    private final String targetSchema;
    private final String targetTableName;
    private final List<MappedColumn> columns;
    private final MigrationOptions options;
    private final int maxBatchRows;
    private final long maxBatchBytes;
    private final int largeLobThresholdBytes;
    private final boolean computeDigestOnStream;

    private final PreparedStatement statement;
    private final boolean previousAutoCommit;
    private final boolean transactional;
    private final boolean identityInsertEnabled;
    private final boolean fkChecksDisabled;
    private final boolean uniqueChecksDisabled;

    private int pendingBatchRows;
    private long pendingBatchBytes;
    private long uncommittedRows;
    private long uncommittedBytes;
    private long totalRowsWritten;
    private long totalBytesWritten;

    public LobAwareBatchWriter(
            Connection targetConnection,
            DbTargetDialect targetDialect,
            String targetSchema,
            String targetTableName,
            List<MappedColumn> columns,
            MigrationOptions options,
            int maxBatchRows,
            long maxBatchBytes,
            int largeLobThresholdBytes,
            boolean computeDigestOnStream
    ) throws SQLException {
        this.targetConnection = targetConnection;
        this.targetDialect = targetDialect;
        this.targetSchema = targetSchema;
        this.targetTableName = targetTableName;
        this.columns = List.copyOf(columns);
        this.options = options;
        this.maxBatchRows = targetDialect == DbTargetDialect.CLICKHOUSE ? Math.max(maxBatchRows, 5_000) : Math.max(1, maxBatchRows);
        this.maxBatchBytes = Math.max(1024L * 1024L, maxBatchBytes);
        this.largeLobThresholdBytes = Math.max(32 * 1024, largeLobThresholdBytes);
        this.computeDigestOnStream = computeDigestOnStream;

        this.transactional = targetDialect != DbTargetDialect.CLICKHOUSE;
        this.previousAutoCommit = targetConnection.getAutoCommit();
        if (transactional && previousAutoCommit) {
            targetConnection.setAutoCommit(false);
        }

        // 会话级导入加速与约束放行（在 close() 的 finally 中严格还原）
        this.fkChecksDisabled = disableForeignKeysIfApplicable();
        this.uniqueChecksDisabled = disableUniqueChecksIfApplicable();
        this.identityInsertEnabled = enableSqlServerIdentityInsertIfNeeded();

        String insertSql = buildInsertSql(targetDialect, targetSchema, targetTableName, columns);
        this.statement = targetConnection.prepareStatement(insertSql);
    }

    public void writeRow(List<CanonicalCell> cells) throws Exception {
        boolean hasLargeLob = false;
        long rowEstBytes = 0;
        for (CanonicalCell cell : cells) {
            if (cell == null || cell.isNull()) continue;
            long est = cell.estimatedMemoryBytes();
            rowEstBytes += est;
            if (cell.isStreamingLob() || est >= largeLobThresholdBytes) {
                hasLargeLob = true;
            }
        }

        if (hasLargeLob) {
            // 先把前序标量批次提交出驱动缓冲，再直通执行本大 LOB 行
            flushBatch();
            for (int i = 0; i < columns.size(); i++) {
                CanonicalCell cell = i < cells.size() ? cells.get(i) : CanonicalCell.ofNull();
                TypedValueBridge.bindCell(statement, i + 1, cell, columns.get(i), targetDialect, options, computeDigestOnStream);
            }
            statement.executeUpdate();
            statement.clearParameters();
            long actualRowBytes = computeActualRowBytes(cells, rowEstBytes);
            totalRowsWritten++;
            totalBytesWritten += actualRowBytes;
            uncommittedRows++;
            uncommittedBytes += actualRowBytes;
            maybeCommit();
            return;
        }

        for (int i = 0; i < columns.size(); i++) {
            CanonicalCell cell = i < cells.size() ? cells.get(i) : CanonicalCell.ofNull();
            TypedValueBridge.bindCell(statement, i + 1, cell, columns.get(i), targetDialect, options, computeDigestOnStream);
        }
        statement.addBatch();
        pendingBatchRows++;
        pendingBatchBytes += rowEstBytes;
        totalRowsWritten++;
        totalBytesWritten += rowEstBytes;
        if (pendingBatchRows >= maxBatchRows || pendingBatchBytes >= maxBatchBytes) {
            flushBatch();
        }
    }

    public void flushAndCommit() throws SQLException {
        flushBatch();
        if (transactional && (uncommittedRows > 0 || uncommittedBytes > 0)) {
            targetConnection.commit();
            uncommittedRows = 0;
            uncommittedBytes = 0;
        }
    }

    public void rollbackQuietly() {
        if (!transactional) return;
        try {
            targetConnection.rollback();
        } catch (SQLException ignored) {
        }
    }

    public long totalRowsWritten() { return totalRowsWritten; }
    public long totalBytesWritten() { return totalBytesWritten; }

    private void flushBatch() throws SQLException {
        if (pendingBatchRows == 0) return;
        statement.executeBatch();
        statement.clearBatch();
        uncommittedRows += pendingBatchRows;
        uncommittedBytes += pendingBatchBytes;
        pendingBatchRows = 0;
        pendingBatchBytes = 0;
        maybeCommit();
    }

    private void maybeCommit() throws SQLException {
        if (!transactional) return;
        // 每 10000 行或累计 128 MB 提交一次事务，兼顾百 GB 导入吞吐与 UNDO/WAL 上界
        if (uncommittedRows >= 10_000 || uncommittedBytes >= 128L * 1024 * 1024) {
            targetConnection.commit();
            uncommittedRows = 0;
            uncommittedBytes = 0;
        }
    }

    private static long computeActualRowBytes(List<CanonicalCell> cells, long fallback) {
        long sum = 0;
        for (CanonicalCell c : cells) {
            if (c == null || c.isNull()) continue;
            sum += c.actualLobBytes() > 0 ? c.actualLobBytes() : c.estimatedMemoryBytes();
        }
        return Math.max(sum, fallback);
    }

    private boolean disableForeignKeysIfApplicable() {
        try {
            if (targetDialect.isMySqlFamily()) {
                try (Statement st = targetConnection.createStatement()) {
                    st.execute("SET FOREIGN_KEY_CHECKS = 0");
                    return true;
                }
            }
            if (targetDialect == DbTargetDialect.SQLITE) {
                try (Statement st = targetConnection.createStatement()) {
                    st.execute("PRAGMA foreign_keys = OFF");
                    return true;
                }
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    private boolean disableUniqueChecksIfApplicable() {
        if (!targetDialect.isMySqlFamily()) return false;
        try (Statement st = targetConnection.createStatement()) {
            st.execute("SET UNIQUE_CHECKS = 0");
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private boolean enableSqlServerIdentityInsertIfNeeded() {
        if (targetDialect != DbTargetDialect.SQLSERVER) return false;
        boolean hasIdentity = columns.stream().anyMatch(c -> c.source().identity() != null);
        if (!hasIdentity) return false;
        try (Statement st = targetConnection.createStatement()) {
            st.execute("SET IDENTITY_INSERT " + targetDialect.qualifiedName(targetSchema, targetTableName) + " ON");
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    static String buildInsertSql(DbTargetDialect target, String schema, String tableName, List<MappedColumn> columns) {
        String colList = columns.stream()
                .map(c -> target.quoteIdentifier(c.targetColumnName()))
                .collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + target.qualifiedName(schema, tableName) + " (" + colList + ") VALUES (" + placeholders + ")";
    }

    @Override
    public void close() throws SQLException {
        SQLException first = null;
        try {
            statement.close();
        } catch (SQLException e) {
            first = e;
        }
        if (identityInsertEnabled) {
            try (Statement st = targetConnection.createStatement()) {
                st.execute("SET IDENTITY_INSERT " + targetDialect.qualifiedName(targetSchema, targetTableName) + " OFF");
            } catch (SQLException e) {
                if (first == null) first = e;
            }
        }
        if (uniqueChecksDisabled) {
            try (Statement st = targetConnection.createStatement()) {
                st.execute("SET UNIQUE_CHECKS = 1");
            } catch (SQLException e) {
                if (first == null) first = e;
            }
        }
        if (fkChecksDisabled) {
            try (Statement st = targetConnection.createStatement()) {
                if (targetDialect.isMySqlFamily()) st.execute("SET FOREIGN_KEY_CHECKS = 1");
                else if (targetDialect == DbTargetDialect.SQLITE) st.execute("PRAGMA foreign_keys = ON");
            } catch (SQLException e) {
                if (first == null) first = e;
            }
        }
        if (transactional && previousAutoCommit) {
            try {
                targetConnection.setAutoCommit(true);
            } catch (SQLException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }
}
