package com.example.dbadmin.service.migration.transfer;

import com.example.dbadmin.service.migration.artifact.CanonicalCell;
import com.example.dbadmin.service.migration.mapping.TypeMappingRegistry.MappedColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalColumn;
import com.example.dbadmin.service.migration.model.CanonicalTypeKind;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import com.example.dbadmin.service.migration.verify.RowDigestAccumulator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * 11 种数据库之间的强类型 JDBC 读/写值桥接器。
 *
 * <p>解决的关键驱动陷阱：
 * <ul>
 *   <li>Oracle {@code DATE} 强制走 {@code getTimestamp()} 保留时分秒；</li>
 *   <li>Oracle {@code BLOB}/{@code CLOB} 大于 4KB 时统一走 {@code setBinaryStream}/{@code setCharacterStream} 避开 {@code ORA-01461}；</li>
 *   <li>PostgreSQL {@code JSONB}/{@code XML}/{@code UUID}/{@code INTERVAL} 走 {@code Types.OTHER} 自动转换，并守卫 UTF-8 {@code \0} 字节；</li>
 *   <li>SQLite 无 {@code setBinaryStream}/{@code setCharacterStream} 且定点小数按规范化文本保存；</li>
 *   <li>在线直迁大 LOB 时通过 {@link DigestingInputStream}/{@link DigestingReader} 边写目标库边算 SHA-256，零二次读盘。</li>
 * </ul></p>
 */
public final class TypedValueBridge {
    private static final int INLINE_BLOB_THRESHOLD = 64 * 1024;
    private static final int INLINE_CLOB_THRESHOLD = 64 * 1024;

    private TypedValueBridge() {
    }

    /** 从源端 {@link ResultSet} 读取一列并转为强类型 {@link CanonicalCell}（默认 UTC 时区策略）。 */
    public static CanonicalCell readCell(
            ResultSet rs,
            int jdbcIndex,
            CanonicalColumn column,
            DbTargetDialect sourceDialect
    ) throws Exception {
        return readCell(rs, jdbcIndex, column, sourceDialect, "UTC");
    }

    /** 从源端 {@link ResultSet} 读取一列并转为强类型 {@link CanonicalCell}。 */
    public static CanonicalCell readCell(
            ResultSet rs,
            int jdbcIndex,
            CanonicalColumn column,
            DbTargetDialect sourceDialect,
            String timezonePolicy
    ) throws Exception {
        CanonicalTypeKind kind = column.canonicalType();
        if (kind == CanonicalTypeKind.BLOB || kind == CanonicalTypeKind.BINARY || kind == CanonicalTypeKind.VARBINARY) {
            return readBinaryCell(rs, jdbcIndex, sourceDialect);
        }
        if (kind == CanonicalTypeKind.CLOB || kind == CanonicalTypeKind.TEXT) {
            return readClobOrTextCell(rs, jdbcIndex, sourceDialect);
        }
        if (kind == CanonicalTypeKind.DATETIME) {
            return readDateTimeCell(rs, jdbcIndex, sourceDialect);
        }
        if (kind == CanonicalTypeKind.DATE) {
            return readDateCell(rs, jdbcIndex, sourceDialect);
        }
        if (kind == CanonicalTypeKind.TIME) {
            return readTimeCell(rs, jdbcIndex, sourceDialect);
        }
        if (kind == CanonicalTypeKind.TIMESTAMP_TZ) {
            return readTimestampTzCell(rs, jdbcIndex, sourceDialect, timezonePolicy);
        }
        if (kind == CanonicalTypeKind.DECIMAL || kind == CanonicalTypeKind.UINT64) {
            if (sourceDialect == DbTargetDialect.SQLITE) {
                String text = rs.getString(jdbcIndex);
                if (text == null || rs.wasNull()) return CanonicalCell.ofNull();
                try {
                    return CanonicalCell.ofDecimal(new BigDecimal(text.trim()));
                } catch (NumberFormatException ignored) {
                    return CanonicalCell.ofString(text);
                }
            }
            BigDecimal bd = rs.getBigDecimal(jdbcIndex);
            return (bd == null || rs.wasNull()) ? CanonicalCell.ofNull() : CanonicalCell.ofDecimal(bd);
        }
        if (kind == CanonicalTypeKind.BOOLEAN) {
            Object raw = rs.getObject(jdbcIndex);
            if (raw == null || rs.wasNull()) return CanonicalCell.ofNull();
            if (raw instanceof Boolean b) return CanonicalCell.ofBoolean(b);
            if (raw instanceof Number n) return CanonicalCell.ofBoolean(n.intValue() != 0);
            String s = raw.toString().trim().toLowerCase(Locale.ROOT);
            return CanonicalCell.ofBoolean("1".equals(s) || "true".equals(s) || "t".equals(s) || "y".equals(s));
        }
        if (kind.isInteger()) {
            Object raw = rs.getObject(jdbcIndex);
            if (raw == null || rs.wasNull()) return CanonicalCell.ofNull();
            if (raw instanceof BigDecimal bd) {
                if (bd.scale() <= 0 && bd.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0 && bd.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0) {
                    return CanonicalCell.ofInt64(bd.longValue());
                }
                return CanonicalCell.ofDecimal(bd);
            }
            if (raw instanceof Number n) return CanonicalCell.ofInt64(n.longValue());
            return CanonicalCell.ofInt64(Long.parseLong(raw.toString().trim()));
        }
        if (kind == CanonicalTypeKind.FLOAT32 || kind == CanonicalTypeKind.FLOAT64) {
            Object raw = rs.getObject(jdbcIndex);
            if (raw == null || rs.wasNull()) return CanonicalCell.ofNull();
            if (raw instanceof Number n) return CanonicalCell.ofFloat64(n.doubleValue());
            return CanonicalCell.ofFloat64(Double.parseDouble(raw.toString().trim()));
        }

        // CHAR / VARCHAR / JSON / XML / UUID / INTERVAL / RAW_UNSUPPORTED
        Object raw = rs.getObject(jdbcIndex);
        if (raw == null || rs.wasNull()) return CanonicalCell.ofNull();
        if (raw instanceof Clob clob) {
            return fromClob(clob);
        }
        if (raw instanceof byte[] bytes) {
            return CanonicalCell.ofString(new String(bytes, StandardCharsets.UTF_8));
        }
        return CanonicalCell.ofString(raw.toString());
    }

