package com.example.dbadmin.service;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class SqlFileStatementReader {
    private SqlFileStatementReader() {
    }

    static Charset detectCharset(Path path) throws Exception {
        byte[] prefix = new byte[3];
        int read;
        try (InputStream input = Files.newInputStream(path)) {
            read = input.read(prefix);
        }
        if (read >= 3 && (prefix[0] & 0xff) == 0xef && (prefix[1] & 0xff) == 0xbb && (prefix[2] & 0xff) == 0xbf) {
            return StandardCharsets.UTF_8;
        }
        if (read >= 2 && (prefix[0] & 0xff) == 0xff && (prefix[1] & 0xff) == 0xfe) {
            return StandardCharsets.UTF_16LE;
        }
        if (read >= 2 && (prefix[0] & 0xff) == 0xfe && (prefix[1] & 0xff) == 0xff) {
            return StandardCharsets.UTF_16BE;
        }
        if (valid(path, StandardCharsets.UTF_8)) return StandardCharsets.UTF_8;
        Charset gb18030 = Charset.forName("GB18030");
        if (valid(path, gb18030)) return gb18030;
        throw new IllegalArgumentException("无法识别 SQL 文件编码；仅支持带 BOM 的 UTF 编码、UTF-8 和 GB18030。");
    }

    private static boolean valid(Path path, Charset charset) throws IOException {
        CharsetDecoder decoder = decoder(charset);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = new byte[128 * 1024];
            ByteBuffer pending = ByteBuffer.allocate(bytes.length + 8);
            CharBuffer chars = CharBuffer.allocate(bytes.length);
            int count;
            while ((count = input.read(bytes)) >= 0) {
                if (count == 0) continue;
                pending.put(bytes, 0, count).flip();
                while (true) {
                    var result = decoder.decode(pending, chars, false);
                    if (result.isError()) result.throwException();
                    chars.clear();
                    if (result.isUnderflow()) break;
                }
                pending.compact();
            }
            pending.flip();
            var result = decoder.decode(pending, chars, true);
            if (result.isError()) result.throwException();
            result = decoder.flush(chars);
            if (result.isError()) result.throwException();
            return true;
        } catch (CharacterCodingException error) {
            return false;
        }
    }

    static void read(Path path, Charset charset, String dbType, int maxStatementChars,
                     StatementConsumer consumer, ProgressConsumer progress) throws Exception {
        try (CountingInputStream input = new CountingInputStream(Files.newInputStream(path));
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder(charset)), 128 * 1024)) {
            Parser parser = new Parser(dbType, maxStatementChars, consumer);
            String line;
            long lastProgress = 0;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine && !line.isEmpty() && line.charAt(0) == '\ufeff') line = line.substring(1);
                firstLine = false;
                parser.acceptLine(line);
                if (input.count() - lastProgress >= 4L * 1024 * 1024) {
                    lastProgress = input.count();
                    progress.accept(lastProgress);
                }
            }
            parser.finish();
            progress.accept(input.count());
        }
    }

    private static CharsetDecoder decoder(Charset charset) {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static final class Parser {
        private final boolean mysql;
        private final boolean sqlServer;
        private final boolean oracle;
        private final int maxStatementChars;
        private final StatementConsumer consumer;
        private final StringBuilder statement = new StringBuilder();
        private String delimiter = ";";
        private String dollarQuote;
        private char oracleQuoteEnd;
        private boolean single;
        private boolean doubleQuoted;
        private boolean backtick;
        private boolean bracket;
        private boolean blockComment;
        private boolean oracleBlock;
        private long index;

        private Parser(String dbType, int maxStatementChars, StatementConsumer consumer) {
            String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
            this.mysql = type.equals("mysql") || type.equals("mariadb") || type.equals("oceanbase-mysql");
            this.sqlServer = type.equals("sqlserver") || type.equals("sql-server") || type.equals("mssql");
            this.oracle = type.equals("oracle") || type.equals("dm") || type.equals("dameng") || type.equals("oceanbase-oracle");
            this.maxStatementChars = Math.max(1, maxStatementChars);
            this.consumer = consumer;
        }

        private void acceptLine(String line) throws Exception {
            String trimmed = line.trim();
            if (cleanState()) {
                if (mysql && statement.toString().isBlank() && trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                    String next = trimmed.substring("DELIMITER".length()).trim();
                    if (next.isBlank() || next.length() > 16 || next.chars().anyMatch(Character::isWhitespace)) {
                        throw new IllegalArgumentException("MySQL DELIMITER 指令不合法。");
                    }
                    delimiter = next;
                    statement.setLength(0);
                    return;
                }
                if (sqlServer && trimmed.equalsIgnoreCase("GO")) {
                    emit();
                    return;
                }
                if (sqlServer && trimmed.toUpperCase(Locale.ROOT).matches("GO\\s+\\d+")) {
                    throw new IllegalArgumentException("暂不支持带重复次数的 SQL Server GO 指令。");
                }
                if (oracle && trimmed.equals("/")) {
                    emit();
                    oracleBlock = false;
                    return;
                }
            }

            if (oracle && statement.toString().isBlank()) oracleBlock = isOracleBlockStart(trimmed);
            boolean lineComment = false;
            for (int cursor = 0; cursor < line.length(); cursor++) {
                char current = line.charAt(cursor);
                char next = cursor + 1 < line.length() ? line.charAt(cursor + 1) : '\0';
                append(current);
                if (lineComment) continue;
                if (dollarQuote != null) {
                    if (line.startsWith(dollarQuote, cursor)) {
                        for (int extra = 1; extra < dollarQuote.length(); extra++) append(line.charAt(cursor + extra));
                        cursor += dollarQuote.length() - 1;
                        dollarQuote = null;
                    }
                    continue;
                }
                if (oracleQuoteEnd != '\0') {
                    if (current == oracleQuoteEnd && next == '\'') {
                        append(next);
                        cursor++;
                        oracleQuoteEnd = '\0';
                    }
                    continue;
                }
                if (blockComment) {
                    if (current == '*' && next == '/') {
                        append(next);
                        cursor++;
                        blockComment = false;
                    }
                    continue;
                }
                if (single) {
                    if (current == '\'' && next == '\'') {
                        append(next);
                        cursor++;
                    } else if (current == '\'' && (cursor == 0 || line.charAt(cursor - 1) != '\\')) single = false;
                    continue;
                }
                if (doubleQuoted) {
                    if (current == '"' && next == '"') {
                        append(next);
                        cursor++;
                    } else if (current == '"') doubleQuoted = false;
                    continue;
                }
                if (backtick) {
                    if (current == '`' && next == '`') {
                        append(next);
                        cursor++;
                    } else if (current == '`') backtick = false;
                    continue;
                }
                if (bracket) {
                    if (current == ']' && next == ']') {
                        append(next);
                        cursor++;
                    } else if (current == ']') bracket = false;
                    continue;
                }

                if (current == '-' && next == '-') {
                    append(next);
                    cursor++;
                    lineComment = true;
                } else if (current == '/' && next == '*') {
                    append(next);
                    cursor++;
                    blockComment = true;
                } else if (current == '\'') single = true;
                else if (current == '"') doubleQuoted = true;
                else if (current == '`') backtick = true;
                else if (current == '[') bracket = true;
                else if ((current == 'q' || current == 'Q') && next == '\'' && cursor + 2 < line.length()) {
                    append(next);
                    append(line.charAt(cursor + 2));
                    oracleQuoteEnd = matchingOracleQuote(line.charAt(cursor + 2));
                    cursor += 2;
                } else if (current == '$' && !mysql) {
                    String tag = dollarDelimiter(line, cursor);
                    if (tag != null) {
                        for (int extra = 1; extra < tag.length(); extra++) append(line.charAt(cursor + extra));
                        cursor += tag.length() - 1;
                        dollarQuote = tag;
                    }
                }

                if (cleanState() && !sqlServer && !(oracle && oracleBlock) && endsWithDelimiter()) {
                    statement.setLength(statement.length() - delimiter.length());
                    emit();
                }
            }
            append('\n');
        }

        private void finish() throws Exception {
            if (!cleanState()) throw new IllegalArgumentException("SQL 文件存在未闭合的字符串或注释。");
            emit();
        }

        private boolean cleanState() {
            return !single && !doubleQuoted && !backtick && !bracket && !blockComment && dollarQuote == null && oracleQuoteEnd == '\0';
        }

        private boolean endsWithDelimiter() {
            if (statement.length() < delimiter.length()) return false;
            for (int i = 0; i < delimiter.length(); i++) {
                if (statement.charAt(statement.length() - delimiter.length() + i) != delimiter.charAt(i)) return false;
            }
            return true;
        }

        private void emit() throws Exception {
            String sql = statement.toString().trim();
            statement.setLength(0);
            if (sql.isBlank() || sql.replaceAll("(?s)/\\*.*?\\*/|--.*?(?:\\R|$)", "").isBlank()) return;
            consumer.accept(++index, sql);
        }

        private void append(char value) {
            statement.append(value);
            if (statement.length() > maxStatementChars) {
                throw new IllegalArgumentException("单条 SQL 超过允许大小（" + maxStatementChars + " 字符）。");
            }
        }

        private boolean isOracleBlockStart(String value) {
            String normalized = value.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            return normalized.equals("BEGIN") || normalized.startsWith("BEGIN ") || normalized.equals("DECLARE")
                    || normalized.startsWith("DECLARE ")
                    || normalized.matches("CREATE( OR REPLACE)? (PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE BODY)\\b.*");
        }

        private char matchingOracleQuote(char opening) {
            return switch (opening) { case '[' -> ']'; case '{' -> '}'; case '(' -> ')'; case '<' -> '>'; default -> opening; };
        }

        private String dollarDelimiter(String line, int start) {
            int end = start + 1;
            while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
            if (end >= line.length() || line.charAt(end) != '$') return null;
            String tag = line.substring(start + 1, end);
            return tag.isEmpty() || Character.isLetter(tag.charAt(0)) || tag.charAt(0) == '_' ? line.substring(start, end + 1) : null;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;
        private CountingInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException { int value = super.read(); if (value >= 0) count++; return value; }
        @Override public int read(byte[] value, int offset, int length) throws IOException {
            int read = super.read(value, offset, length); if (read > 0) count += read; return read;
        }
        private long count() { return count; }
    }

    @FunctionalInterface interface StatementConsumer { void accept(long index, String sql) throws Exception; }
    @FunctionalInterface interface ProgressConsumer { void accept(long processedBytes) throws Exception; }
}
