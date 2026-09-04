package com.example.dbadmin.service.ai;

import com.example.dbadmin.service.ai.AiSchemaTools.CatalogObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiGlossarySuggestionsTest {
    private static CatalogObject table(String name, String comment) {
        return new CatalogObject(name, "TABLE", comment);
    }

    @Test
    void turnsATableCommentIntoATermAndKeepsTheFullCommentAsAnAlias() {
        var result = AiGlossarySuggestions.suggest(
                List.of(table("T_CRM_0021", "客户主档")), Set.of(), Map.of(), 10);

        assertThat(result.suggestions()).singleElement().satisfies(item -> {
            assertThat(item.term()).isEqualTo("客户");
            assertThat(item.aliases()).containsExactly("客户主档");
            assertThat(item.objectNames()).containsExactly("T_CRM_0021");
            assertThat(item.description()).isEqualTo("客户主档");
        });
    }

    /** 销售订单和订单明细都是「订单」，该合成一条词条指向两张表，而不是两条同名词条。 */
    @Test
    void mergesTablesThatShareTheSameBusinessTerm() {
        var result = AiGlossarySuggestions.suggest(List.of(
                table("SALES_ORDER", "订单主表"),
                table("SALES_ORDER_ITEM", "订单明细")), Set.of(), Map.of(), 10);

        assertThat(result.suggestions()).singleElement().satisfies(item -> {
            assertThat(item.term()).isEqualTo("订单");
            assertThat(item.objectNames()).containsExactly("SALES_ORDER", "SALES_ORDER_ITEM");
        });
    }

    @Test
    void skipsTermsTheGlossaryAlreadyCoversIncludingItsAliases() {
        var result = AiGlossarySuggestions.suggest(
                List.of(table("T_CRM_0021", "客户主档"), table("PRODUCT", "商品信息")),
                Set.of("客户"), Map.of(), 10);

        assertThat(result.suggestions()).extracting(AiGlossarySuggestions.Suggestion::term)
                .containsExactly("商品");
    }

    /** 没人查过的表先写词条没有意义，所以按执行历史里的使用次数排。 */
    @Test
    void ranksByHowOftenTheTableIsActuallyQueried() {
        var result = AiGlossarySuggestions.suggest(List.of(
                        table("AUDIT_TRAIL", "审计轨迹"),
                        table("SALES_ORDER", "订单主表"),
                        table("PRODUCT", "商品信息")),
                Set.of(), Map.of("SALES_ORDER", 40, "PRODUCT", 7), 10);

        assertThat(result.suggestions()).extracting(AiGlossarySuggestions.Suggestion::term)
                .containsExactly("订单", "商品", "审计轨迹");
    }

    /** 没有注释的表给不出候选词，但它恰恰是 AI 最找不到的那批，要单独列出来。 */
    @Test
    void listsUncommentedObjectsSeparatelyInsteadOfSilentlyDroppingThem() {
        var result = AiGlossarySuggestions.suggest(List.of(
                table("T_CRM_0021", "客户主档"),
                table("T_XX_0099", null),
                table("T_YY_0100", "  ")), Set.of(), Map.of(), 10);

        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.uncommented()).containsExactly("T_XX_0099", "T_YY_0100");
    }

    @Test
    void ignoresCommentsThatAreSentencesRatherThanNames() {
        var result = AiGlossarySuggestions.suggest(List.of(
                table("A", "这张表按天汇总每个渠道的成交金额，数据由 ETL 于凌晨写入，不要直接修改"),
                table("B", "支付流水，含微信和支付宝两个渠道")), Set.of(), Map.of(), 10);

        // 第二条能在逗号处截出「支付流水」；第一条截出来仍有十六个字，是描述不是名字，放弃。
        assertThat(result.suggestions()).extracting(AiGlossarySuggestions.Suggestion::term)
                .containsExactly("支付流水");
        assertThat(result.suggestions().get(0).description())
                .isEqualTo("支付流水，含微信和支付宝两个渠道");
    }

    @Test
    void stripsGenericSuffixesWithoutEatingShortNames() {
        assertThat(AiGlossarySuggestions.stripSuffixes("客户主档")).isEqualTo("客户");
        assertThat(AiGlossarySuggestions.stripSuffixes("订单明细表")).isEqualTo("订单");
        assertThat(AiGlossarySuggestions.stripSuffixes("商品")).isEqualTo("商品");
        // 剥到只剩一个字就没有检索价值了，保持原样。
        assertThat(AiGlossarySuggestions.stripSuffixes("单表")).isEqualTo("单表");
    }

    @Test
    void cutsTheCommentAtTheFirstSeparator() {
        assertThat(AiGlossarySuggestions.headline("支付流水（含退款）")).isEqualTo("支付流水");
        assertThat(AiGlossarySuggestions.headline("客户主档: 每日全量")).isEqualTo("客户主档");
        assertThat(AiGlossarySuggestions.headline("商品")).isEqualTo("商品");
    }

    @Test
    void honoursTheLimit() {
        var objects = new java.util.ArrayList<CatalogObject>();
        for (int index = 0; index < 30; index++) objects.add(table("T" + index, "业务实体" + index));

        assertThat(AiGlossarySuggestions.suggest(objects, Set.of(), Map.of(), 5).suggestions()).hasSize(5);
    }
}
