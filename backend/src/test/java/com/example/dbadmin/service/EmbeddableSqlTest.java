package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddableSqlTest {
    @Test
    void stripsTrailingSemicolons() {
        assertThat(EmbeddableSql.of("SELECT 1;")).isEqualTo("SELECT 1");
        assertThat(EmbeddableSql.of("  SELECT 1 ; ;  ")).isEqualTo("SELECT 1");
    }

    @Test
    void leavesAStatementWithoutLineCommentsUntouched() {
        assertThat(EmbeddableSql.of("SELECT id FROM t ORDER BY id")).isEqualTo("SELECT id FROM t ORDER BY id");
    }

    /** 注释写在最后一行时，追加上去的分页子句会整段落进注释里。 */
    @Test
    void terminatesAStatementThatEndsInALineComment() {
        assertThat(EmbeddableSql.of("SELECT * FROM t -- 只看这张表"))
                .isEqualTo("SELECT * FROM t -- 只看这张表\n");
        assertThat(EmbeddableSql.of("SELECT * FROM t # 只看这张表"))
                .isEqualTo("SELECT * FROM t # 只看这张表\n");
    }

    /** 分号在注释里也照样去掉，去掉之后仍然要补换行。 */
    @Test
    void terminatesAStatementWhoseTrailingSemicolonSitsInsideAComment() {
        assertThat(EmbeddableSql.of("SELECT 1 -- 备注;")).isEqualTo("SELECT 1 -- 备注\n");
    }

    /**
     * 认错了只多一个换行，认漏了就是静默错页 —— 所以这条规则有意偏宽：
     * 字符串字面量里的 {@code --} 同样算数。
     */
    @Test
    void prefersAHarmlessNewlineOverParsingStringLiterals() {
        assertThat(EmbeddableSql.of("SELECT '--' FROM t")).isEqualTo("SELECT '--' FROM t\n");
    }

    @Test
    void handlesEmptyInput() {
        assertThat(EmbeddableSql.of(null)).isEmpty();
        assertThat(EmbeddableSql.of("  ;  ")).isEmpty();
    }
}
