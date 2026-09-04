package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaContextFormatTest {
    private static SchemaContext.Table table(List<List<String>> samples) {
        return new SchemaContext.Table(
                "shop",
                "orders",
                List.of(
                        new SchemaContext.Column("id", "BIGINT", false, null),
                        new SchemaContext.Column("status", "VARCHAR(20)", true, "订单状态")
                ),
                List.of("id"),
                List.of("UNIQUE uk_orders_no(order_no)"),
                samples
        );
    }

    @Test
    void rendersColumnsPrimaryKeysAndIndexes() {
        String text = SchemaContextFormat.render(new SchemaContext("MySQL", "shop", List.of(table(List.of())), false));

        assertThat(text).contains("数据库类型：MySQL");
        assertThat(text).contains("表 shop.orders");
        assertThat(text).contains("id BIGINT NOT NULL");
        assertThat(text).contains("status VARCHAR(20) -- 订单状态");
        assertThat(text).contains("主键：id");
        assertThat(text).contains("索引：UNIQUE uk_orders_no(order_no)");
    }

    /** 只结构档的上下文里绝不能出现任何行数据，这是对用户的承诺，所以正面测它。 */
    @Test
    void rendersNoSampleSectionWhenThereAreNoSampleRows() {
        String text = SchemaContextFormat.render(new SchemaContext("MySQL", "shop", List.of(table(List.of())), false));

        assertThat(text).doesNotContain("样本行");
    }

    @Test
    void rendersSampleRowsWhenTheyWereCollected() {
        String text = SchemaContextFormat.render(new SchemaContext(
                "MySQL", "shop", List.of(table(List.of(List.of("1", "PAID"), List.of("2", "NEW")))), false));

        assertThat(text).contains("样本行（2 行）");
        assertThat(text).contains("1 | PAID");
    }

    @Test
    void capsColumnsPerTableAndSaysSo() {
        List<SchemaContext.Column> columns = new ArrayList<>();
        for (int index = 0; index < SchemaContextFormat.MAX_COLUMNS_PER_TABLE + 5; index++) {
            columns.add(new SchemaContext.Column("c" + index, "INT", true, null));
        }
        SchemaContext.Table wide = new SchemaContext.Table("shop", "wide", columns, List.of(), List.of(), List.of());

        String text = SchemaContextFormat.render(new SchemaContext("MySQL", "shop", List.of(wide), false));

        assertThat(text).contains("另有 5 列未列出");
        assertThat(text).doesNotContain("c" + (SchemaContextFormat.MAX_COLUMNS_PER_TABLE + 1) + " INT");
    }

    @Test
    void marksTruncationWhenSomeTablesWereDropped() {
        String text = SchemaContextFormat.render(new SchemaContext("MySQL", "shop", List.of(table(List.of())), true));

        assertThat(text).contains("还有更多表未列出");
    }

    @Test
    void rendersNothingForAnEmptyContext() {
        assertThat(SchemaContextFormat.render(SchemaContext.empty("MySQL", "shop"))).isEmpty();
        assertThat(SchemaContextFormat.render(null)).isEmpty();
    }
}
