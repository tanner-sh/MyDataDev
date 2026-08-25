package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SelectProjectionTest {

    @Test
    void shouldAcceptPlainProjections() {
        assertThat(SelectProjection.isDirectColumnProjection("SELECT * FROM people")).isTrue();
        assertThat(SelectProjection.isDirectColumnProjection("select code, nickname from people")).isTrue();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT p.code, p.nickname FROM people p")).isTrue();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT p.* FROM app.people p WHERE code = 'x'")).isTrue();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT ALL code FROM people")).isTrue();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT \"My Col\", `other col` FROM people")).isTrue();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT code /* 说明 */, nickname -- 备注\nFROM people")).isTrue();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT code FROM people ORDER BY nickname")).isTrue();
    }

    @Test
    void shouldRejectAliasedProjections() {
        // 就是这条查询会让主键从别的列取值。
        assertThat(SelectProjection.isDirectColumnProjection("SELECT nickname AS code, code AS nickname FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT nickname code FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT code AS \"编码\" FROM people")).isFalse();
    }

    @Test
    void shouldRejectExpressionsAndAggregates() {
        assertThat(SelectProjection.isDirectColumnProjection("SELECT count(*) FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT code || nickname FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT 1 FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT 'literal' FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT (SELECT 1) FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT code, (a + b) FROM people")).isFalse();
    }

    @Test
    void shouldRejectQueriesWhoseRowsDoNotMapToTableRows() {
        assertThat(SelectProjection.isDirectColumnProjection("SELECT DISTINCT code FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT code FROM a UNION SELECT code FROM b")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("WITH t AS (SELECT 1) SELECT * FROM t")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT code")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("UPDATE people SET code = '1'")).isFalse();
    }

    @Test
    void shouldRejectUnparseableInput() {
        assertThat(SelectProjection.isDirectColumnProjection(null)).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("   ")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT \"unclosed FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT code, FROM people")).isFalse();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT code) FROM people")).isFalse();
    }

    @Test
    void shouldNotMistakeIdentifiersThatStartWithKeywords() {
        assertThat(SelectProjection.isDirectColumnProjection("SELECT fromage FROM cheese")).isTrue();
        assertThat(SelectProjection.isDirectColumnProjection("SELECT unionized FROM workers")).isTrue();
    }
}
