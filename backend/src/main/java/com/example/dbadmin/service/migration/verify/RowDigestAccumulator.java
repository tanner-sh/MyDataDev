package com.example.dbadmin.service.migration.verify;

import com.example.dbadmin.service.migration.artifact.CanonicalCell;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ColumnAggregate;
import com.example.dbadmin.service.migration.model.CanonicalSchema.TableChecksum;
import com.example.dbadmin.service.migration.model.CanonicalTypeKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * O(1) 内存、顺序无关的 256-bit 多重集哈希与 L1/L2/L3 校验累加器。
 *
 * <p>原理：对每行规范化编码算 {@code SHA-256}（32 字节 = 4 个无符号 64 位 limb），将全表所有行的哈希在
 * $\mathbb{Z}/2^{256}\mathbb{Z}$ 下做无符号模加：
 * <ul>
 *   <li><b>100% 顺序无关</b>：彻底免疫 Oracle / MySQL / PostgreSQL 排序规则不一致，且支持无主键表；</li>
 *   <li><b>可检测重复行</b>：与 XOR 不同，两行完全相同记录相加不会抵消（{@code H + H != 0}）；</li>
 *   <li><b>可并发归并</b>：多分片并行读取各自累加，最后调用 {@link #merge} 合并结果与单线程扫描逐位一致；</li>
 *   <li><b>完整覆盖 BLOB/CLOB</b>：大对象流式读取时同步算出 32 字节 SHA-256 折入行摘要，堆占用恒定 32 字节/表。</li>
 * </ul></p>
 */
public final class RowDigestAccumulator {
    private static final ObjectMapper JSON_CANONICAL_MAPPER = new ObjectMapper();

    private final List<String> columnNames;
    private final List<CanonicalTypeKind> columnTypes;
    private final int temporalFspCap;
    private final String timezonePolicy;
    private final ColAcc[] colAccs;

    private long rowCount;
    private long totalLobBytes;
    // 大端序 4 个无符号 64-bit limbs（s0 最高位，s3 最低位），模 2^256 相加
    private long s0, s1, s2, s3;

    public RowDigestAccumulator(List<String> columnNames, int temporalFspCap, String timezonePolicy) {
        this(columnNames, List.of(), temporalFspCap, timezonePolicy);
    }

    public RowDigestAccumulator(
            List<String> columnNames,
            List<CanonicalTypeKind> columnTypes,
            int temporalFspCap,
            String timezonePolicy
    ) {
        this.columnNames = List.copyOf(columnNames);
        this.columnTypes = columnTypes == null ? List.of() : List.copyOf(columnTypes);
        this.temporalFspCap = temporalFspCap;
        this.timezonePolicy = timezonePolicy == null ? "UTC" : timezonePolicy;
        this.colAccs = new ColAcc[columnNames.size()];
        for (int i = 0; i < colAccs.length; i++) {
            colAccs[i] = new ColAcc();
        }
    }

    /** 记录一行并更新 L1 行数、L2 聚合统计与 L3 256-bit 多重集哈希。 */
    public void recordRow(List<CanonicalCell> cells) {
        MessageDigest rowDigest = sha256();
        int count = Math.min(cells.size(), colAccs.length);
        for (int i = 0; i < count; i++) {
            CanonicalCell cell = cells.get(i);
            ColAcc acc = colAccs[i];
            if (cell == null || cell.isNull()) {
                rowDigest.update((byte) 0x00);
                continue;
            }
            acc.nonNullCount++;
            rowDigest.update((byte) 0x01);
            switch (cell.kind()) {
                case BOOLEAN -> {
                    byte[] bytes = cell.utf8BytesForHash(temporalFspCap, timezonePolicy);
                    rowDigest.update(bytes);
                    acc.recordNumeric(BigDecimal.valueOf(cell.asBoolean() ? 1 : 0));
                }
                case INT64 -> {
                    byte[] bytes = cell.utf8BytesForHash(temporalFspCap, timezonePolicy);
                    rowDigest.update(bytes);
                    acc.recordNumeric(BigDecimal.valueOf(cell.asInt64()));
                }
                case FLOAT64 -> {
                    double d = cell.asFloat64();
                    if (d == 0.0d) d = 0.0d;
                    byte[] bytes = Double.toString(d).getBytes(StandardCharsets.UTF_8);
                    rowDigest.update(bytes);
                    if (Double.isFinite(d)) acc.recordNumeric(BigDecimal.valueOf(d));
                }
                case DECIMAL -> {
                    String norm = CanonicalCell.normalizeBigDecimal(cell.asDecimal());
                    rowDigest.update(norm.getBytes(StandardCharsets.UTF_8));
                    acc.recordNumeric(cell.asDecimal());
                }
                case STRING -> {
                    String s = cell.asString();
                    if (isJsonColumn(i)) {
                        s = canonicalizeJson(s);
                    }
                    byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
                    if (utf8.length > 1024) {
                        byte[] h = sha256().digest(utf8);
                        rowDigest.update((byte) 0x02);
                        rowDigest.update(h);
                        acc.lobByteSum += utf8.length;
                        totalLobBytes += utf8.length;
                    } else {
                        rowDigest.update((byte) 0x02);
                        rowDigest.update(sha256().digest(utf8));
                        acc.recordMinMax(s);
                        acc.lobByteSum += utf8.length;
                    }
                }
                case DATE, TIME, DATETIME, TIMESTAMP_TZ -> {
                    String text = cell.canonicalHashText(temporalFspCap, timezonePolicy);
                    rowDigest.update(text.getBytes(StandardCharsets.UTF_8));
                    acc.recordMinMax(text);
                }
                case BYTES -> {
                    byte[] raw = cell.asBytes();
                    byte[] h = cell.lobSha256() != null ? cell.lobSha256() : sha256().digest(raw);
                    rowDigest.update((byte) 0x03);
                    rowDigest.update(h);
                    acc.lobByteSum += raw.length;
                    totalLobBytes += raw.length;
                }
                case STREAM_BLOB, STREAM_CLOB -> {
                    byte[] h = cell.lobSha256();
                    if (h == null) {
                        throw new IllegalStateException("流式大对象在参与校验哈希前尚未完成流式摘要计算。");
                    }
                    rowDigest.update(cell.kind() == CanonicalCell.Kind.STREAM_CLOB ? (byte) 0x02 : (byte) 0x03);
                    rowDigest.update(h);
                    acc.lobByteSum += cell.actualLobBytes();
                    if (cell.kind() == CanonicalCell.Kind.STREAM_BLOB || cell.actualLobBytes() > 1024) {
                        totalLobBytes += cell.actualLobBytes();
                    }
                }
                default -> { }
            }
            rowDigest.update((byte) 0x1F);
        }
        addHashMod256(rowDigest.digest());
        rowCount++;
    }

    /** 合并另一个分片/线程的累加器（结合律 + 交换律）。 */
    public synchronized void merge(RowDigestAccumulator other) {
        this.rowCount += other.rowCount;
        this.totalLobBytes += other.totalLobBytes;
        addLimbs(other.s0, other.s1, other.s2, other.s3);
        int count = Math.min(this.colAccs.length, other.colAccs.length);
        for (int i = 0; i < count; i++) {
            this.colAccs[i].merge(other.colAccs[i]);
        }
    }

    public long rowCount() { return rowCount; }
    public long totalLobBytes() { return totalLobBytes; }

    public String multisetHashHex() {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.putLong(s0).putLong(s1).putLong(s2).putLong(s3);
        return HexFormat.of().formatHex(buf.array());
    }

    public TableChecksum toTableChecksum(String tableId, String tableName) {
        Map<String, ColumnAggregate> aggregates = new LinkedHashMap<>();
        for (int i = 0; i < columnNames.size(); i++) {
            ColAcc acc = colAccs[i];
            aggregates.put(columnNames.get(i), new ColumnAggregate(
                    acc.nonNullCount,
                    acc.numericSum == null ? null : CanonicalCell.normalizeBigDecimal(acc.numericSum),
                    acc.minValue,
                    acc.maxValue,
                    acc.lobByteSum
            ));
        }
        return new TableChecksum(tableId, tableName, rowCount, totalLobBytes, multisetHashHex(), temporalFspCap, aggregates);
    }

    private void addHashMod256(byte[] digest32) {
        ByteBuffer buf = ByteBuffer.wrap(digest32);
        addLimbs(buf.getLong(), buf.getLong(), buf.getLong(), buf.getLong());
    }

    private void addLimbs(long a0, long a1, long a2, long a3) {
        long r3 = this.s3 + a3;
        long c3 = Long.compareUnsigned(r3, this.s3) < 0 ? 1L : 0L;

        long t2 = this.s2 + a2;
        long c2a = Long.compareUnsigned(t2, this.s2) < 0 ? 1L : 0L;
        long r2 = t2 + c3;
        long c2b = Long.compareUnsigned(r2, t2) < 0 ? 1L : 0L;
        long c2 = c2a + c2b;

        long t1 = this.s1 + a1;
        long c1a = Long.compareUnsigned(t1, this.s1) < 0 ? 1L : 0L;
        long r1 = t1 + c2;
        long c1b = Long.compareUnsigned(r1, t1) < 0 ? 1L : 0L;
        long c1 = c1a + c1b;

        long r0 = this.s0 + a0 + c1;

        this.s0 = r0;
        this.s1 = r1;
        this.s2 = r2;
        this.s3 = r3;
    }

    private boolean isJsonColumn(int index) {
        return index >= 0 && index < columnTypes.size() && columnTypes.get(index) == CanonicalTypeKind.JSON;
    }

    /** 规范化 JSON 文本（递归按键字典序重排并去空白），消除 PG JSONB / MySQL JSON / 文本 CLOB 的格式差异。 */
    public static String canonicalizeJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            JsonNode tree = JSON_CANONICAL_MAPPER.readTree(raw);
            return JSON_CANONICAL_MAPPER.writeValueAsString(sortJsonNode(tree));
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static JsonNode sortJsonNode(JsonNode node) {
        if (node == null || !node.isContainerNode()) return node;
        if (node.isObject()) {
            ObjectNode sorted = JSON_CANONICAL_MAPPER.createObjectNode();
            TreeMap<String, JsonNode> ordered = new TreeMap<>();
            node.fields().forEachRemaining(e -> ordered.put(e.getKey(), sortJsonNode(e.getValue())));
            ordered.forEach(sorted::set);
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode arr = JSON_CANONICAL_MAPPER.createArrayNode();
            for (JsonNode item : node) {
                arr.add(sortJsonNode(item));
            }
            return arr;
        }
        return node;
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", e);
        }
    }

    private static final class ColAcc {
        long nonNullCount;
        BigDecimal numericSum;
        String minValue;
        String maxValue;
        long lobByteSum;

        void recordNumeric(BigDecimal val) {
            if (val == null) return;
            numericSum = numericSum == null ? val : numericSum.add(val);
            String norm = CanonicalCell.normalizeBigDecimal(val);
            if (minValue == null || compareNumericString(norm, minValue) < 0) minValue = norm;
            if (maxValue == null || compareNumericString(norm, maxValue) > 0) maxValue = norm;
        }

        void recordMinMax(String val) {
            if (val == null || val.length() > 256) return;
            if (minValue == null || val.compareTo(minValue) < 0) minValue = val;
            if (maxValue == null || val.compareTo(maxValue) > 0) maxValue = val;
        }

        void merge(ColAcc other) {
            this.nonNullCount += other.nonNullCount;
            this.lobByteSum += other.lobByteSum;
            if (other.numericSum != null) {
                this.numericSum = this.numericSum == null ? other.numericSum : this.numericSum.add(other.numericSum);
                if (other.minValue != null && (this.minValue == null || compareNumericString(other.minValue, this.minValue) < 0)) {
                    this.minValue = other.minValue;
                }
                if (other.maxValue != null && (this.maxValue == null || compareNumericString(other.maxValue, this.maxValue) > 0)) {
                    this.maxValue = other.maxValue;
                }
            } else {
                if (other.minValue != null && (this.minValue == null || other.minValue.compareTo(this.minValue) < 0)) {
                    this.minValue = other.minValue;
                }
                if (other.maxValue != null && (this.maxValue == null || other.maxValue.compareTo(this.maxValue) > 0)) {
                    this.maxValue = other.maxValue;
                }
            }
        }

        private static int compareNumericString(String a, String b) {
            try {
                return new BigDecimal(a).compareTo(new BigDecimal(b));
            } catch (NumberFormatException ignored) {
                return a.compareTo(b);
            }
        }
    }
}
