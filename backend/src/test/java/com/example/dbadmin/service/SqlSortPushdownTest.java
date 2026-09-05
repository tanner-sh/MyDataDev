package com.example.dbadmin.service;

import com.example.dbadmin.core.DefaultDialect;
import com.example.dbadmin.core.MySqlDialect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSortPushdownTest {
    private static final DefaultDialect DEFAULT = new DefaultDialect();

    @Test
    void wrapsTheQueryInsteadOfAppendingToIt() {
        String sorted = SqlSortPushdown.apply("SELECT id, name FROM app_user", "name", "DESC", DEFAULT);

        assertThat(sorted).isEqualTo(
                "SELECT * FROM (SELECT id, name FROM app_user) mdd_sorted ORDER BY \"name\" DESC");
    }

    /** 原查询自己带 ORDER BY 时，往末尾追加只会变成语法错误 —— 包一层才是对的。 */
    @Test
    void keepsAnExistingOrderByIntact() {
        String sorted = SqlSortPushdown.apply("SELECT id FROM t ORDER BY id", "id", "ASC", DEFAULT);

        assertThat(sorted).startsWith("SELECT * FROM (SELECT id FROM t ORDER BY id) mdd_sorted ORDER BY");
    }

    /** 工作台里语句带分号是常态，而带分号的子查询是语法错误。 */
    @Test
    void stripsTrailingSemicolons() {
        assertThat(SqlSortPushdown.apply("SELECT 1;  ", "c", null, DEFAULT))
                .isEqualTo("SELECT * FROM (SELECT 1) mdd_sorted ORDER BY \"c\" ASC");
    }

    /** 列标签来自结果集，可能带引号或空格；转义交给方言，两家的引用符本来就不同。 */
    @Test
    void quotesTheColumnWithTheDialectsOwnRules() {
        assertThat(SqlSortPushdown.apply("SELECT 1", "we\"ird", "ASC", DEFAULT)).contains("\"we\"\"ird\"");
        assertThat(SqlSortPushdown.apply("SELECT 1", "订单 金额", "ASC", new MySqlDialect()))
                .contains("`订单 金额`");
    }

    @Test
    void leavesTheQueryAloneWhenNoColumnIsGiven() {
        assertThat(SqlSortPushdown.apply("SELECT 1", null, "DESC", DEFAULT)).isEqualTo("SELECT 1");
        assertThat(SqlSortPushdown.apply("SELECT 1", "  ", null, DEFAULT)).isEqualTo("SELECT 1");
    }

    @Test
    void acceptsOnlyTheTwoDirections() {
        assertThat(SqlSortPushdown.normalizeDirection(null)).isEqualTo("ASC");
        assertThat(SqlSortPushdown.normalizeDirection(" desc ")).isEqualTo("DESC");
        assertThatThrownBy(() -> SqlSortPushdown.normalizeDirection("ASC; DROP TABLE t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ASC 或 DESC");
    }

    /** 控制字符不可能出现在结果集列标签里，出现了说明这个参数不是从界面来的。 */
    @Test
    void refusesColumnNamesThatCannotComeFromAResultSet() {
        assertThatThrownBy(() -> SqlSortPushdown.apply("SELECT 1", "a\nb", "ASC", DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSortPushdown.apply("SELECT 1", "x".repeat(200), "ASC", DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");
    }
}
