package com.example.dbadmin.service;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式 CSV 读取。
 *
 * <p>浏览器端的导入把整份文件读进内存再解析，因此封在 10 MB / 1000 行。要支持百万行级导入
 * 就必须边读边转，这里只保留 RFC 4180 需要的部分：双引号包裹、引号内的分隔符与换行、
 * 连续两个双引号表示一个字面量引号。</p>
 *
 * <p>不做类型推断 —— 值一律以文本交给上层，由目标列的类型决定怎么绑定。</p>
 */
final class CsvStreamReader implements AutoCloseable {
    /** 单个字段的上限，防止一份畸形文件把整行读成一个巨大的字符串。 */
    static final int MAX_FIELD_CHARS = 1_000_000;

    private final Reader reader;
    private int pushback = -2;
    private boolean endOfInput;
    /**
     * 上一行是不是「真空行」。
     *
     * <p>不能只看「一个字段且为空」：单列 CSV 里显式写成 {@code ""} 的空值解析结果也长这样，
     * 若按空行跳过，这些行会凭空消失，导入行数与文件对不上。带引号就说明是数据。</p>
     */
    private boolean blankLine;

    CsvStreamReader(Reader reader) {
        this.reader = reader;
    }

    /** 读下一行；到达文件末尾返回 {@code null}。空行（只有换行）会被跳过。 */
    List<String> readRow() throws IOException {
        while (!endOfInput) {
            List<String> row = readRowInternal();
            if (row == null) return null;
            if (blankLine) continue;
            return row;
        }
        return null;
    }

    private List<String> readRowInternal() throws IOException {
        int first = read();
        if (first == -1) {
            endOfInput = true;
            return null;
        }
        unread(first);

        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        boolean sawQuote = false;

        while (true) {
            int ch = read();
            if (ch == -1) {
                endOfInput = true;
                return finish(fields, field, sawQuote);
            }
            if (quoted) {
                if (ch == '"') {
                    int next = read();
                    if (next == '"') {
                        append(field, '"');
                        continue;
                    }
                    quoted = false;
                    if (next != -1) unread(next);
                    continue;
                }
                append(field, (char) ch);
                continue;
            }
            if (ch == '"' && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
                sawQuote = true;
                continue;
            }
            if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                continue;
            }
            if (ch == '\r') {
                int next = read();
                if (next != '\n' && next != -1) unread(next);
                return finish(fields, field, sawQuote);
            }
            if (ch == '\n') {
                return finish(fields, field, sawQuote);
            }
            fieldStarted = true;
            append(field, (char) ch);
        }
    }

    private List<String> finish(List<String> fields, StringBuilder field, boolean sawQuote) {
        fields.add(field.toString());
        blankLine = !sawQuote && fields.size() == 1 && fields.get(0).isEmpty();
        return fields;
    }

    private static void append(StringBuilder field, char ch) {
        if (field.length() >= MAX_FIELD_CHARS) {
            throw new IllegalArgumentException("CSV 单个字段超过 " + MAX_FIELD_CHARS + " 个字符，文件可能格式有误。");
        }
        field.append(ch);
    }

    private int read() throws IOException {
        if (pushback != -2) {
            int value = pushback;
            pushback = -2;
            return value;
        }
        return reader.read();
    }

    private void unread(int value) {
        pushback = value;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
