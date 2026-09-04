package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiSqlShapeTest {
    @Test
    void removesStringAndNumericLiteralsSoNoBusinessValueLeavesTheHost() {
        String masked = AiSqlShape.mask(
                "SELECT * FROM T_CRM_0021 WHERE MOBILE = '13800138000' AND LEVEL_CD = 'VIP' AND ID > 88888");

        assertThat(masked).isEqualTo("SELECT * FROM T_CRM_0021 WHERE MOBILE = ? AND LEVEL_CD = ? AND ID > ?");
        assertThat(masked).doesNotContain("13800138000").doesNotContain("VIP").doesNotContain("88888");
    }

    @Test
    void keepsQuotedIdentifiersBecauseThoseAreTableAndColumnNames() {
        assertThat(AiSqlShape.mask("select \"CUST_NM\", `MOBILE`, [ID] from \"T_CRM_0021\" where [ID] = 7"))
                .isEqualTo("select \"CUST_NM\", `MOBILE`, [ID] from \"T_CRM_0021\" where [ID] = ?");
    }

    @Test
    void dropsCommentsWhichOftenCarryTicketNumbersAndNames() {
        assertThat(AiSqlShape.mask("""
                -- 给张三导的名单 JIRA-1234
                SELECT ID /* 内部编号 */ FROM APP_USER
                """))
                .isEqualTo("SELECT ID FROM APP_USER");
    }

    @Test
    void handlesEscapedQuotesWithoutSpillingTheRestOfTheStatement() {
        assertThat(AiSqlShape.mask("SELECT * FROM T WHERE NAME = 'O''Brien' AND CITY = 'x' "))
                .isEqualTo("SELECT * FROM T WHERE NAME = ? AND CITY = ?");
        assertThat(AiSqlShape.mask("SELECT * FROM T WHERE NAME = 'a\\'b' AND CITY = 'y'"))
                .isEqualTo("SELECT * FROM T WHERE NAME = ? AND CITY = ?");
    }

    @Test
    void doesNotMangleIdentifiersThatContainDigits() {
        assertThat(AiSqlShape.mask("SELECT A1, B2 FROM T_CRM_0021 JOIN SALES_ORDER2 ON A1 = 5"))
                .isEqualTo("SELECT A1, B2 FROM T_CRM_0021 JOIN SALES_ORDER2 ON A1 = ?");
    }

    @Test
    void collapsesWhitespaceSoTheSameQueryFormattedTwoWaysHasOneFingerprint() {
        String one = "SELECT   ID\n  FROM APP_USER\n WHERE ID = 1";
        String other = "select id from app_user where id = 999";

        assertThat(AiSqlShape.mask(one)).isEqualTo("SELECT ID FROM APP_USER WHERE ID = ?");
        assertThat(AiSqlShape.fingerprint(one)).isEqualTo(AiSqlShape.fingerprint(other));
    }

    @Test
    void toleratesUnterminatedLiterals() {
        assertThat(AiSqlShape.mask("SELECT * FROM T WHERE NAME = 'unclosed")).isEqualTo("SELECT * FROM T WHERE NAME = ?");
        assertThat(AiSqlShape.mask(null)).isEmpty();
        assertThat(AiSqlShape.mask("   ")).isEmpty();
    }
}