    /** 将 {@link CanonicalCell} 强类型绑定到目标库 {@link PreparedStatement}。 */
    public static void bindCell(
            PreparedStatement ps,
            int paramIndex,
            CanonicalCell cell,
            MappedColumn targetCol,
            DbTargetDialect targetDialect,
            MigrationOptions options,
            boolean computeDigestOnStream
    ) throws Exception {
        if (cell == null || cell.isNull()) {
            bindNull(ps, paramIndex, targetCol, targetDialect);
            return;
        }
        String targetTypeUpper = targetCol.targetSqlType() == null ? "" : targetCol.targetSqlType().toUpperCase(Locale.ROOT);
        int fsp = targetCol.effectiveTemporalFsp();

        switch (cell.kind()) {
            case NULL -> bindNull(ps, paramIndex, targetCol, targetDialect);
            case BOOLEAN -> {
                boolean b = cell.asBoolean();
                if (targetDialect == DbTargetDialect.POSTGRESQL || targetDialect == DbTargetDialect.H2 || targetDialect == DbTargetDialect.CLICKHOUSE) {
                    ps.setBoolean(paramIndex, b);
                } else {
                    ps.setInt(paramIndex, b ? 1 : 0);
                }
            }
            case INT64 -> ps.setLong(paramIndex, cell.asInt64());
            case FLOAT64 -> ps.setDouble(paramIndex, cell.asFloat64());
            case DECIMAL -> {
                if (targetDialect == DbTargetDialect.SQLITE) {
                    ps.setString(paramIndex, CanonicalCell.normalizeBigDecimal(cell.asDecimal()));
                } else {
                    ps.setBigDecimal(paramIndex, cell.asDecimal());
                }
            }
            case DATE -> {
                if (targetDialect == DbTargetDialect.SQLITE) {
                    ps.setString(paramIndex, cell.asDate().toString());
                } else {
                    try {
                        ps.setObject(paramIndex, cell.asDate());
                    } catch (SQLException | AbstractMethodError ignored) {
                        ps.setDate(paramIndex, Date.valueOf(cell.asDate()));
                    }
                }
            }
            case TIME -> {
                LocalTime t = truncateTime(cell.asTime(), fsp);
                if (targetDialect.isOracleFamily() || targetDialect == DbTargetDialect.SQLITE || targetDialect == DbTargetDialect.CLICKHOUSE) {
                    ps.setString(paramIndex, CanonicalCell.formatTime(t, fsp));
                } else if (fsp > 0) {
                    try {
                        ps.setObject(paramIndex, t);
                    } catch (SQLException | AbstractMethodError ignored) {
                        ps.setTime(paramIndex, Time.valueOf(t));
                    }
                } else {
                    ps.setTime(paramIndex, Time.valueOf(t));
                }
            }
            case DATETIME -> {
                LocalDateTime dt = truncateDateTime(cell.asDateTime(), fsp);
                if (targetDialect == DbTargetDialect.SQLITE) {
                    ps.setString(paramIndex, CanonicalCell.formatDateTime(dt, fsp));
                } else {
                    // 优先用 JDBC 4.2 LocalDateTime 保留墙钟时间，避免 MySQL 驱动在 JVM 与服务器时区不同步时漂移
                    try {
                        ps.setObject(paramIndex, dt);
                    } catch (SQLException | AbstractMethodError ignored) {
                        ps.setTimestamp(paramIndex, Timestamp.valueOf(dt));
                    }
                }
            }
            case TIMESTAMP_TZ -> {
                OffsetDateTime odt = truncateOffsetDateTime(cell.asTimestampTz(), fsp);
                if (targetDialect == DbTargetDialect.SQLITE) {
                    LocalDateTime ldt = "LOCAL".equalsIgnoreCase(options.timezonePolicy())
                            ? odt.toLocalDateTime()
                            : odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                    ps.setString(paramIndex, CanonicalCell.formatDateTime(ldt, fsp));
                } else if (isNonTimezoneStorageDialect(targetDialect)) {
                    LocalDateTime ldt = "LOCAL".equalsIgnoreCase(options.timezonePolicy())
                            ? odt.toLocalDateTime()
                            : odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                    try {
                        ps.setObject(paramIndex, ldt);
                    } catch (SQLException | AbstractMethodError ignored) {
                        ps.setTimestamp(paramIndex, Timestamp.valueOf(ldt));
                    }
                } else {
                    try {
                        ps.setObject(paramIndex, odt);
                    } catch (SQLException | AbstractMethodError ignored) {
                        ps.setTimestamp(paramIndex, Timestamp.from(odt.toInstant()));
                    }
                }
            }
            case STRING -> bindStringValue(ps, paramIndex, cell.asString(), targetCol, targetTypeUpper, targetDialect, options);
            case BYTES -> {
                byte[] bytes = cell.asBytes();
                if (targetDialect.isOracleFamily() && bytes.length > 2000) {
                    // 避开 ojdbc setBytes > 4000B 的 ORA-01461 限制
                    ps.setBinaryStream(paramIndex, new ByteArrayInputStream(bytes), bytes.length);
                } else {
                    ps.setBytes(paramIndex, bytes);
                }
            }
            case STREAM_BLOB -> {
                InputStream raw = cell.asBlobStream();
                InputStream stream = computeDigestOnStream ? new DigestingInputStream(raw, cell) : raw;
                if (targetDialect == DbTargetDialect.SQLITE || targetDialect == DbTargetDialect.CLICKHOUSE) {
                    byte[] all = readAllBytes(stream);
                    ps.setBytes(paramIndex, all);
                } else if (cell.knownLength() >= 0) {
                    ps.setBinaryStream(paramIndex, stream, cell.knownLength());
                } else {
                    ps.setBinaryStream(paramIndex, stream);
                }
            }
            case STREAM_CLOB -> {
                Reader raw = cell.asClobStream();
                Reader reader = computeDigestOnStream ? new DigestingReader(raw, cell) : raw;
                if (targetDialect == DbTargetDialect.SQLITE || targetDialect == DbTargetDialect.CLICKHOUSE) {
                    String all = readAllChars(reader);
                    ps.setString(paramIndex, all);
                } else if (targetDialect == DbTargetDialect.POSTGRESQL && "STRIP".equalsIgnoreCase(options.pgNulBytePolicy())) {
                    Reader sanitized = new NulStrippingReader(reader);
                    ps.setCharacterStream(paramIndex, sanitized);
                } else if (cell.knownLength() >= 0) {
                    ps.setCharacterStream(paramIndex, reader, cell.knownLength());
                } else {
                    ps.setCharacterStream(paramIndex, reader);
                }
            }
        }
    }

