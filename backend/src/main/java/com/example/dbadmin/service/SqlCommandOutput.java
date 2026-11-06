package com.example.dbadmin.service;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 命令窗口里每条语句的输出文字：SQL*Plus 的习惯是语句后面跟一句结果（「已插入 1 行。」），查询则打出
 * 一张文本表。这里只做文字，不碰执行与事务。
 */
final class SqlCommandOutput {
    static final int MAX_CELL = 40;
    static final int MAX_TEXT = 8_000;

    private SqlCommandOutput() {
    }

    /** 日志里一条语句的摘要：去掉开头的注释行，压成一行，过长截断。 */
    static String summary(String sql) {
        StringBuilder text = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || (text.isEmpty() && trimmed.startsWith("--"))) continue;
            if (!text.isEmpty()) text.append(' ');
            text.append(trimmed);
            if (text.length() > 240) break;
        }
        String value = text.isEmpty() ? sql.strip() : text.toString().replaceAll("\\s+", " ");
        return value.length() > 200 ? value.substring(0, 199) + "…" : value;
    }

    /** 不返回结果集的语句：按语句种类说一句人话。DDL 与例程调用驱动给的是 0，不说「影响 0 行」。 */
    static String updateText(String sql, int updateCount) {
        String head = SqlScriptSyntax.leadingSql(sql).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        String verb = head.startsWith("INSERT") ? "已插入" : head.startsWith("UPDATE") ? "已更新"
                : head.startsWith("DELETE") ? "已删除" : head.startsWith("MERGE") ? "已合并" : null;
        if (verb != null) return verb + " " + Math.max(updateCount, 0) + " 行。";
        if (head.matches("(?:CREATE|ALTER|DROP|TRUNCATE|RENAME|COMMENT|GRANT|REVOKE)\\b.*")) return "已执行（" + head.split(" ")[0] + "）。";
        if (head.matches("(?:COMMIT)\\b.*")) return "已提交。";
        if (head.matches("(?:ROLLBACK)\\b.*")) return "已回滚。";
        if (head.matches("(?:BEGIN|DECLARE|CALL|EXEC|EXECUTE)\\b.*")) return "PL/SQL 过程已成功完成。";
        return updateCount > 0 ? "影响 " + updateCount + " 行。" : "执行成功。";
    }

    /** 读一个结果集：前 previewRows 行排成文本表，其余只计数（最多数到 maxCounted）。 */
    static Rendered render(ResultSet rows, int previewRows, int maxCounted, Runnable cancellationCheck) throws Exception {
        ResultSetMetaData meta = rows.getMetaData();
        int columns = meta.getColumnCount();
        List<String> labels = new ArrayList<>(columns);
        for (int i = 1; i <= columns; i++) labels.add(meta.getColumnLabel(i));
        List<List<String>> preview = new ArrayList<>();
        long count = 0;
        boolean more = false;
        while (rows.next()) {
            count++;
            if (preview.size() < previewRows) {
                List<String> row = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    Object value;
                    try {
                        value = rows.getObject(i);
                    } catch (Exception unreadable) {
                        value = "<无法读取>";
                    }
                    row.add(value == null ? "NULL" : String.valueOf(value));
                }
                preview.add(row);
            }
            if ((count & 1023) == 0) cancellationCheck.run();
            if (count >= maxCounted) {
                more = rows.next();
                break;
            }
        }
        return new Rendered(table(labels, preview) + "\n" + rowCountText(count, preview.size(), more), count);
    }

    record Rendered(String text, long rows) { }

    static String rowCountText(long count, int shown, boolean more) {
        if (count == 0) return "未选定行。";
        String total = more ? "至少 " + count + " 行" : "共 " + count + " 行";
        return shown < count || more ? "已选择 " + total + "，仅显示前 " + shown + " 行。" : "已选择 " + total + "。";
    }

    /** 等宽文本表：列宽取表头与值的最大宽度（单元格截到 MAX_CELL），中文按两个宽度算。 */
    static String table(List<String> labels, List<List<String>> rows) {
        int columns = labels.size();
        int[] widths = new int[columns];
        List<String> header = labels.stream().map(SqlCommandOutput::cell).toList();
        List<List<String>> cells = rows.stream().map(row -> row.stream().map(SqlCommandOutput::cell).toList()).toList();
        for (int i = 0; i < columns; i++) {
            widths[i] = width(header.get(i));
            for (List<String> row : cells) widths[i] = Math.max(widths[i], width(row.get(i)));
        }
        StringBuilder out = new StringBuilder();
        line(out, header, widths);
        out.append('\n');
        for (int i = 0; i < columns; i++) {
            if (i > 0) out.append(' ');
            out.append("-".repeat(widths[i]));
        }
        for (List<String> row : cells) {
            out.append('\n');
            line(out, row, widths);
        }
        String text = out.toString();
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "\n…" : text;
    }

    private static void line(StringBuilder out, List<String> values, int[] widths) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(' ');
            String value = values.get(i);
            out.append(value);
            if (i < values.size() - 1) out.append(" ".repeat(Math.max(0, widths[i] - width(value))));
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
    }

    private static String cell(String value) {
        String flat = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return flat.codePointCount(0, flat.length()) > MAX_CELL ? flat.substring(0, flat.offsetByCodePoints(0, MAX_CELL - 1)) + "…" : flat;
    }

    static int width(String value) {
        int width = 0;
        for (int i = 0; i < value.length(); ) {
            int code = value.codePointAt(i);
            width += code > 0x2e80 && code < 0xff61 || code >= 0xffe0 && code <= 0xffe6 ? 2 : 1;
            i += Character.charCount(code);
        }
        return width;
    }
}
