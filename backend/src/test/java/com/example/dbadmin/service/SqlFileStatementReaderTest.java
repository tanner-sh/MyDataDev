package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlFileStatementReaderTest {
    @TempDir
    Path directory;

    @Test
    void readsSemicolonStatementsWithoutSplittingQuotedValues() throws Exception {
        assertThat(read("generic", "insert into t values ('a;b');\n-- comment ;\nselect 1;"))
                .containsExactly("insert into t values ('a;b')", "-- comment ;\nselect 1");
    }

    @Test
    void readsMysqlDelimiterBlocks() throws Exception {
        String sql = """
                DELIMITER $$
                CREATE PROCEDURE p()
                BEGIN
                  SELECT 'a;b';
                END$$
                DELIMITER ;
                SELECT 2;
                """;
        assertThat(read("mysql", sql)).containsExactly(
                "CREATE PROCEDURE p()\nBEGIN\n  SELECT 'a;b';\nEND",
                "SELECT 2"
        );
    }

    @Test
    void readsPostgresDollarQuotes() throws Exception {
        assertThat(read("postgresql", "create function f() returns void as $$ begin perform 1; end; $$ language plpgsql;\nselect 2;"))
                .hasSize(2)
                .first().asString().contains("perform 1; end;");
    }

    @Test
    void readsSqlServerGoBatches() throws Exception {
        assertThat(read("sqlserver", "create table t(id int);\nGO\ninsert into t values (1);\ngo\n"))
                .containsExactly("create table t(id int);", "insert into t values (1);");
    }

    @Test
    void readsOracleSlashTerminatedBlock() throws Exception {
        String sql = """
                CREATE OR REPLACE PROCEDURE p AS
                BEGIN
                  NULL;
                END;
                /
                SELECT 1 FROM dual;
                """;
        assertThat(read("oracle", sql)).containsExactly(
                "CREATE OR REPLACE PROCEDURE p AS\nBEGIN\n  NULL;\nEND;",
                "SELECT 1 FROM dual"
        );
    }

    @Test
    void detectsUtf8BomAndGb18030() throws Exception {
        Path utf8 = directory.resolve("utf8.sql");
        Files.write(utf8, new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 's', 'e', 'l', 'e', 'c', 't', ' ', '1', ';'});
        assertThat(SqlFileStatementReader.detectCharset(utf8)).isEqualTo(StandardCharsets.UTF_8);

        Path gb = directory.resolve("gb.sql");
        Files.writeString(gb, "select '中文';", Charset.forName("GB18030"));
        assertThat(SqlFileStatementReader.detectCharset(gb).name()).isEqualTo("GB18030");
    }

    private List<String> read(String dbType, String sql) throws Exception {
        Path path = directory.resolve(dbType + ".sql");
        Files.writeString(path, sql, StandardCharsets.UTF_8);
        List<String> values = new ArrayList<>();
        SqlFileStatementReader.read(path, StandardCharsets.UTF_8, dbType, 1024 * 1024,
                (index, statement) -> values.add(statement), ignored -> { });
        return values;
    }
}
