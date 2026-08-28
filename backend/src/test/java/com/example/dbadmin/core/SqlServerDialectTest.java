package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerDialectTest {
    private final SqlServerDialect dialect = new SqlServerDialect();

    @Test
    void paginationOnlyAcceptsATopLevelOrderByClause() {
        assertThat(dialect.pageQuery("SELECT 'order by' AS note", 20, 40))
                .isEqualTo("SELECT 'order by' AS note ORDER BY (SELECT NULL) OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY");
        assertThat(dialect.pageQuery("SELECT * FROM (SELECT * FROM t ORDER BY id) q", 20, 0))
                .contains("q ORDER BY (SELECT NULL) OFFSET 0 ROWS");
        assertThat(dialect.pageQuery("SELECT * FROM t /* order by fake */ ORDER /* keep */ BY id", 20, 0))
                .isEqualTo("SELECT * FROM t /* order by fake */ ORDER /* keep */ BY id OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY");
    }

    @Test
    void generatedLiteralsUseSqlServerBooleanUnicodeAndBinarySyntax() {
        assertThat(dialect.scriptLiteral(true)).isEqualTo("1");
        assertThat(dialect.scriptLiteral("中文'O")).isEqualTo("N'中文''O'");
        assertThat(dialect.scriptLiteral(new byte[]{0, (byte) 0xff})).isEqualTo("0x00ff");
    }
}
