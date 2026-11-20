package com.example.dbadmin.service.migration.transfer;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.service.migration.artifact.CanonicalCell;
import com.example.dbadmin.service.migration.artifact.MddPackageReader;
import com.example.dbadmin.service.migration.artifact.MddPackageWriter;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.TableExecutionPlan;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactPartEntry;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalTable;
import com.example.dbadmin.service.migration.model.CanonicalSchema.TableChecksum;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import com.example.dbadmin.service.migration.verify.RowDigestAccumulator;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * 统一流式数据搬运引擎：同时驱动「在线直迁」「导出 .mddpkg」「导入 .mddpkg」三条链路。
 */
@Component
public class JdbcDataCopier {
    private static final long PROGRESS_REPORT_INTERVAL_NANOS = 500_000_000L;

    private final AppProperties properties;

    public JdbcDataCopier(AppProperties properties) {
        this.properties = properties;
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(long rows, long bytes);
    }

    public record CopyStats(long rows, long bytes, TableChecksum sourceChecksum) {
    }

    /** 模式 A：把源表流式导出进 {@link MddPackageWriter}（零内存堆积，自动分片 + 算校验和）。 */
    public CopyStats exportTableToArtifact(
            Connection sourceConn,
            DatabaseDialect sourceJdbcDialect,
            DbTargetDialect sourceDialect,
            String sourceSchema,
            CanonicalTable table,
            String snapshotToken,
            MigrationOptions options,
            MddPackageWriter writer,
            BooleanSupplier cancelled,
            ProgressCallback progress
    ) throws Exception {
        return exportTableToArtifact(sourceConn, sourceJdbcDialect, sourceDialect, sourceSchema,
                table, snapshotToken, options, writer, false, cancelled, progress);
    }

    /** 模式 A（支持并发分片导出）：把源表流式导出进 {@link MddPackageWriter}。 */
    public CopyStats exportTableToArtifact(
            Connection sourceConn,
            DatabaseDialect sourceJdbcDialect,
            DbTargetDialect sourceDialect,
            String sourceSchema,
            CanonicalTable table,
            String snapshotToken,
            MigrationOptions options,
            MddPackageWriter writer,
            boolean parallelWriter,
            BooleanSupplier cancelled,
            ProgressCallback progress
    ) throws Exception {
        String selectSql = buildSourceSelectSql(sourceDialect, sourceSchema, table, snapshotToken);
        int fetchSize = adaptiveFetchSize(table);
        long lastReport = System.nanoTime();
        try (Statement st = sourceConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            sourceJdbcDialect.configureStreamingStatement(sourceConn, st, fetchSize, properties.getMigration().getStatementTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(selectSql);
                 MddPackageWriter.TableWriter tw = parallelWriter
                         ? writer.beginParallelTable(table, sourceDialect.maxTemporalFsp(), options.timezonePolicy())
                         : writer.beginTable(table, sourceDialect.maxTemporalFsp(), options.timezonePolicy())) {
                try {
                    while (rs.next()) {
                        if (cancelled.getAsBoolean()) throw new InterruptedException("导出任务已取消。");
                        List<CanonicalCell> row = readRow(rs, table.columns(), sourceDialect, options.timezonePolicy());
                        try {
                            tw.writeRow(row);
                        } finally {
                            closeRow(row);
                        }
                        long now = System.nanoTime();
                        if (progress != null && now - lastReport >= PROGRESS_REPORT_INTERVAL_NANOS) {
                            progress.onProgress(tw.tableRows(), tw.tableRawBytes());
                            lastReport = now;
                        }
                    }
                    if (progress != null && tw.tableRows() > 0) progress.onProgress(tw.tableRows(), tw.tableRawBytes());
                    return new CopyStats(tw.tableRows(), tw.tableRawBytes(), null);
                } catch (Throwable error) {
                    tw.abort();
                    throw error;
                }
            }
        }
    }

    /** 模式 B：在线双连接流式直迁（零落盘中转，边直写目标库边计算源端 L1/L2/L3 校验摘要）。 */
    public CopyStats copyTableDirect(
            Connection sourceConn,
            DatabaseDialect sourceJdbcDialect,
            DbTargetDialect sourceDialect,
            String sourceSchema,
            Connection targetConn,
            DbTargetDialect targetDialect,
            String targetSchema,
            TableExecutionPlan plan,
            String snapshotToken,
            MigrationOptions options,
            BooleanSupplier cancelled,
            ProgressCallback progress
    ) throws Exception {
        CanonicalTable table = plan.sourceTable();
        String selectSql = buildSourceSelectSql(sourceDialect, sourceSchema, table, snapshotToken);
        List<String> colNames = table.columns().stream().map(CanonicalColumn::name).toList();
        var colTypes = table.columns().stream().map(CanonicalColumn::canonicalType).toList();
        int fspCap = Math.min(sourceDialect.maxTemporalFsp(), targetDialect.maxTemporalFsp());
        RowDigestAccumulator sourceAcc = new RowDigestAccumulator(colNames, colTypes, fspCap, options.timezonePolicy());

        var cfg = properties.getMigration();
        long lastReport = System.nanoTime();
        try (Statement st = sourceConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             LobAwareBatchWriter batchWriter = new LobAwareBatchWriter(
                     targetConn, targetDialect, targetSchema, plan.targetTableName(), plan.columns(), options,
                     cfg.getBatchMaxRows(), cfg.getBatchMaxBytes(), cfg.getLargeLobThresholdBytes(), true)) {
            sourceJdbcDialect.configureStreamingStatement(sourceConn, st, adaptiveFetchSize(table), cfg.getStatementTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(selectSql)) {
                while (rs.next()) {
                    if (cancelled.getAsBoolean()) {
                        batchWriter.rollbackQuietly();
                        throw new InterruptedException("迁移任务已取消。");
                    }
                    List<CanonicalCell> row = readRow(rs, table.columns(), sourceDialect, options.timezonePolicy());
                    try {
                        batchWriter.writeRow(row);
                        // 注意：流式 LOB 在 writeRow -> executeUpdate() 消费完流后已自动写入 lobSha256，此处安全入账！
                        sourceAcc.recordRow(row);
                    } finally {
                        closeRow(row);
                    }
                    long now = System.nanoTime();
                    if (progress != null && now - lastReport >= PROGRESS_REPORT_INTERVAL_NANOS) {
                        progress.onProgress(batchWriter.totalRowsWritten(), batchWriter.totalBytesWritten());
                        lastReport = now;
                    }
                }
                batchWriter.flushAndCommit();
                if (progress != null) progress.onProgress(batchWriter.totalRowsWritten(), batchWriter.totalBytesWritten());
                return new CopyStats(
                        batchWriter.totalRowsWritten(),
                        batchWriter.totalBytesWritten(),
                        sourceAcc.toTableChecksum(table.tableId(), table.name())
                );
            } catch (Exception error) {
                batchWriter.rollbackQuietly();
                throw error;
            }
        }
    }

