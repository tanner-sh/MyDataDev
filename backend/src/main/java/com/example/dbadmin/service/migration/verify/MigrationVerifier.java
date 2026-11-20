package com.example.dbadmin.service.migration.verify;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.dto.MigrationDtos.MigrationVerificationReport;
import com.example.dbadmin.dto.MigrationDtos.TableVerificationResult;
import com.example.dbadmin.service.migration.artifact.CanonicalCell;
import com.example.dbadmin.service.migration.artifact.MddPackageFormat;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.TableExecutionPlan;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ColumnAggregate;
import com.example.dbadmin.service.migration.model.CanonicalSchema.TableChecksum;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import com.example.dbadmin.service.migration.transfer.JdbcDataCopier;
import com.example.dbadmin.service.migration.transfer.TypedValueBridge;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * 端到端数据一致性校验引擎（支持在线直迁与离线 {@code .mddpkg} 导入双重模式）。
 *
 * <ul>
 *   <li><b>L1_COUNT</b>：逐表精确行数校验；</li>
 *   <li><b>L2_AGGREGATE</b>：行数 + 各列非空数、数值 SUM/MIN/MAX、时间 MIN/MAX 与 LOB 总字节数；</li>
 *   <li><b>L3_FULL_HASH</b>：L1 + L2 + 顺序无关 256-bit 流式行级与全字节 LOB 多重集哈希对比。</li>
 * </ul>
 */
@Component
public class MigrationVerifier {
    private final AppProperties properties;

    public MigrationVerifier(AppProperties properties) {
        this.properties = properties;
    }

    /** 针对单张表在目标库计算校验摘要（L1 仅跑 COUNT(*)，L2/L3 走流式扫描并流式算 LOB SHA-256）。 */
    public TableVerificationResult verifyTable(
            TableExecutionPlan plan,
            TableChecksum expectedSourceChecksum,
            Connection targetConn,
            DatabaseDialect targetJdbcDialect,
            DbTargetDialect targetDialect,
            String targetSchema,
            String verifyLevel,
            MigrationOptions options,
            BooleanSupplier cancelled
    ) throws Exception {
        long started = System.nanoTime();
        String level = verifyLevel == null || verifyLevel.isBlank() ? "L3_FULL_HASH" : verifyLevel.toUpperCase();
        String tableId = plan.sourceTable().tableId();
        String sourceName = plan.sourceTable().name();
        String targetName = plan.targetTableName();

        if (expectedSourceChecksum == null) {
            return new TableVerificationResult(
                    tableId, sourceName, targetName, "SKIPPED", level, 0, 0,
                    false, false, null, 0, 0, null, null,
                    List.of("缺少源端/制品基线校验摘要，已跳过"), elapsedMs(started)
            );
        }

        if ("L1_COUNT".equals(level) || expectedSourceChecksum.rowCount() == 0L) {
            long targetCount = countRows(targetConn, targetDialect, targetSchema, targetName);
            boolean match = targetCount == expectedSourceChecksum.rowCount();
            List<String> mismatches = match ? List.of()
                    : List.of("行数不一致：源=" + expectedSourceChecksum.rowCount() + "，目标=" + targetCount);
            Boolean hashMatch = "L3_FULL_HASH".equals(level) ? match : null;
            return new TableVerificationResult(
                    tableId, sourceName, targetName, match ? "PASSED" : "FAILED", level,
                    expectedSourceChecksum.rowCount(), targetCount, match, match, hashMatch,
                    expectedSourceChecksum.totalLobBytes(), 0,
                    expectedSourceChecksum.multisetHashHex(), match ? expectedSourceChecksum.multisetHashHex() : null,
                    mismatches, elapsedMs(started)
            );
        }

        int fspCap = Math.min(expectedSourceChecksum.temporalFspCap(), targetDialect.maxTemporalFsp());
        TableChecksum actual = scanTableChecksum(
                targetConn, targetJdbcDialect, targetDialect, targetSchema, plan,
                fspCap, options.timezonePolicy(), cancelled
        );

        List<String> mismatches = new ArrayList<>();
        boolean countMatch = actual.rowCount() == expectedSourceChecksum.rowCount();
        if (!countMatch) {
            mismatches.add("行数不一致：源=" + expectedSourceChecksum.rowCount() + "，目标=" + actual.rowCount());
        }
        boolean aggMatch = compareAggregates(expectedSourceChecksum.columnAggregates(), actual.columnAggregates(), mismatches);
        Boolean hashMatch = null;
        if ("L3_FULL_HASH".equals(level)) {
            hashMatch = Objects.equals(expectedSourceChecksum.multisetHashHex(), actual.multisetHashHex());
            if (!hashMatch) {
                mismatches.add("256-bit 行级/LOB 多重集哈希不一致（源=" + abbreviateHash(expectedSourceChecksum.multisetHashHex())
                        + "，目标=" + abbreviateHash(actual.multisetHashHex()) + "）");
            }
        }
        boolean passed = countMatch && aggMatch && (hashMatch == null || hashMatch);
        return new TableVerificationResult(
                tableId, sourceName, targetName, passed ? "PASSED" : "FAILED", level,
                expectedSourceChecksum.rowCount(), actual.rowCount(), countMatch, aggMatch, hashMatch,
                expectedSourceChecksum.totalLobBytes(), actual.totalLobBytes(),
                expectedSourceChecksum.multisetHashHex(), actual.multisetHashHex(),
                List.copyOf(mismatches), elapsedMs(started)
        );
    }

