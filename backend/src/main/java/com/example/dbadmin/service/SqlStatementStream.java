package com.example.dbadmin.service;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class SqlStatementStream {
    private static final int MAX_STATEMENT_CHARS = 128 * 1024 * 1024;
    private static final int READ_BUFFER_CHARS = 64 * 1024;

    private SqlStatementStream() {
    }

    static void read(Path path, StatementConsumer consumer) throws Exception {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path), 128 * 1024);
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder statement = new StringBuilder();
            boolean single = false;
            boolean doubleQuoted = false;
            boolean backtick = false;
            boolean bracket = false;
            boolean lineComment = false;
            boolean blockComment = false;
            int previous = -1;
            char[] buffer = new char[READ_BUFFER_CHARS];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                for (int i = 0; i < read; i++) {
                    char current = buffer[i];
                    statement.append(current);
                    if (statement.length() > MAX_STATEMENT_CHARS) {
                        throw new IllegalArgumentException("单条 SQL 超过 128MB，无法安全恢复。");
                    }
                    char before = previous < 0 ? '\0' : (char) previous;
                    if (lineComment) {
                        if (current == '\n' || current == '\r') lineComment = false;
                    } else if (blockComment) {
                        if (before == '*' && current == '/') blockComment = false;
                    } else if (single) {
                        if (current == '\'' && before != '\\') single = false;
                    } else if (doubleQuoted) {
                        if (current == '"' && before != '\\') doubleQuoted = false;
                    } else if (backtick) {
                        if (current == '`') backtick = false;
                    } else if (bracket) {
                        if (current == ']') bracket = false;
                    } else if (before == '-' && current == '-') {
                        lineComment = true;
                    } else if (before == '/' && current == '*') {
                        blockComment = true;
                    } else if (current == '\'') {
                        single = true;
                    } else if (current == '"') {
                        doubleQuoted = true;
                    } else if (current == '`') {
                        backtick = true;
                    } else if (current == '[') {
                        bracket = true;
                    } else if (current == ';') {
                        emit(statement, consumer);
                        statement.setLength(0);
                    }
                    previous = current;
                }
            }
            emit(statement, consumer);
        }
    }

    private static void emit(StringBuilder value, StatementConsumer consumer) throws Exception {
        String sql = value.toString().trim();
        if (!sql.isBlank() && !sql.replaceAll("(?s)/\\*.*?\\*/|--.*?(?:\\R|$)", "").isBlank()) {
            consumer.accept(sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql);
        }
    }

    @FunctionalInterface
    interface StatementConsumer {
        void accept(String sql) throws Exception;
    }
}
