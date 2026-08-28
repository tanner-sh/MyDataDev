package com.example.dbadmin.service;

import java.sql.Timestamp;

/**
 * 结果单元格落到 JSON 之前的文本化规则。
 *
 * <p>查询结果与表数据浏览各有一套 {@code serializableValue}，两边对同一个值必须给出同样的
 * 文本 —— 否则同一张表从 SQL 工作台看和从表数据看会长得不一样。共用的部分放这里。</p>
 */
final class CellValues {
    private CellValues() {
    }

    /**
     * 日期时间的文本形式。
     *
     * <p>{@code java.sql.Timestamp.toString()} 在没有小数秒时也会补一位 {@code .0}
     * （{@code 2025-09-18 14:30:00.0}）。那位 0 不携带任何信息，却出现在时间列的每一行里，
     * 还把列宽撑宽了两个字符。有真实小数秒时原样保留，一位都不截。</p>
     */
    static String text(Object value) {
        if (value instanceof Timestamp timestamp && timestamp.getNanos() == 0) {
            String text = timestamp.toString();
            return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
        }
        return value.toString();
    }
}
