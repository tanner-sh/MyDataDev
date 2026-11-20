package com.example.dbadmin.service.migration.artifact;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 强类型行单元格载体：统一表达在线直迁与 {@code .mddpkg} 制品中的标量值与流式大对象（BLOB / CLOB）。
 *
 * <p>对于大对象（> 阈值），直接持有流式 {@link InputStream}/{@link Reader} 而非整块 {@code byte[]}/{@code String}，
 * 保证 10 GB 高密 LOB 迁移时 JVM 堆内存保持恒定。</p>
 */
public final class CanonicalCell implements AutoCloseable {
    public enum Kind {
        NULL,
        BOOLEAN,
        INT64,
        FLOAT64,
        DECIMAL,
        STRING,
        DATE,
        TIME,
        DATETIME,
        TIMESTAMP_TZ,
        BYTES,
        STREAM_BLOB,
        STREAM_CLOB
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_BASE = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DT_BASE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Kind kind;
    private final Object value;
    private final long knownLength;
    private byte[] lobSha256;
    private long actualLobBytes;
    private Runnable finalizeDigestCallback;
    private boolean consumed;

    private CanonicalCell(Kind kind, Object value, long knownLength) {
        this.kind = kind;
        this.value = value;
        this.knownLength = knownLength;
    }

    public static CanonicalCell ofNull() { return new CanonicalCell(Kind.NULL, null, 0); }
    public static CanonicalCell ofBoolean(boolean v) { return new CanonicalCell(Kind.BOOLEAN, v, 1); }
    public static CanonicalCell ofInt64(long v) { return new CanonicalCell(Kind.INT64, v, 8); }
    public static CanonicalCell ofFloat64(double v) { return new CanonicalCell(Kind.FLOAT64, v, 8); }
    public static CanonicalCell ofDecimal(BigDecimal v) { return v == null ? ofNull() : new CanonicalCell(Kind.DECIMAL, v, v.precision()); }
    public static CanonicalCell ofString(String v) { return v == null ? ofNull() : new CanonicalCell(Kind.STRING, v, v.length()); }
    public static CanonicalCell ofDate(LocalDate v) { return v == null ? ofNull() : new CanonicalCell(Kind.DATE, v, 10); }
    public static CanonicalCell ofTime(LocalTime v) { return v == null ? ofNull() : new CanonicalCell(Kind.TIME, v, 15); }
    public static CanonicalCell ofDateTime(LocalDateTime v) { return v == null ? ofNull() : new CanonicalCell(Kind.DATETIME, v, 26); }
    public static CanonicalCell ofTimestampTz(OffsetDateTime v) { return v == null ? ofNull() : new CanonicalCell(Kind.TIMESTAMP_TZ, v, 32); }
    public static CanonicalCell ofBytes(byte[] v) { return v == null ? ofNull() : new CanonicalCell(Kind.BYTES, v, v.length); }
    public static CanonicalCell ofBlobStream(InputStream in, long knownBytes) {
        return in == null ? ofNull() : new CanonicalCell(Kind.STREAM_BLOB, in, knownBytes);
    }
    public static CanonicalCell ofClobStream(Reader reader, long knownChars) {
        return reader == null ? ofNull() : new CanonicalCell(Kind.STREAM_CLOB, reader, knownChars);
    }

    public Kind kind() { return kind; }
    public boolean isNull() { return kind == Kind.NULL; }
    public boolean isStreamingLob() { return kind == Kind.STREAM_BLOB || kind == Kind.STREAM_CLOB; }
    public long knownLength() { return knownLength; }

    public boolean asBoolean() { return (Boolean) value; }
    public long asInt64() { return (Long) value; }
    public double asFloat64() { return (Double) value; }
    public BigDecimal asDecimal() { return (BigDecimal) value; }
    public String asString() { return (String) value; }
    public LocalDate asDate() { return (LocalDate) value; }
    public LocalTime asTime() { return (LocalTime) value; }
    public LocalDateTime asDateTime() { return (LocalDateTime) value; }
    public OffsetDateTime asTimestampTz() { return (OffsetDateTime) value; }
    public byte[] asBytes() { return (byte[]) value; }

    public InputStream asBlobStream() {
        consumed = true;
        if (kind == Kind.STREAM_BLOB) return (InputStream) value;
        if (kind == Kind.BYTES) return new ByteArrayInputStream((byte[]) value);
        return new ByteArrayInputStream(new byte[0]);
    }

