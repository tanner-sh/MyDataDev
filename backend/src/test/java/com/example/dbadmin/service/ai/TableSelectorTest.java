package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableSelectorTest {
    private static final List<String> CATALOG = List.of(
            "orders", "order_item", "order_sync_temp_20231001", "users", "user_login_log",
            "payment", "inventory_snapshot", "shipping_address", "coupon", "t_config"
    );

    @Test
    void picksTablesNamedInTheQuestion() {
        assertThat(TableSelector.select("orders 表里上周有多少订单", CATALOG)).contains("orders");
    }

    /** 同分时短名优先：order_sync_temp_20231001 几乎不会是用户嘴里的「订单表」。 */
    @Test
    void prefersTheShorterNameWhenScoresTie() {
        List<String> selected = TableSelector.select("统计 order 的数量", CATALOG);

        assertThat(selected.indexOf("orders")).isLessThan(selected.indexOf("order_sync_temp_20231001"));
    }

    @Test
    void matchesOnNameTokensNotJustWholeNames() {
        assertThat(TableSelector.select("查一下 user login 的记录", CATALOG)).contains("user_login_log");
    }

    @Test
    void matchesChineseTableNamesByContainment() {
        List<String> chinese = List.of("订单明细", "用户", "优惠券");

        assertThat(TableSelector.select("订单明细里金额最大的十条", chinese)).containsExactly("订单明细");
    }

    /** 选不出来就一张都不给：让模型明说「看不到相关的表」，好过拿八张无关表编一条 SQL。 */
    @Test
    void returnsNothingWhenNoTableLooksRelevant() {
        assertThat(TableSelector.select("今天天气怎么样", CATALOG)).isEmpty();
    }

    @Test
    void capsTheNumberOfTables() {
        List<String> many = new ArrayList<>();
        for (int index = 0; index < 50; index++) many.add("order_part_" + index);

        assertThat(TableSelector.select("order 的数据", many)).hasSize(TableSelector.MAX_TABLES);
    }

    @Test
    void ignoresGenericNameNoise() {
        List<String> tables = List.of("t_config", "data_info");

        assertThat(TableSelector.select("config 表", tables)).containsExactly("t_config");
    }

    @Test
    void toleratesEmptyInput() {
        assertThat(TableSelector.select("", CATALOG)).isEmpty();
        assertThat(TableSelector.select("orders", List.of())).isEmpty();
        assertThat(TableSelector.select(null, CATALOG)).isEmpty();
    }
}
