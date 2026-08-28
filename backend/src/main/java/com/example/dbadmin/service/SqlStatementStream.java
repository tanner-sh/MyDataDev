package com.example.dbadmin.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

final class SqlStatementStream {
    private static final int MAX_STATEMENT_CHARS = 128 * 1024 * 1024;
    private SqlStatementStream() {
    }

    static void read(Path path, String sourceDbType, StatementConsumer consumer) throws Exception {
        SqlFileStatementReader.read(
                path,
                StandardCharsets.UTF_8,
                sourceDbType,
                MAX_STATEMENT_CHARS,
                (ignored, sql) -> consumer.accept(sql),
                ignored -> { }
        );
    }

    @FunctionalInterface
    interface StatementConsumer {
        void accept(String sql) throws Exception;
    }
}