    /** 模式 C：从 {@code .mddpkg} 制品的指定分片流式导入目标库（天然支持分片级断点续传）。 */
    public CopyStats importPartFromArtifact(
            MddPackageReader reader,
            ArtifactPartEntry part,
            Connection targetConn,
            DbTargetDialect targetDialect,
            String targetSchema,
            TableExecutionPlan plan,
            MigrationOptions options,
            BooleanSupplier cancelled,
            ProgressCallback progress
    ) throws Exception {
        var cfg = properties.getMigration();
        long lastReport = System.nanoTime();
        try (MddPackageReader.PartCursor cursor = reader.openPart(part);
             LobAwareBatchWriter batchWriter = new LobAwareBatchWriter(
                     targetConn, targetDialect, targetSchema, plan.targetTableName(), plan.columns(), options,
                     cfg.getBatchMaxRows(), cfg.getBatchMaxBytes(), cfg.getLargeLobThresholdBytes(), false)) {
            try {
                List<CanonicalCell> row;
                while ((row = cursor.nextRow()) != null) {
                    if (cancelled.getAsBoolean()) {
                        closeRow(row);
                        batchWriter.rollbackQuietly();
                        throw new InterruptedException("导入任务已取消。");
                    }
                    try {
                        batchWriter.writeRow(row);
                    } finally {
                        closeRow(row);
                    }
                    long now = System.nanoTime();
                    if (progress != null && now - lastReport >= PROGRESS_REPORT_INTERVAL_NANOS) {
                        progress.onProgress(batchWriter.totalRowsWritten(), batchWriter.totalBytesWritten());
                        lastReport = now;
                    }
                }
                batchWriter.flushAndCommit();
                if (progress != null) progress.onProgress(batchWriter.totalRowsWritten(), batchWriter.totalBytesWritten());
                return new CopyStats(batchWriter.totalRowsWritten(), batchWriter.totalBytesWritten(), null);
            } catch (Exception error) {
                batchWriter.rollbackQuietly();
                throw error;
            }
        }
    }

    public static int adaptiveFetchSize(CanonicalTable table) {
        if (table == null || table.columns() == null) return 1000;
        boolean hasLob = table.columns().stream().anyMatch(c -> c.canonicalType().isLob());
        return hasLob ? 200 : 2000;
    }

    public static String buildSourceSelectSql(
            DbTargetDialect sourceDialect,
            String sourceSchema,
            CanonicalTable table,
            String snapshotToken
    ) {
        String cols = table.columns().stream()
                .map(c -> sourceDialect.quoteIdentifier(c.name()))
                .collect(Collectors.joining(", "));
        String from = sourceDialect.qualifiedName(sourceSchema, table.name());
        if (sourceDialect == DbTargetDialect.ORACLE && snapshotToken != null && snapshotToken.startsWith("SCN:")) {
            String scn = snapshotToken.substring(4).trim();
            if (scn.matches("\\d+")) {
                return "SELECT " + cols + " FROM " + from + " AS OF SCN " + scn;
            }
        }
        return "SELECT " + cols + " FROM " + from;
    }

    public static List<CanonicalCell> readRow(ResultSet rs, List<CanonicalColumn> columns, DbTargetDialect dialect) throws Exception {
        return readRow(rs, columns, dialect, "UTC");
    }

    public static List<CanonicalCell> readRow(
            ResultSet rs,
            List<CanonicalColumn> columns,
            DbTargetDialect dialect,
            String timezonePolicy
    ) throws Exception {
        List<CanonicalCell> row = new ArrayList<>(columns.size());
        try {
            for (int i = 0; i < columns.size(); i++) {
                row.add(TypedValueBridge.readCell(rs, i + 1, columns.get(i), dialect, timezonePolicy));
            }
            return row;
        } catch (Exception error) {
            closeRow(row);
            throw error;
        }
    }

    public static void closeRow(List<CanonicalCell> row) {
        if (row == null) return;
        for (CanonicalCell cell : row) {
            if (cell != null) {
                try { cell.close(); } catch (Exception ignored) { }
            }
        }
    }
}