    /** 流式扫描目标表并计算完整 L1/L2/L3 摘要（恒定 O(1) 堆内存，全量读取并校验所有 BLOB/CLOB 字节）。 */
    public TableChecksum scanTableChecksum(
            Connection conn,
            DatabaseDialect jdbcDialect,
            DbTargetDialect dialect,
            String schema,
            TableExecutionPlan plan,
            int fspCap,
            String timezonePolicy,
            BooleanSupplier cancelled
    ) throws Exception {
        List<String> sourceColNames = plan.columns().stream().map(c -> c.source().name()).toList();
        List<CanonicalColumn> readingCols = plan.columns().stream().map(c -> c.source()).toList();
        var colTypes = readingCols.stream().map(CanonicalColumn::canonicalType).toList();
        String colSql = plan.columns().stream()
                .map(c -> dialect.quoteIdentifier(c.targetColumnName()))
                .collect(Collectors.joining(", "));
        String sql = "SELECT " + colSql + " FROM " + dialect.qualifiedName(schema, plan.targetTableName());

        int fetchSize = JdbcDataCopier.adaptiveFetchSize(plan.sourceTable());
        RowDigestAccumulator acc = new RowDigestAccumulator(sourceColNames, colTypes, fspCap, timezonePolicy);
        try (Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            jdbcDialect.configureStreamingStatement(conn, st, fetchSize, properties.getMigration().getStatementTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    if (cancelled.getAsBoolean()) throw new InterruptedException("数据校验已取消。");
                    List<CanonicalCell> row = new ArrayList<>(readingCols.size());
                    try {
                        for (int i = 0; i < readingCols.size(); i++) {
                            CanonicalCell cell = TypedValueBridge.readCell(rs, i + 1, readingCols.get(i), dialect, timezonePolicy);
                            if (cell.isStreamingLob()) {
                                digestAndDrainLob(cell);
                            }
                            row.add(cell);
                        }
                        acc.recordRow(row);
                    } finally {
                        JdbcDataCopier.closeRow(row);
                    }
                }
            }
        }
        return acc.toTableChecksum(plan.sourceTable().tableId(), plan.sourceTable().name());
    }

    /** 直接扫描源表计算基线校验摘要（用于在线直迁后手动重跑校验）。 */
    public TableChecksum scanSourceChecksum(
            Connection conn,
            DatabaseDialect jdbcDialect,
            DbTargetDialect sourceDialect,
            String sourceSchema,
            TableExecutionPlan plan,
            String snapshotToken,
            int fspCap,
            String timezonePolicy,
            BooleanSupplier cancelled
    ) throws Exception {
        var table = plan.sourceTable();
        List<String> colNames = table.columns().stream().map(CanonicalColumn::name).toList();
        var colTypes = table.columns().stream().map(CanonicalColumn::canonicalType).toList();
        String sql = JdbcDataCopier.buildSourceSelectSql(sourceDialect, sourceSchema, table, snapshotToken);
        int fetchSize = JdbcDataCopier.adaptiveFetchSize(table);
        RowDigestAccumulator acc = new RowDigestAccumulator(colNames, colTypes, fspCap, timezonePolicy);
        try (Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            jdbcDialect.configureStreamingStatement(conn, st, fetchSize, properties.getMigration().getStatementTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    if (cancelled.getAsBoolean()) throw new InterruptedException("源表扫描已取消。");
                    List<CanonicalCell> row = new ArrayList<>(table.columns().size());
                    try {
                        for (int i = 0; i < table.columns().size(); i++) {
                            CanonicalCell cell = TypedValueBridge.readCell(rs, i + 1, table.columns().get(i), sourceDialect, timezonePolicy);
                            if (cell.isStreamingLob()) digestAndDrainLob(cell);
                            row.add(cell);
                        }
                        acc.recordRow(row);
                    } finally {
                        JdbcDataCopier.closeRow(row);
                    }
                }
            }
        }
        return acc.toTableChecksum(table.tableId(), table.name());
    }

    public MigrationVerificationReport assembleReport(
            long jobId,
            String verifyMode,
            String verifyLevel,
            List<TableVerificationResult> results,
            List<String> notes
    ) {
        int passed = 0, failed = 0, skipped = 0;
        long totalRows = 0, totalLobBytes = 0;
        for (TableVerificationResult r : results) {
            if ("PASSED".equals(r.status())) passed++;
            else if ("FAILED".equals(r.status())) failed++;
            else skipped++;
            totalRows += r.targetRowCount();
            totalLobBytes += Math.max(r.sourceLobBytes(), r.targetLobBytes());
        }
        String overall = failed > 0 ? "FAILED" : (passed > 0 ? "PASSED" : "SKIPPED");
        return new MigrationVerificationReport(
                jobId, verifyMode, verifyLevel, overall,
                results.size(), passed, failed, skipped,
                totalRows, totalLobBytes, Instant.now().toString(),
                List.copyOf(results), List.copyOf(notes)
        );
    }

    public String renderMarkdownReport(MigrationVerificationReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# MyDataDev 数据库迁移一致性验收报告\n\n");
        md.append("- 任务 ID：`").append(report.jobId()).append("`\n");
        md.append("- 校验模式：`").append(report.verifyMode()).append("` / 级别：`").append(report.verifyLevel()).append("`\n");
        md.append("- 总体结论：**").append("PASSED".equals(report.overallStatus()) ? "通过 (PASSED)" : report.overallStatus()).append("**\n");
        md.append("- 验收时间：").append(report.verifiedAt()).append("\n");
        md.append("- 表汇总：共 ").append(report.totalTables()).append(" 张，通过 ").append(report.passedTables())
                .append("，失败 ").append(report.failedTables()).append("，跳过 ").append(report.skippedTables()).append("\n");
        md.append("- 累计校验行数：").append(report.totalRowsVerified())
                .append("，累计校验大对象字节：").append(report.totalLobBytesVerified()).append(" B\n\n");
        md.append("| 源表 | 目标表 | 状态 | 源行数 | 目标行数 | LOB 字节(源/目标) | L3 多重集哈希 | 耗时(ms) | 差异说明 |\n");
        md.append("| :--- | :--- | :---: | ---: | ---: | ---: | :---: | ---: | :--- |\n");
        for (TableVerificationResult r : report.tables()) {
            String hashLabel = r.multisetHashMatch() == null ? "-" : (r.multisetHashMatch() ? "一致" : "不一致");
            String diff = r.mismatches().isEmpty() ? "-" : String.join("；", r.mismatches());
            md.append("| ").append(r.sourceName())
                    .append(" | ").append(r.targetName())
                    .append(" | ").append(r.status())
                    .append(" | ").append(r.sourceRowCount())
                    .append(" | ").append(r.targetRowCount())
                    .append(" | ").append(r.sourceLobBytes()).append(" / ").append(r.targetLobBytes())
                    .append(" | ").append(hashLabel)
                    .append(" | ").append(r.elapsedMs())
                    .append(" | ").append(diff.replace("|", "\\|")).append(" |\n");
        }
        if (!report.notes().isEmpty()) {
            md.append("\n## 说明\n");
            for (String note : report.notes()) md.append("- ").append(note).append('\n');
        }
        return md.toString();
    }

    private long countRows(Connection conn, DbTargetDialect dialect, String schema, String tableName) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + dialect.qualifiedName(schema, tableName);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void digestAndDrainLob(CanonicalCell cell) throws Exception {
        MessageDigest md = RowDigestAccumulator.sha256();
        long totalBytes = 0;
        if (cell.kind() == CanonicalCell.Kind.STREAM_BLOB) {
            byte[] buf = new byte[MddPackageFormat.IO_BUFFER_BYTES];
            try (InputStream in = cell.asBlobStream()) {
                int r;
                while ((r = in.read(buf)) >= 0) {
                    if (r > 0) { md.update(buf, 0, r); totalBytes += r; }
                }
            }
        } else if (cell.kind() == CanonicalCell.Kind.STREAM_CLOB) {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            char[] cbuf = new char[16 * 1024];
            ByteBuffer bbuf = ByteBuffer.allocate(64 * 1024);
            try (Reader reader = cell.asClobStream()) {
                int r;
                while ((r = reader.read(cbuf)) >= 0) {
                    if (r == 0) continue;
                    CharBuffer inBuf = CharBuffer.wrap(cbuf, 0, r);
                    while (inBuf.hasRemaining()) {
                        bbuf.clear();
                        encoder.encode(inBuf, bbuf, false);
                        bbuf.flip();
                        int n = bbuf.remaining();
                        if (n > 0) { md.update(bbuf.array(), 0, n); totalBytes += n; }
                    }
                }
                CharBuffer empty = CharBuffer.allocate(0);
                while (true) {
                    bbuf.clear();
                    var cr = encoder.encode(empty, bbuf, true);
                    bbuf.flip();
                    int n = bbuf.remaining();
                    if (n > 0) { md.update(bbuf.array(), 0, n); totalBytes += n; }
                    if (!cr.isOverflow()) break;
                }
                bbuf.clear();
                encoder.flush(bbuf);
                bbuf.flip();
                if (bbuf.hasRemaining()) {
                    int n = bbuf.remaining();
                    md.update(bbuf.array(), 0, n);
                    totalBytes += n;
                }
            }
        }
        cell.recordLobDigest(md.digest(), totalBytes);
    }

    private boolean compareAggregates(Map<String, ColumnAggregate> src, Map<String, ColumnAggregate> tgt, List<String> mismatches) {
        if (src == null || tgt == null) return true;
        boolean ok = true;
        for (var e : src.entrySet()) {
            String col = e.getKey();
            ColumnAggregate s = e.getValue();
            ColumnAggregate t = tgt.get(col);
            if (t == null) continue;
            if (s.nonNullCount() != t.nonNullCount()) {
                ok = false;
                mismatches.add("列 " + col + " 非空行数不一致（源=" + s.nonNullCount() + "，目标=" + t.nonNullCount() + "）");
            }
            if (!sameNumeric(s.numericSum(), t.numericSum())) {
                ok = false;
                mismatches.add("列 " + col + " 数值合计不一致（源=" + s.numericSum() + "，目标=" + t.numericSum() + "）");
            }
            if (s.lobByteSum() != t.lobByteSum()) {
                ok = false;
                mismatches.add("列 " + col + " 文本/LOB 字节总数不一致（源=" + s.lobByteSum() + "，目标=" + t.lobByteSum() + "）");
            }
        }
        return ok;
    }

    private static boolean sameNumeric(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException ignored) {
            return a.equals(b);
        }
    }

    private static String abbreviateHash(String hex) {
        if (hex == null || hex.length() <= 16) return hex;
        return hex.substring(0, 16) + "…";
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