    private static void bindStringValue(
            PreparedStatement ps,
            int paramIndex,
            String value,
            MappedColumn targetCol,
            String targetTypeUpper,
            DbTargetDialect targetDialect,
            MigrationOptions options
    ) throws SQLException {
        String text = value;
        if (targetDialect == DbTargetDialect.POSTGRESQL && text.indexOf('\0') >= 0) {
            if ("STRIP".equalsIgnoreCase(options.pgNulBytePolicy())) {
                text = text.replace("\0", "");
            } else {
                throw new IllegalArgumentException("目标列 " + targetCol.targetColumnName()
                        + " 遇到 NUL(\\0) 字节，PostgreSQL UTF-8 拒绝写入；可将「PG NUL 字节策略」调整为 STRIP 后重试。");
            }
        }
        if (targetDialect.isOracleFamily() && text.isEmpty() && !targetCol.nullable()) {
            if ("SPACE_PLACEHOLDER".equalsIgnoreCase(options.emptyStringToOraclePolicy())) {
                text = " ";
            } else if ("FAIL".equalsIgnoreCase(options.emptyStringToOraclePolicy())) {
                throw new IllegalArgumentException("目标 Oracle 非空列 " + targetCol.targetColumnName()
                        + " 收到空字符串 ''（Oracle 中空串等价于 NULL）；请将策略设为 ALLOW_NULL 或 SPACE_PLACEHOLDER。");
            }
        }

        if (targetDialect == DbTargetDialect.POSTGRESQL) {
            if (targetTypeUpper.equals("UUID")) {
                ps.setObject(paramIndex, UUID.fromString(text.trim()));
                return;
            }
            if (targetTypeUpper.equals("JSONB") || targetTypeUpper.equals("JSON")
                    || targetTypeUpper.equals("XML") || targetTypeUpper.equals("INTERVAL")) {
                ps.setObject(paramIndex, text, Types.OTHER);
                return;
            }
        }
        if (targetDialect == DbTargetDialect.H2 && targetTypeUpper.equals("UUID")) {
            ps.setObject(paramIndex, UUID.fromString(text.trim()));
            return;
        }
        if (targetDialect.isOracleFamily() && text.length() > 1000 && targetTypeUpper.contains("CLOB")) {
            ps.setCharacterStream(paramIndex, new StringReader(text), text.length());
            return;
        }
        ps.setString(paramIndex, text);
    }

