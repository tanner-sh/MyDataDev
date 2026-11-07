package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 表数据编辑与浏览共用的值处理：界面传来的文本怎么按列类型变成 JDBC 参数、怎么绑定、
 * 以及预览 SQL 里参数怎么显示成字面量。
 *
 * <p>从 {@link DataEditService} 拆出来。规则只有一份：筛选、游标、行定位与提交走的都是这里的
 * {@link #bind}，同一个字符串在哪条路上都解释成同一个值。</p>
 */
final class TableValues {
    private static final int MAX_PREVIEW_PARAMETER_CHARS = 2_000;
    private static final int MAX_PREVIEW_SQL_CHARS = 10_000;

    static boolean textJdbcType(int jdbcType) {
        return Set.of(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB).contains(jdbcType);
    }

    static ColumnDescriptor descriptor(Map<String, ColumnDescriptor> descriptors, String column) {
        ColumnDescriptor exact = descriptors.get(column);
        if (exact != null) return exact;
        List<ColumnDescriptor> folded = descriptors.values().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(column))
                .toList();
        if (folded.size() == 1) return folded.get(0);
        if (folded.size() > 1) throw new IllegalArgumentException("字段名大小写不明确，请使用精确名称：" + column);
        throw new IllegalArgumentException("字段不存在：" + column);
    }

    /**
     * 空字符串能不能落进这个类型的列。
     *
     * <p>字符列上空串是一个合法的值；到了数字、日期、时间戳列上它什么都不是 —— 而
     * `Integer.valueOf("")`、`Timestamp.valueOf("")` 抛出来的是一句英文驱动文案，用户看了
     * 不知道该怎么办。这里提前拦住并把话说清楚：想清空就去设 NULL。</p>
     */
    private static boolean acceptsEmptyString(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.OTHER -> true;
            default -> false;
        };
    }

    static Object decodeDatabaseValue(String value, ColumnDescriptor column) {
        if (value == null) return null;
        String columnLabel = column.name() == null || column.name().isBlank()
                ? "该字段" : "字段 " + column.name();
        if (value.isEmpty() && !acceptsEmptyString(column.jdbcType())) {
            throw new IllegalArgumentException(columnLabel + "（" + column.typeName()
                    + "）不接受空字符串。要清空这个值，请在单元格上点右键选择「设为 NULL」。");
        }
        return switch (column.jdbcType()) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Integer.valueOf(value);
            case Types.BIGINT -> new BigDecimal(value);
            case Types.NUMERIC, Types.DECIMAL -> new BigDecimal(value);
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> Double.valueOf(value);
            case Types.BOOLEAN, Types.BIT -> parseBoolean(value);
            case Types.DATE -> Date.valueOf(value.substring(0, Math.min(value.length(), 10)));
            case Types.TIME -> Time.valueOf(value.length() >= 8 ? value.substring(0, 8) : value);
            /*
              这两支原来直接 parse，抛的是 DateTimeParseException —— 它不是
              IllegalArgumentException 的子类，于是绕过了 ApiExceptionHandler 那条把非法入参
              转成 400 的分支，被压成一句「服务器内部错误，请稍后重试」。同一个 switch 里其它
              分支（Integer.valueOf、Timestamp.valueOf、Base64、UUID.fromString）恰好抛的都是
              IAE，所以只有带时区的这两个类型坏掉了。
            */
            case Types.TIME_WITH_TIMEZONE -> parseOffsetTime(value, columnLabel, column);
            case Types.TIMESTAMP -> Timestamp.valueOf(value.replace('T', ' '));
            case Types.TIMESTAMP_WITH_TIMEZONE -> parseOffsetDateTime(value, columnLabel, column);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> Base64.getDecoder().decode(value.replaceFirst("^base64:", ""));
            case Types.OTHER -> {
                String typeName = column.typeName() == null ? "" : column.typeName().toLowerCase(Locale.ROOT);
                yield Set.of("uuid", "uniqueidentifier").contains(typeName) ? UUID.fromString(value) : value;
            }
            default -> value;
        };
    }

    /**
     * 带时区的时间戳。
     *
     * <p>用户手上的写法比 ISO-8601 杂：表格里读出来的往往是 Oracle 的
     * `2025-09-18 14:30:00.0 +08:00`，中间是空格；带区域名的 `…+08:00[Asia/Shanghai]` 也常见。
     * 挨个试完再放弃，报错里给出一个能照抄的例子。</p>
     *
     * <p>刻意不在缺时区时替用户补一个：那等于往库里写进一个错误的时刻，而且从界面上看不出来，
     * 比直接拒绝糟糕得多。</p>
     */
    private static OffsetDateTime parseOffsetDateTime(String value, String columnLabel, ColumnDescriptor column) {
        String normalized = value.trim().replaceFirst("^(\\d{4}-\\d{2}-\\d{2})[ T]", "$1T");
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeException ignored) {
            // 继续试下一种写法。
        }
        try {
            return ZonedDateTime.parse(normalized).toOffsetDateTime();
        } catch (DateTimeException ignored) {
            // 继续试下一种写法。
        }
        try {
            return OffsetDateTime.parse(normalized.replaceAll("\\s+(?=[+-]\\d{2}(:?\\d{2})?$)", ""));
        } catch (DateTimeException failed) {
            throw invalidTemporal(value, columnLabel, column, "2025-09-18 14:30:00+08:00", failed);
        }
    }

    private static OffsetTime parseOffsetTime(String value, String columnLabel, ColumnDescriptor column) {
        try {
            return OffsetTime.parse(value.trim().replaceAll("\\s+(?=[+-]\\d{2}(:?\\d{2})?$)", ""));
        } catch (DateTimeException failed) {
            throw invalidTemporal(value, columnLabel, column, "14:30:00+08:00", failed);
        }
    }

    /** 抛 IAE 而不是 DateTimeException：前者已经有一条走 400 并原样带出消息的分支。 */
    private static IllegalArgumentException invalidTemporal(
            String value, String columnLabel, ColumnDescriptor column, String example, Throwable cause) {
        return new IllegalArgumentException(columnLabel + "（" + column.typeName() + "）的值无法识别：「"
                + value + "」。请使用带时区偏移的写法，例如 " + example + "。", cause);
    }

    static void bind(PreparedStatement statement, List<BoundValue> values) throws Exception {
        for (int index = 0; index < values.size(); index++) {
            BoundValue bound = values.get(index);
            if (bound.value() == null) statement.setNull(index + 1, bound.jdbcType() == Types.OTHER ? Types.NULL : bound.jdbcType());
            else {
                Object value = coerceValue(bound);
                if (bound.jdbcType() == Types.OTHER) statement.setObject(index + 1, value);
                else statement.setObject(index + 1, value, bound.jdbcType());
            }
        }
    }

    private static Boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new IllegalArgumentException("布尔字段仅支持 true、false、1 或 0：" + value);
        };
    }

    private static Object coerceValue(BoundValue bound) {
        Object value = bound.value();
        if (!(value instanceof String text)) return value;
        return decodeDatabaseValue(text, new ColumnDescriptor(bound.column(), bound.jdbcType(), bound.typeName(), true));
    }

    static String previewSql(String sql, List<BoundValue> values, DatabaseDialect dialect) {
        StringBuilder result = new StringBuilder();
        int parameter = 0;
        for (int index = 0; index < sql.length(); index++) {
            char ch = sql.charAt(index);
            if (ch == '?' && parameter < values.size()) {
                Object previewValue = coerceValue(values.get(parameter++));
                if (previewValue instanceof CharSequence text && text.length() > MAX_PREVIEW_PARAMETER_CHARS) {
                    previewValue = "<值预览已截断，共 " + text.length() + " 字符>";
                }
                String literal = dialect.literal(previewValue);
                result.append(literal.length() <= MAX_PREVIEW_PARAMETER_CHARS
                        ? literal
                        : dialect.literal("<值预览已截断>"));
            }
            else result.append(ch);
            if (result.length() > MAX_PREVIEW_SQL_CHARS) {
                return result.substring(0, MAX_PREVIEW_SQL_CHARS) + "… /* 预览已截断 */";
            }
        }
        return result.append(';').toString();
    }

    record ColumnDescriptor(String name, int jdbcType, String typeName, boolean nullable) {
    }

    /** column 只用于错误文案：预览阶段报错时得说得出是哪一列，否则用户只知道「某个字段」。 */
    record BoundValue(Object value, int jdbcType, String typeName, String column) {
    }

    private TableValues() {
    }
}