    public Reader asClobStream() {
        consumed = true;
        if (kind == Kind.STREAM_CLOB) return (Reader) value;
        if (kind == Kind.STRING) return new StringReader((String) value);
        return new StringReader("");
    }

    public byte[] lobSha256() {
        if (lobSha256 == null && finalizeDigestCallback != null) {
            finalizeDigestCallback.run();
        }
        return lobSha256;
    }

    public long actualLobBytes() {
        if (lobSha256 == null && finalizeDigestCallback != null) {
            finalizeDigestCallback.run();
        }
        return actualLobBytes;
    }

    public void registerFinalizeDigest(Runnable callback) {
        this.finalizeDigestCallback = callback;
    }

    public void recordLobDigest(byte[] sha256, long byteCount) {
        this.lobSha256 = sha256;
        this.actualLobBytes = byteCount;
        this.finalizeDigestCallback = null;
    }

    /** 估算内存占用（用于双阈值批处理字节预算）。 */
    public long estimatedMemoryBytes() {
        return switch (kind) {
            case NULL -> 0;
            case BOOLEAN, INT64, FLOAT64 -> 16;
            case DECIMAL, DATE, TIME, DATETIME, TIMESTAMP_TZ -> 48;
            case STRING -> Math.max(16L, ((String) value).length() * 2L);
            case BYTES -> ((byte[]) value).length;
            case STREAM_BLOB, STREAM_CLOB -> knownLength > 0 ? knownLength : 1024L * 1024L;
        };
    }

    /**
     * 规范化文本表示（专用于跨库一致性 L3 哈希与 L2 聚合）：
     * 消除合法跨库格式差异（如 {@code 1.00} vs {@code 1}、{@code -0.0} vs {@code 0.0}、末尾小数秒补零）。
     */
    public String canonicalHashText(int temporalFspCap, String timezonePolicy) {
        return switch (kind) {
            case NULL -> null;
            case BOOLEAN -> asBoolean() ? "1" : "0";
            case INT64 -> Long.toString(asInt64());
            case FLOAT64 -> {
                double d = asFloat64();
                if (d == 0.0d) d = 0.0d; // 消除 -0.0
                yield Double.toString(d);
            }
            case DECIMAL -> normalizeBigDecimal(asDecimal());
            case STRING -> asString();
            case DATE -> DATE_FMT.format(asDate());
            case TIME -> formatTime(asTime(), temporalFspCap);
            case DATETIME -> formatDateTime(asDateTime(), temporalFspCap);
            case TIMESTAMP_TZ -> {
                OffsetDateTime odt = asTimestampTz();
                LocalDateTime normalized = "LOCAL".equalsIgnoreCase(timezonePolicy)
                        ? odt.toLocalDateTime()
                        : odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                yield formatDateTime(normalized, temporalFspCap);
            }
            case BYTES -> null; // 二进制与流式 LOB 走直接字节/SHA-256 哈希，不经字符串
            case STREAM_BLOB, STREAM_CLOB -> null;
        };
    }

    public static String normalizeBigDecimal(BigDecimal bd) {
        if (bd == null) return null;
        if (bd.compareTo(BigDecimal.ZERO) == 0) return "0";
        return bd.stripTrailingZeros().toPlainString();
    }

    public static String formatTime(LocalTime time, int fspCap) {
        String base = TIME_BASE.format(time);
        String frac = formatFraction(time.getNano(), fspCap);
        return frac.isEmpty() ? base : base + "." + frac;
    }

    public static String formatDateTime(LocalDateTime dt, int fspCap) {
        String base = DT_BASE.format(dt);
        String frac = formatFraction(dt.getNano(), fspCap);
        return frac.isEmpty() ? base : base + "." + frac;
    }

    private static String formatFraction(int nanos, int fspCap) {
        int cap = Math.min(Math.max(fspCap, 0), 9);
        if (cap == 0 || nanos == 0) return "";
        String full = String.format("%09d", nanos).substring(0, cap);
        int end = full.length();
        while (end > 0 && full.charAt(end - 1) == '0') end--;
        return full.substring(0, end);
    }

    public byte[] utf8BytesForHash(int temporalFspCap, String timezonePolicy) {
        String text = canonicalHashText(temporalFspCap, timezonePolicy);
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        if (consumed) return;
        if (kind == Kind.STREAM_BLOB && value instanceof InputStream in) {
            in.close();
        } else if (kind == Kind.STREAM_CLOB && value instanceof Reader r) {
            r.close();
        }
    }
}
