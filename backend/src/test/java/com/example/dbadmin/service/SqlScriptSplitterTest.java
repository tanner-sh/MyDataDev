package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptSplitterTest {
    private final SqlScriptSplitter splitter = new SqlScriptSplitter();

    @Test
    void splitsStatementsBySemicolon() {
        var statements = splitter.split("select 1; select 2 ;\nselect 3");

        assertThat(statements).extracting(SqlScriptSplitter.StatementSegment::sql)
                .containsExactly("select 1", "select 2", "select 3");
    }

    @Test
    void ignoresSemicolonsInsideStringsAndQuotedIdentifiers() {
        var statements = splitter.split("select 'a;b' as value; select \"a;b\" from test; select `a;b`, [c;d] from test");

        assertThat(statements).extracting(SqlScriptSplitter.StatementSegment::sql)
                .containsExactly("select 'a;b' as value", "select \"a;b\" from test", "select `a;b`, [c;d] from test");
    }

    @Test
    void ignoresSemicolonsInsideComments() {
        var statements = splitter.split("""
                select 1; -- select 2;
                select /* a;b */ 3;
                """);

        assertThat(statements).extracting(SqlScriptSplitter.StatementSegment::sql)
                .containsExactly("select 1", "-- select 2;\nselect /* a;b */ 3");
    }

    @Test
    void returnsTrimmedOffsetsForFailedStatementSelection() {
        var statements = splitter.split("  select 1 ;\n  select 2");

        assertThat(statements.get(0).startOffset()).isEqualTo(2);
        assertThat(statements.get(0).endOffset()).isEqualTo(10);
        assertThat(statements.get(1).startOffset()).isEqualTo(15);
        assertThat(statements.get(1).endOffset()).isEqualTo(23);
    }

    @Test
    void keepsSemicolonsInsidePostgresAndOracleAlternativeQuotes() {
        var statements = splitter.split("select $$a;b$$; select $tag$c;d$tag$; select q'[e;f]' from dual");

        assertThat(statements).extracting(SqlScriptSplitter.StatementSegment::sql)
                .containsExactly("select $$a;b$$", "select $tag$c;d$tag$", "select q'[e;f]' from dual");
    }

    @Test
    void ignoresTrailingCommentOnlySegments() {
        var statements = splitter.split("select 1; -- trailing comment only");

        assertThat(statements).extracting(SqlScriptSplitter.StatementSegment::sql)
                .containsExactly("select 1");
    }
}
