package com.example.dbadmin.service;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;

/**
 * 解码生产确认串。
 *
 * <p>确认串就是连接名，而连接名在本项目里几乎必然含中文。它通过
 * {@code X-Production-Confirmation} 请求头传输，但 HTTP 头值只能是 ISO-8859-1：浏览器的
 * {@code fetch} 在构造 Headers 时就会抛
 * {@code TypeError: String contains non ISO-8859-1 code point}，请求根本发不出去。结果是
 * 只要生产连接用中文命名，执行 SQL、翻页、导出、提交表数据、表结构设计、表增删改名、
 * schema 对象生命周期与调用全部不可用。</p>
 *
 * <p>因此前端统一用 {@code encodeURIComponent} 编码后再放进请求头，这里负责还原。</p>
 *
 * <p>解码是「尽力而为」的：只有当整个值都是 ASCII 且包含 {@code %} 时才尝试还原，任何
 * 格式错误都原样返回。这样既能处理编码过的请求头，也不会破坏从 JSON body 里直接传来的
 * 原始值（恢复任务就是这么传的，body 是 UTF-8，本来就不需要编码）。</p>
 */
final class ProductionConfirmationCodec {
    private ProductionConfirmationCodec() {
    }

    static String decode(String value) {
        if (value == null || value.indexOf('%') < 0 || !isAscii(value)) return value;
        try {
            return percentDecode(value);
        } catch (IllegalArgumentException | CharacterCodingException malformed) {
            // 不是我们编码出来的值，原样交给调用方比较。
            return value;
        }
    }

    private static boolean isAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) return false;
        }
        return true;
    }

    /**
     * 只识别 {@code %XX}，不把 {@code +} 当空格。
     *
     * <p>{@link java.net.URLDecoder} 会把 {@code +} 解码成空格，那是
     * application/x-www-form-urlencoded 的规则，而 {@code encodeURIComponent} 生成的是
     * 普通百分号编码 —— 用 URLDecoder 会把名字里带加号的连接解坏。</p>
     */
    private static String percentDecode(String value) throws CharacterCodingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '%') {
                bytes.write(current);
                continue;
            }
            if (index + 2 >= value.length()) throw new IllegalArgumentException("截断的百分号转义");
            int high = Character.digit(value.charAt(index + 1), 16);
            int low = Character.digit(value.charAt(index + 2), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("非法的百分号转义");
            bytes.write((high << 4) + low);
            index += 2;
        }
        // 严格解码：半个 UTF-8 序列必须报错，否则会得到替换字符并让确认串意外不匹配。
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()));
        return decoded.toString();
    }
}