    private static void bindNull(PreparedStatement ps, int paramIndex, MappedColumn targetCol, DbTargetDialect targetDialect) throws SQLException {
        if (targetDialect == DbTargetDialect.CLICKHOUSE || targetDialect == DbTargetDialect.SQLITE) {
            ps.setObject(paramIndex, null);
            return;
        }
        int sqlType = switch (targetCol.source().canonicalType()) {
            case BOOLEAN -> targetDialect == DbTargetDialect.POSTGRESQL || targetDialect == DbTargetDialect.H2 ? Types.BOOLEAN : Types.INTEGER;
            case INT8, INT16, INT32 -> Types.INTEGER;
            case INT64 -> Types.BIGINT;
            case UINT64, DECIMAL -> Types.DECIMAL;
            case FLOAT32 -> Types.REAL;
            case FLOAT64 -> Types.DOUBLE;
            case BINARY, VARBINARY -> Types.VARBINARY;
            case BLOB -> targetDialect == DbTargetDialect.POSTGRESQL ? Types.BINARY : Types.BLOB;
            case CLOB -> targetDialect == DbTargetDialect.POSTGRESQL ? Types.VARCHAR : Types.CLOB;
            case DATE -> Types.DATE;
            case TIME -> targetDialect.isOracleFamily() ? Types.VARCHAR : Types.TIME;
            case DATETIME -> Types.TIMESTAMP;
            case TIMESTAMP_TZ -> targetDialect.isMySqlFamily() ? Types.TIMESTAMP : Types.TIMESTAMP_WITH_TIMEZONE;
            case JSON, XML, UUID, INTERVAL -> targetDialect == DbTargetDialect.POSTGRESQL ? Types.OTHER : Types.VARCHAR;
            default -> Types.VARCHAR;
        };
        try {
            ps.setNull(paramIndex, sqlType);
        } catch (SQLException ignored) {
            ps.setNull(paramIndex, Types.NULL);
        }
    }

