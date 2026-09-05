package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiPlanAdviceTest {
    @Test
    void acceptsTheIndexScriptsThatPlanReadingIsActuallyFor() {
        assertThat(AiPlanAdvice.isIndexScript("CREATE INDEX idx_order_status ON SALES_ORDER(ORDER_STATUS)")).isTrue();
        assertThat(AiPlanAdvice.isIndexScript("create unique index uq_user_email on APP_USER(EMAIL)")).isTrue();
        // PostgreSQL 的在线建索引，CONCURRENTLY 排在 INDEX 后面。
        assertThat(AiPlanAdvice.isIndexScript("CREATE INDEX CONCURRENTLY idx_a ON t(a)")).isTrue();
    }

    /** 模型常在语句前先写一句说明，那不该让整条建议被打回。 */
    @Test
    void looksPastLeadingComments() {
        assertThat(AiPlanAdvice.isIndexScript("-- 给订单状态加索引\nCREATE INDEX idx_a ON t(a)")).isTrue();
        assertThat(AiPlanAdvice.isIndexScript("/* 建议 */ CREATE INDEX idx_a ON t(a)")).isTrue();
    }

    /**
     * 删索引、改表、写数据都不算「建议」：删一个索引是否安全取决于这个库上还有谁在用它，
     * 那是人的判断，不该被顺手写进编辑器。
     */
    @Test
    void refusesEverythingElseThatCallsItselfAnOptimization() {
        assertThat(AiPlanAdvice.isIndexScript("DROP INDEX idx_order_status")).isFalse();
        assertThat(AiPlanAdvice.isIndexScript("ALTER TABLE SALES_ORDER ADD COLUMN x INT")).isFalse();
        assertThat(AiPlanAdvice.isIndexScript("DELETE FROM SALES_ORDER WHERE ORDER_STATUS = 'DRAFT'")).isFalse();
        assertThat(AiPlanAdvice.isIndexScript("CREATE TABLE t(a INT)")).isFalse();
        assertThat(AiPlanAdvice.isIndexScript("CREATE MATERIALIZED VIEW mv AS SELECT 1")).isFalse();
        assertThat(AiPlanAdvice.isIndexScript("SELECT 1")).isFalse();
        assertThat(AiPlanAdvice.isIndexScript(null)).isFalse();
        assertThat(AiPlanAdvice.isIndexScript("   ")).isFalse();
    }
}
