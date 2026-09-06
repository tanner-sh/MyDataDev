package com.example.dbadmin.core;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Timestamp;

/**
 * 结果单元格落到 JSON 之前的统一转换规则。
 *
 * <p>同一个值可能从三条路走到前端：SQL 工作台的查询结果（{@code SqlService}）、表数据浏览
 * （{@code DataEditService}）、执行计划（{@code DefaultDialect}）。三条路必须给出同样的文本，
 * 否则同一张表从不同入口看会长得不一样，而这种不一致排查起来很贵 —— 用户只会说「数字对不上」。</p>
 *
 * <p>放在 {@code core} 而不是 {@code service}，是因为执行计划那条路在 {@code DefaultDialect} 里，
 * 而 {@code core} 不依赖 {@code service}。之前的 {@code CellValues} 是 {@code service} 包内可见的，
 * 方言够不着，只能退回 {@code value.toString()} —— 于是 EXPLAIN 结果里的 Timestamp 会多出一个
 * {@code .0} 后缀，别处却没有。那次漂移不是疏忽，是包可见性逼出来的，所以这次落在能被三方共用的层。</p>
 *
 * <p>CLOB 的读取窗口由调用方给：查询结果、表格单元格与执行计划对「先读多少字符回来判断要不要截断」
 * 的取舍本来就不同（计划文本长，表格单元格窄）。那是各条路的策略，不是这里的规则。</p>
 */
public final class CellSerializer {
    /** 二进制列只探长度不读内容，超过这个字节数就不再数下去。 */
    public static final long BINARY_PROBE_BYTES = 1L << 20;

    private CellSerializer() {
    }

    /**
     * 把一个 JDBC 取出来的值转成可以直接序列化进 JSON 的形式。
     *
     * @param maxTextChars 这个单元格最多允许多少字符
     * @param clobWindow   CLOB 先读回多少字符用于判断是否截断
     */
    public static Object serialize(Object value, int maxTextChars, int clobWindow) throws Exception {
        if (value == null) return null;
        if (value instanceof Clob clob) {
            long length = clob.length();
            int visible = (int) Math.min(length, Math.min(clobWindow, Math.max(maxTextChars, 0)));
            String text = visible == 0 ? "" : clob.getSubString(1, visible);
            return length > visible ? truncate(text, "… <CLOB 已截断，共 " + length + " 字符>", maxTextChars) : text;
        }
        if (value instanceof Blob blob) return truncate("<BLOB " + blob.length() + " bytes>", "", maxTextChars);
        if (value instanceof byte[] bytes) return truncate("<BINARY " + bytes.length + " bytes>", "", maxTextChars);
        // 浏览器把 JSON 数字解析成 IEEE-754 双精度。BIGINT 的身份与 DECIMAL 的标度靠转成字符串保住。
        if (value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return truncate(value.toString(), "", maxTextChars);
        }
        if (value instanceof CharSequence text) {
            String string = text.toString();
            return string.length() > maxTextChars
                    ? truncate(string, "… <文本已截断，共 " + string.length() + " 字符>", maxTextChars)
                    : string;
        }
        // NaN 与 ±Infinity 不是合法的 JSON 数字，只能按文本发。
        if (value instanceof Float number && !Float.isFinite(number)) return number.toString();
        if (value instanceof Double number && !Double.isFinite(number)) return number.toString();
        if (value instanceof Number || value instanceof Boolean) return value;
        return truncate(text(value), "", maxTextChars);
    }

    /**
     * 日期时间的文本形式。
     *
     * <p>{@code java.sql.Timestamp.toString()} 在没有小数秒时也会补一位 {@code .0}
     * （{@code 2025-09-18 14:30:00.0}）。那位 0 不携带任何信息，却出现在时间列的每一行里，
     * 还把列宽撑宽了两个字符。有真实小数秒时原样保留，一位都不截。</p>
     */
    public static String text(Object value) {
        if (value instanceof Timestamp timestamp && timestamp.getNanos() == 0) {
            String text = timestamp.toString();
            return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
        }
        return value.toString();
    }

    /** 截断到 {@code maxChars}，并在末尾留出位置放 {@code marker}。 */
    public static String truncate(String prefixSource, String marker, int maxChars) {
        if (maxChars <= 0) return "";
        if (prefixSource.length() <= maxChars && marker.isEmpty()) return prefixSource;
        if (marker.length() >= maxChars) return prefixSource.substring(0, Math.min(prefixSource.length(), maxChars));
        int prefixLength = Math.min(prefixSource.length(), maxChars - marker.length());
        return prefixSource.substring(0, prefixLength) + marker;
    }

    /**
     * 只数长度不读内容地描述一个二进制流。
     *
     * <p>有些驱动的 VARBINARY 只能从流里拿，长度得自己数。数到 {@link #BINARY_PROBE_BYTES}
     * 就停 —— 目的是给用户一个「这里是多大一坨二进制」的提示，不是把它读进内存。</p>
     */
    public static String describeBinaryStream(InputStream input) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while (total <= BINARY_PROBE_BYTES && (read = input.read(buffer)) >= 0) total += read;
        return total > BINARY_PROBE_BYTES ? "<BINARY > 1 MB>" : "<BINARY " + total + " bytes>";
    }
}