    private static CanonicalCell readBinaryCell(ResultSet rs, int idx, DbTargetDialect sourceDialect) throws Exception {
        if (sourceDialect == DbTargetDialect.SQLITE || sourceDialect == DbTargetDialect.POSTGRESQL || sourceDialect == DbTargetDialect.CLICKHOUSE) {
            byte[] bytes = rs.getBytes(idx);
            if (bytes == null || rs.wasNull()) return CanonicalCell.ofNull();
            if (bytes.length <= INLINE_BLOB_THRESHOLD) {
                CanonicalCell c = CanonicalCell.ofBytes(bytes);
                c.recordLobDigest(RowDigestAccumulator.sha256().digest(bytes), bytes.length);
                return c;
            }
            return CanonicalCell.ofBlobStream(new ByteArrayInputStream(bytes), bytes.length);
        }
        Object obj = rs.getObject(idx);
        if (obj == null || rs.wasNull()) return CanonicalCell.ofNull();
        if (obj instanceof Blob blob) {
            long len = -1L;
            try { len = blob.length(); } catch (Exception ignored) { }
            if (len >= 0 && len <= INLINE_BLOB_THRESHOLD) {
                byte[] bytes = len == 0 ? new byte[0] : blob.getBytes(1, (int) len);
                try { blob.free(); } catch (Exception ignored) { }
                CanonicalCell c = CanonicalCell.ofBytes(bytes);
                c.recordLobDigest(RowDigestAccumulator.sha256().digest(bytes), bytes.length);
                return c;
            }
            return CanonicalCell.ofBlobStream(blob.getBinaryStream(), len);
        }
        if (obj instanceof byte[] bytes) {
            if (bytes.length <= INLINE_BLOB_THRESHOLD) {
                CanonicalCell c = CanonicalCell.ofBytes(bytes);
                c.recordLobDigest(RowDigestAccumulator.sha256().digest(bytes), bytes.length);
                return c;
            }
            return CanonicalCell.ofBlobStream(new ByteArrayInputStream(bytes), bytes.length);
        }
        InputStream in = rs.getBinaryStream(idx);
        return in == null ? CanonicalCell.ofNull() : CanonicalCell.ofBlobStream(in, -1L);
    }

    private static CanonicalCell readClobOrTextCell(ResultSet rs, int idx, DbTargetDialect sourceDialect) throws Exception {
        if (sourceDialect == DbTargetDialect.SQLITE || sourceDialect == DbTargetDialect.POSTGRESQL || sourceDialect == DbTargetDialect.CLICKHOUSE) {
            String s = rs.getString(idx);
            if (s == null || rs.wasNull()) return CanonicalCell.ofNull();
            return CanonicalCell.ofString(s);
        }
        Object obj = rs.getObject(idx);
        if (obj == null || rs.wasNull()) return CanonicalCell.ofNull();
        if (obj instanceof Clob clob) {
            return fromClob(clob);
        }
        return CanonicalCell.ofString(obj.toString());
    }

    private static CanonicalCell fromClob(Clob clob) throws SQLException {
        long len = -1L;
        try { len = clob.length(); } catch (Exception ignored) { }
        if (len >= 0 && len <= INLINE_CLOB_THRESHOLD) {
            String s = len == 0 ? "" : clob.getSubString(1, (int) len);
            try { clob.free(); } catch (Exception ignored) { }
            return CanonicalCell.ofString(s);
        }
        return CanonicalCell.ofClobStream(clob.getCharacterStream(), len);
    }

    private static CanonicalCell readDateCell(ResultSet rs, int idx, DbTargetDialect sourceDialect) throws Exception {
        if (sourceDialect == DbTargetDialect.SQLITE) {
            String s = rs.getString(idx);
            if (s == null || rs.wasNull()) return CanonicalCell.ofNull();
            String trimmed = s.trim();
            return CanonicalCell.ofDate(LocalDate.parse(trimmed.length() >= 10 ? trimmed.substring(0, 10) : trimmed));
        }
        try {
            LocalDate ld = rs.getObject(idx, LocalDate.class);
            if (ld != null && !rs.wasNull()) return CanonicalCell.ofDate(ld);
            if (rs.wasNull()) return CanonicalCell.ofNull();
        } catch (Exception | AbstractMethodError ignored) {
        }
        Date d = rs.getDate(idx);
        return (d == null || rs.wasNull()) ? CanonicalCell.ofNull() : CanonicalCell.ofDate(d.toLocalDate());
    }

