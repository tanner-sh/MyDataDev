package com.example.dbadmin.service;

import com.example.dbadmin.core.DefaultDialect;
import com.example.dbadmin.core.MySqlDialect;
import com.example.dbadmin.core.OracleDialect;
import com.example.dbadmin.dto.ApiDtos.SqlResultFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlResultPushdownTest {
    private static final DefaultDialect DEFAULT = new DefaultDialect();

    @Test
    void wrapsTheQueryInsteadOfAppendingToIt() {
        var shaped = SqlResultPushdown.apply("SELECT id, name FROM app_user", List.of(), "name", "DESC", DEFAULT);

        assertThat(shaped.sql()).isEqualTo(
                "SELECT * FROM (SELECT id, name FROM app_user) mdd_view ORDER BY \"name\" DESC");
        assertThat(shaped.parameters()).isEmpty();
    }

    /** 原查询自己带 ORDER BY 时，往末尾追加只会变成语法错误 —— 包一层才是对的。 */
    @Test
    void keepsAnExistingOrderByIntact() {
        var shaped = SqlResultPushdown.apply("SELECT id FROM t ORDER BY id", List.of(), "id", "ASC", DEFAULT);

        assertThat(shaped.sql()).startsWith("SELECT * FROM (SELECT id FROM t ORDER BY id) mdd_view ORDER BY");
    }

    /** 工作台里语句带分号是常态，而带分号的子查询是语法错误。 */
    @Test
    void stripsTrailingSemicolons() {
        assertThat(SqlResultPushdown.apply("SELECT 1;  ", List.of(), "c", null, DEFAULT).sql())
                .isEqualTo("SELECT * FROM (SELECT 1) mdd_view ORDER BY \"c\" ASC");
    }

    /** 列标签来自结果集，可能带引号或空格；转义交给方言，两家的引用符本来就不同。 */
    @Test
    void quotesTheColumnWithTheDialectsOwnRules() {
        assertThat(SqlResultPushdown.apply("SELECT 1", List.of(), "we\"ird", "ASC", DEFAULT).sql())
                .contains("\"we\"\"ird\"");
        assertThat(SqlResultPushdown.apply("SELECT 1", List.of(), "订单 金额", "ASC", new MySqlDialect()).sql())
                .contains("`订单 金额`");
    }

    @Test
    void leavesTheQueryAloneWhenThereIsNothingToPushDown() {
        var shaped = SqlResultPushdown.apply("SELECT 1", List.of(), null, "DESC", DEFAULT);

        assertThat(shaped.sql()).isEqualTo("SELECT 1");
        assertThat(shaped.hasParameters()).isFalse();
    }

    @Test
    void acceptsOnlyTheTwoDirections() {
        assertThat(SqlResultPushdown.normalizeDirection(null)).isEqualTo("ASC");
        assertThat(SqlResultPushdown.normalizeDirection(" desc ")).isEqualTo("DESC");
        assertThatThrownBy(() -> SqlResultPushdown.normalizeDirection("ASC; DROP TABLE t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ASC 或 DESC");
    }

    /** 控制字符不可能出现在结果集列标签里，出现了说明这个参数不是从界面来的。 */
    @Test
    void refusesColumnNamesThatCannotComeFromAResultSet() {
        assertThatThrownBy(() -> SqlResultPushdown.apply("SELECT 1", List.of(), "a\nb", "ASC", DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlResultPushdown.apply("SELECT 1", List.of(), "x".repeat(200), "ASC", DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");
    }

    /** 筛选值绝不拼进 SQL：列名靠引用转义，值来自输入框，拼进去就是注入点。 */
    @Test
    void bindsFilterValuesInsteadOfInliningThem() {
        var shaped = SqlResultPushdown.apply("SELECT * FROM t",
                List.of(new SqlResultFilter("name", "equals", "O'Brien'; DROP TABLE t;--")), null, null, DEFAULT);

        assertThat(shaped.sql()).doesNotContain("O'Brien").endsWith("= ?");
        assertThat(shaped.parameters()).containsExactly("o'brien'; drop table t;--");
    }

    /** 列可能是数字、时间或布尔，而筛选是文本语义 —— 转文本的写法交给方言。 */
    @Test
    void castsTheColumnToTextWithTheDialectsOwnSyntax() {
        var filters = List.of(new SqlResultFilter("amount", "contains", "12"));

        assertThat(SqlResultPushdown.apply("SELECT 1", filters, null, null, new MySqlDialect()).sql())
                .contains("LOWER(COALESCE(CAST(`amount` AS CHAR), ''))");
        assertThat(SqlResultPushdown.apply("SELECT 1", filters, null, null, new OracleDialect()).sql())
                .contains("TO_CHAR(\"amount\")");
    }

    /** 用户输入的 % 与 _ 是字面量，不是通配符 —— 前端的「包含」一直是这个语义。 */
    @Test
    void escapesLikeWildcardsInsideTheValue() {
        var shaped = SqlResultPushdown.apply("SELECT 1",
                List.of(new SqlResultFilter("note", "contains", "50%_off")), null, null, DEFAULT);

        assertThat(shaped.parameters()).containsExactly("%50/%/_off%");
        assertThat(shaped.sql()).contains("LIKE ? ESCAPE '/'");
    }

    /** NULL 与空串同样落进「为空」：前端此前就是这个行为，下推不该悄悄改掉它。 */
    @Test
    void treatsNullAndEmptyStringAlikeForEmptinessFilters() {
        assertThat(SqlResultPushdown.apply("SELECT 1",
                List.of(new SqlResultFilter("note", "empty", null)), null, null, DEFAULT).sql())
                .contains("COALESCE(").endsWith("= ''");
        assertThat(SqlResultPushdown.apply("SELECT 1",
                List.of(new SqlResultFilter("note", "notEmpty", null)), null, null, DEFAULT).sql())
                .endsWith("<> ''");
    }

    @Test
    void combinesEveryFilterWithAndThenSorts() {
        var shaped = SqlResultPushdown.apply("SELECT * FROM t", List.of(
                new SqlResultFilter("a", "contains", "x"),
                new SqlResultFilter("b", "notEquals", "y")), "c", "DESC", DEFAULT);

        assertThat(shaped.sql()).contains(" WHERE ").contains(" AND ").endsWith("ORDER BY \"c\" DESC");
        assertThat(shaped.parameters()).containsExactly("%x%", "y");
    }

    @Test
    void refusesUnknownOperatorsAndTooManyFilters() {
        assertThatThrownBy(() -> SqlResultPushdown.apply("SELECT 1",
                List.of(new SqlResultFilter("a", "regex", "x")), null, null, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的筛选操作");

        List<SqlResultFilter> many = new java.util.ArrayList<>();
        for (int index = 0; index < SqlResultPushdown.MAX_FILTERS + 1; index++) {
            many.add(new SqlResultFilter("c" + index, "equals", "v"));
        }
        assertThatThrownBy(() -> SqlResultPushdown.apply("SELECT 1", many, null, null, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("筛选条件过多");
    }
}
