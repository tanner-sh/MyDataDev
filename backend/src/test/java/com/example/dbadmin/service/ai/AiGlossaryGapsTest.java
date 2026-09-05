package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiGlossaryGapsTest {
    @Test
    void keepsBusinessWordsAndDropsWholeSentences() {
        List<String> candidates = AiGlossaryGaps.candidates(List.of(
                "会员",
                "买家手机号",
                // 模型偶尔会把整句需求当检索词。词典里混进一句话，比少一条词条更糟。
                "每个客户本月的成交总金额是多少",
                "monthly_recurring_revenue_by_channel_and_region"));

        assertThat(candidates).containsExactly("会员", "买家手机号");
    }

    @Test
    void dropsTermsNobodyCouldTurnIntoAGlossaryEntry() {
        assertThat(AiGlossaryGaps.candidates(List.of("   ", "123456", "%%%", "2026-09-05"))).isEmpty();
    }

    @Test
    void deduplicatesRegardlessOfCaseAndSpacingAndKeepsTheFirstSpelling() {
        List<String> candidates = AiGlossaryGaps.candidates(List.of("Buyer", "buyer", "  BUYER  ", "买家"));

        assertThat(candidates).containsExactly("Buyer", "买家");
    }

    @Test
    void stopsAtTheRunLimitSoOneWildAgentCannotFloodTheList() {
        List<String> queries = new java.util.ArrayList<>();
        for (int index = 0; index < 30; index++) queries.add("词" + index);

        assertThat(AiGlossaryGaps.candidates(queries)).hasSize(AiGlossaryGaps.MAX_TERMS_PER_RUN);
    }

    /** 词条本身和它的别名都算「已经有答案了」，销账时两者都要认。 */
    @Test
    void treatsBothTermsAndAliasesAsCovered() {
        List<AiBusinessTerm> terms = List.of(
                new AiBusinessTerm(1, 7, "客户", List.of("会员", "买家"), List.of("APP_USER"), null));

        assertThat(AiGlossaryGaps.covered(terms)).containsExactlyInAnyOrder("客户", "会员", "买家");
    }
}