    private static CanonicalCell readTimeCell(ResultSet rs, int idx, DbTargetDialect sourceDialect) throws Exception {
        if (sourceDialect == DbTargetDialect.SQLITE || sourceDialect.isOracleFamily()) {
            String s = rs.getString(idx);
            if (s == null || rs.wasNull()) return CanonicalCell.ofNull();
            return CanonicalCell.ofTime(LocalTime.parse(s.trim()));
        }
        try {
            LocalTime lt = rs.getObject(idx, LocalTime.class);
            if (lt != null && !rs.wasNull()) return CanonicalCell.ofTime(lt);
            if (rs.wasNull()) return CanonicalCell.ofNull();
        } catch (Exception | AbstractMethodError ignored) {
        }
        Time t = rs.getTime(idx);
        return (t == null || rs.wasNull()) ? CanonicalCell.ofNull() : CanonicalCell.ofTime(t.toLocalTime());
    }

    private static CanonicalCell readDateTimeCell(ResultSet rs, int idx, DbTargetDialect sourceDialect) throws Exception {
        if (sourceDialect == DbTargetDialect.SQLITE) {
            String s = rs.getString(idx);
            if (s == null || rs.wasNull()) return CanonicalCell.ofNull();
            String norm = s.trim().replace(' ', 'T');
            if (norm.length() == 10) norm = norm + "T00:00:00";
            return CanonicalCell.ofDateTime(LocalDateTime.parse(norm));
        }
        try {
            LocalDateTime ldt = rs.getObject(idx, LocalDateTime.class);
            if (ldt != null && !rs.wasNull()) return CanonicalCell.ofDateTime(ldt);
            if (rs.wasNull()) return CanonicalCell.ofNull();
        } catch (Exception | AbstractMethodError ignored) {
        }
        Timestamp ts = rs.getTimestamp(idx);
        return (ts == null || rs.wasNull()) ? CanonicalCell.ofNull() : CanonicalCell.ofDateTime(ts.toLocalDateTime());
    }

    private static CanonicalCell readTimestampTzCell(
            ResultSet rs,
            int idx,
            DbTargetDialect sourceDialect,
            String timezonePolicy
    ) throws Exception {
        // MySQL/MariaDB/DM8/ClickHouse/SQLite 等目标列不含原生时区元数据，写入时已按 timezonePolicy 转为墙钟 DATETIME，
        // 读回校验时必须按同策略直接解释墙钟 LocalDateTime，杜绝 Timestamp.toInstant() 触发二次时区偏移。
        if (isNonTimezoneStorageDialect(sourceDialect)) {
            CanonicalCell dt = readDateTimeCell(rs, idx, sourceDialect);
            if (dt.isNull()) return CanonicalCell.ofNull();
            LocalDateTime ldt = dt.asDateTime();
            OffsetDateTime odt = "LOCAL".equalsIgnoreCase(timezonePolicy)
                    ? ldt.atZone(ZoneId.systemDefault()).toOffsetDateTime()
                    : ldt.atOffset(ZoneOffset.UTC);
            return CanonicalCell.ofTimestampTz(odt);
        }
        try {
            OffsetDateTime odt = rs.getObject(idx, OffsetDateTime.class);
            if (odt != null && !rs.wasNull()) return CanonicalCell.ofTimestampTz(odt);
            if (rs.wasNull()) return CanonicalCell.ofNull();
        } catch (Exception | AbstractMethodError ignored) {
        }
        try {
            ZonedDateTime zdt = rs.getObject(idx, ZonedDateTime.class);
            if (zdt != null && !rs.wasNull()) return CanonicalCell.ofTimestampTz(zdt.toOffsetDateTime());
        } catch (Exception | AbstractMethodError ignored) {
        }
        Timestamp ts = rs.getTimestamp(idx);
        if (ts == null || rs.wasNull()) return CanonicalCell.ofNull();
        return CanonicalCell.ofTimestampTz(ts.toInstant().atOffset(ZoneOffset.UTC));
    }

    private static boolean isNonTimezoneStorageDialect(DbTargetDialect dialect) {
        return dialect != null && (dialect.isMySqlFamily()
                || dialect == DbTargetDialect.DAMENG
                || dialect == DbTargetDialect.CLICKHOUSE
                || dialect == DbTargetDialect.SQLITE);
    }

