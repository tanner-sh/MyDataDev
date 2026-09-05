package com.example.dbadmin.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiClarifyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsTheQuestionAndItsOptions() throws Exception {
        var parsed = AiClarify.parse(JSON.readTree("""
                {"question":"按下单时间还是支付时间统计？","options":[
                  {"label":"下单时间","detail":"SALES_ORDER.ORDER_DATE"},
                  {"label":"支付时间","detail":"PAYMENT.PAID_AT"}]}
                """));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().question()).isEqualTo("按下单时间还是支付时间统计？");
        assertThat(parsed.get().options()).extracting(AiClarifyQuestion.Option::label)
                .containsExactly("下单时间", "支付时间");
        assertThat(parsed.get().options().get(0).detail()).isEqualTo("SALES_ORDER.ORDER_DATE");
    }

    /** 「这个订单号是多少」给不出选项。强行要求只会让模型编造几个。 */
    @Test
    void acceptsAQuestionWithNoOptions() throws Exception {
        var parsed = AiClarify.parse(JSON.readTree("{\"question\":\"这个订单号是多少？\"}"));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().options()).isEmpty();
    }

    /** 没有问题的反问会让对话停在一个空气泡上，还不如让模型重来一次。 */
    @Test
    void refusesAClarificationWithoutAQuestion() throws Exception {
        assertThat(AiClarify.parse(JSON.readTree("{\"options\":[{\"label\":\"A\"}]}"))).isEmpty();
        assertThat(AiClarify.parse(JSON.readTree("{\"question\":\"   \"}"))).isEmpty();
        assertThat(AiClarify.parse(null)).isEmpty();
    }

    @Test
    void dropsBlankAndDuplicateOptionsAndStopsAtTheCap() throws Exception {
        var parsed = AiClarify.parse(JSON.readTree("""
                {"question":"哪一张？","options":[
                  {"label":"客户表"},{"label":"客户表"},{"label":"  "},
                  {"label":"A"},{"label":"B"},{"label":"C"},{"label":"D"},{"label":"E"}]}
                """));

        assertThat(parsed.orElseThrow().options()).extracting(AiClarifyQuestion.Option::label)
                .containsExactly("客户表", "A", "B", "C", "D")
                .hasSize(AiClarify.MAX_OPTIONS);
    }

    /** 问题和选项都来自模型，长度得有个上界，否则一屏按钮能撑成一段文章。 */
    @Test
    void clampsOverlongText() throws Exception {
        var parsed = AiClarify.parse(JSON.readTree(JSON.writeValueAsString(java.util.Map.of(
                "question", "问".repeat(500),
                "options", java.util.List.of(java.util.Map.of("label", "选".repeat(200)))))));

        assertThat(parsed.orElseThrow().question()).hasSize(AiClarify.MAX_QUESTION_CHARS);
        assertThat(parsed.orElseThrow().options().get(0).label()).hasSize(AiClarify.MAX_LABEL_CHARS);
    }

    @Test
    void declaresTheToolWithAQuestionThatIsRequired() {
        var definition = AiClarify.definition();

        assertThat(definition.name()).isEqualTo("ask_user");
        assertThat(definition.inputSchema().path("required").toString()).contains("question");
        assertThat(definition.description()).contains("歧义");
    }
}
