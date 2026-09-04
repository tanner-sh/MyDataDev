package com.example.dbadmin.service.ai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiEvalScoringTest {
    private static final AiEvalCase CASE = AiEvalCase.of("rep-monthly",
            "统计每个销售员本月成交的订单数",
            List.of("SALES_ORDER", "APP_USER"),
            List.of("APP_USER_ARCHIVE"),
            "干扰项");

    @Test
    void passesWhenEveryExpectedTableIsUsedAndTheSqlCompiled() {
        var score = AiEvalScoring.score(CASE, """
                ```sql
                SELECT u.DISPLAY_NAME, COUNT(*) FROM SALES_ORDER o
                JOIN APP_USER u ON u.ID = o.SALES_REP_ID GROUP BY u.DISPLAY_NAME
                ```
                按销售员分组统计。
                """, true);

        assertThat(score.passed()).isTrue();
        assertThat(score.matchedTables()).containsExactly("APP_USER", "SALES_ORDER");
        assertThat(score.missingTables()).isEmpty();
        assertThat(score.reason()).isEqualTo("通过");
    }

    @Test
    void failsOnAMissingTableEvenWhenTheSqlCompiles() {
        var score = AiEvalScoring.score(CASE, "```sql\nSELECT COUNT(*) FROM SALES_ORDER\n```", true);

        assertThat(score.passed()).isFalse();
        assertThat(score.missingTables()).containsExactly("APP_USER");
        assertThat(score.reason()).contains("漏掉 APP_USER");
    }

    @Test
    void failsOnTheArchiveTableEvenWhenEveryExpectedTableIsAlsoPresent() {
        var score = AiEvalScoring.score(CASE, """
                ```sql
                SELECT u.DISPLAY_NAME FROM SALES_ORDER o
                JOIN APP_USER u ON u.ID = o.SALES_REP_ID
                UNION ALL SELECT a.DISPLAY_NAME FROM APP_USER_ARCHIVE a
                ```
                """, true);

        assertThat(score.passed()).isFalse();
        assertThat(score.forbiddenTables()).containsExactly("APP_USER_ARCHIVE");
    }

    @Test
    void failsWhenTheSqlDidNotCompile() {
        var score = AiEvalScoring.score(CASE,
                "```sql\nSELECT * FROM SALES_ORDER JOIN APP_USER ON 1=1\n```", false);

        assertThat(score.passed()).isFalse();
        assertThat(score.reason()).isEqualTo("未通过目标库编译校验");
    }

    @Test
    void treatsTwoSqlBlocksAsNoAnswerBecauseTheAgentOnlyOwesOne() {
        var score = AiEvalScoring.score(CASE,
                "```sql\nSELECT 1 FROM SALES_ORDER\n```\n或者\n```sql\nSELECT 2 FROM APP_USER\n```", true);

        assertThat(score.sql()).isNull();
        assertThat(score.reason()).isEqualTo("没有产出唯一的一条 SQL");
    }

    @Test
    void ignoresSchemaPrefixesQuotingAndCaseWhenComparingTableNames() {
        var score = AiEvalScoring.score(CASE, """
                ```sql
                select * from public."sales_order" o join PUBLIC.app_user u on u.id = o.sales_rep_id
                ```
                """, true);

        assertThat(score.passed()).isTrue();
    }

    @Test
    void countsUnexpectedTablesWithoutFailingTheCase() {
        var score = AiEvalScoring.score(CASE, """
                ```sql
                SELECT * FROM SALES_ORDER o JOIN APP_USER u ON u.ID = o.SALES_REP_ID
                JOIN T_CRM_0021 c ON c.ID = o.CUSTOMER_ID
                ```
                """, true);

        assertThat(score.passed()).isTrue();
        assertThat(score.extraTables()).containsExactly("T_CRM_0021");
    }
}