    private static LocalTime truncateTime(LocalTime t, int fsp) {
        int clamped = Math.min(Math.max(fsp, 0), 9);
        int div = (int) Math.pow(10, 9 - clamped);
        return t.withNano((t.getNano() / div) * div);
    }

    private static LocalDateTime truncateDateTime(LocalDateTime dt, int fsp) {
        int clamped = Math.min(Math.max(fsp, 0), 9);
        int div = (int) Math.pow(10, 9 - clamped);
        return dt.withNano((dt.getNano() / div) * div);
    }

    private static OffsetDateTime truncateOffsetDateTime(OffsetDateTime odt, int fsp) {
        int clamped = Math.min(Math.max(fsp, 0), 9);
        int div = (int) Math.pow(10, 9 - clamped);
        return odt.withNano((odt.getNano() / div) * div);
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String readAllChars(Reader reader) throws IOException {
        try (reader) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[16 * 1024];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                if (n > 0) sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    /** 在线直迁大 BLOB 流式读取的同时零拷贝计算 SHA-256 与精确字节数。 */
    private static final class DigestingInputStream extends FilterInputStream {
        private final CanonicalCell cell;
        private final MessageDigest md = RowDigestAccumulator.sha256();
        private long count;
        private boolean finalized;

        DigestingInputStream(InputStream in, CanonicalCell cell) {
            super(in);
            this.cell = cell;
            cell.registerFinalizeDigest(this::finish);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                md.update((byte) b);
                count++;
                if (cell.knownLength() >= 0 && count >= cell.knownLength()) finish();
            } else {
                finish();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) {
                md.update(b, off, r);
                count += r;
                if (cell.knownLength() >= 0 && count >= cell.knownLength()) finish();
            } else if (r < 0) {
                finish();
            }
            return r;
        }

        @Override
        public void close() throws IOException {
            finish();
            super.close();
        }

        private void finish() {
            if (!finalized) {
                finalized = true;
                cell.recordLobDigest(md.digest(), count);
            }
        }
    }

    /** 在线直迁大 CLOB 流式读取的同时零拷贝计算 UTF-8 SHA-256 与字节数。 */
    private static final class DigestingReader extends FilterReader {
        private final CanonicalCell cell;
        private final MessageDigest md = RowDigestAccumulator.sha256();
        private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private final ByteBuffer bbuf = ByteBuffer.allocate(64 * 1024);
        private long byteCount;
        private boolean finalized;

        DigestingReader(Reader in, CanonicalCell cell) {
            super(in);
            this.cell = cell;
            cell.registerFinalizeDigest(this::finish);
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            int r = super.read(cbuf, off, len);
            if (r > 0) {
                encodeAndDigest(CharBuffer.wrap(cbuf, off, r), false);
            } else if (r < 0) {
                finish();
            }
            return r;
        }

        @Override
        public void close() throws IOException {
            finish();
            super.close();
        }

        private void encodeAndDigest(CharBuffer inBuf, boolean endOfInput) {
            while (inBuf.hasRemaining() || endOfInput) {
                bbuf.clear();
                var cr = encoder.encode(inBuf, bbuf, endOfInput);
                bbuf.flip();
                if (bbuf.hasRemaining()) {
                    int n = bbuf.remaining();
                    md.update(bbuf.array(), 0, n);
                    byteCount += n;
                }
                if (!cr.isOverflow()) break;
            }
        }

        private void finish() {
            if (!finalized) {
                finalized = true;
                encodeAndDigest(CharBuffer.allocate(0), true);
                bbuf.clear();
                encoder.flush(bbuf);
                bbuf.flip();
                if (bbuf.hasRemaining()) {
                    int n = bbuf.remaining();
                    md.update(bbuf.array(), 0, n);
                    byteCount += n;
                }
                cell.recordLobDigest(md.digest(), byteCount);
            }
        }
    }

    private static final class NulStrippingReader extends FilterReader {
        NulStrippingReader(Reader in) { super(in); }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            int r = super.read(cbuf, off, len);
            if (r <= 0) return r;
            int out = off;
            for (int i = off; i < off + r; i++) {
                if (cbuf[i] != '\0') cbuf[out++] = cbuf[i];
            }
            return out - off;
        }
    }
}
