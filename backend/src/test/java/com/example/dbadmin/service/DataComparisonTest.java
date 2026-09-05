package com.example.dbadmin.service;

import com.example.dbadmin.core.DefaultDialect;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataComparisonTest {
    private static final DefaultDialect DIALECT = new DefaultDialect();
    private static final List<String> COLUMNS = List.of("NAME", "AMOUNT");
    private static final List<String> KEYS = List.of("ID");

    @Test
    void countsEveryKindOfDifferenceSeparately() {
        var result = DataComparison.compare(
                rows(row("1", "张三", "100"), row("2", "李四", "200"), row("3", "王五", "300")),
                rows(row("1", "张三", "100"), row("2", "李四", "999"), row("4", "赵六", "400")),
                COLUMNS, 100);

        assertThat(result.identical()).isEqualTo(1);
        assertThat(result.different()).isEqualTo(1);
        assertThat(result.onlyInSource()).isEqualTo(1);
        assertThat(result.onlyInTarget()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        assertThat(result.differences()).extracting(DataComparison.Difference::change)
                .containsExactly(DataComparison.Change.DIFFERENT,
                        DataComparison.Change.ONLY_IN_SOURCE,
                        DataComparison.Change.ONLY_IN_TARGET);
    }

    /** 只报值不同的那几列：整行标红的话，用户还得自己一列列找。 */
    @Test
    void namesExactlyWhichColumnsChanged() {
        var result = DataComparison.compare(
                rows(row("1", "张三", "100")), rows(row("1", "张三", "999")), COLUMNS, 100);

        assertThat(result.differences()).singleElement()
                .extracting(DataComparison.Difference::columns).isEqualTo(List.of("AMOUNT"));
    }

    /** NULL 和空串是两回事：一个是「没有值」，一个是「值是空的」。 */
    @Test
    void treatsNullAndEmptyStringAsDifferent() {
        var result = DataComparison.compare(
                rows(row("1", null, "1")), rows(row("1", "", "1")), COLUMNS, 100);

        assertThat(result.different()).isEqualTo(1);
        assertThat(result.differences().get(0).columns()).containsExactly("NAME");
    }

    /** 复合主键拼接不能撞：("a","bc") 和 ("ab","c") 是两行。 */
    @Test
    void keepsCompositeKeysApart() {
        assertThat(DataComparison.keyOf(List.of("a", "bc")))
                .isNotEqualTo(DataComparison.keyOf(List.of("ab", "c")));
        assertThat(DataComparison.keyOf(Arrays.asList("a", null)))
                .isNotEqualTo(DataComparison.keyOf(List.of("a", "")));
    }

    @Test
    void stopsRecordingOnceTheDifferenceCapIsReached() {
        Map<String, DataComparison.Row> source = new LinkedHashMap<>();
        for (int index = 0; index < 10; index++) {
            var row = row(String.valueOf(index), "n" + index, "1");
            source.put(DataComparison.keyOf(row.key()), row);
        }

        var result = DataComparison.compare(source, Map.of(), COLUMNS, 3);

        assertThat(result.onlyInSource()).isEqualTo(10);
        assertThat(result.differences()).hasSize(3);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void writesInsertsForRowsMissingFromTheTarget() {
        var result = DataComparison.compare(rows(row("1", "张三", "100")), Map.of(), COLUMNS, 100);

        assertThat(DataComparison.syncScript(result, DIALECT, "\"shop\".\"orders\"", COLUMNS, KEYS, false))
                .containsExactly("INSERT INTO \"shop\".\"orders\" (\"NAME\", \"AMOUNT\") VALUES ('张三', '100');");
    }

    /** 更新只写不同的列：整行覆盖会把目标端那些不参与对比的字段连带改掉。 */
    @Test
    void writesUpdatesThatTouchOnlyTheChangedColumns() {
        var result = DataComparison.compare(
                rows(row("1", "张三", "100")), rows(row("1", "张三", "999")), COLUMNS, 100);

        assertThat(DataComparison.syncScript(result, DIALECT, "\"orders\"", COLUMNS, KEYS, false))
                .containsExactly("UPDATE \"orders\" SET \"AMOUNT\" = '100' WHERE \"ID\" = '1';");
    }

    /** 目标端多出来的行往往是目标库自己的数据，默认生成 DELETE 等于把对比变成危险操作。 */
    @Test
    void leavesTargetOnlyRowsAloneUnlessDeletesAreAskedFor() {
        var result = DataComparison.compare(Map.of(), rows(row("9", "多的", "1")), COLUMNS, 100);

        assertThat(DataComparison.syncScript(result, DIALECT, "\"orders\"", COLUMNS, KEYS, false)).isEmpty();
        assertThat(DataComparison.syncScript(result, DIALECT, "\"orders\"", COLUMNS, KEYS, true))
                .containsExactly("DELETE FROM \"orders\" WHERE \"ID\" = '9';");
    }

    /** {@code = NULL} 永远不成立：主键值为 NULL 时必须写成 IS NULL，否则那条语句改不到任何行。 */
    @Test
    void comparesNullKeysWithIsNull() {
        var row = new DataComparison.Row(Arrays.asList((String) null), List.of("张三", "100"));
        var result = DataComparison.compare(
                Map.of(DataComparison.keyOf(row.key()), row),
                Map.of(DataComparison.keyOf(row.key()), new DataComparison.Row(Arrays.asList((String) null), List.of("张三", "999"))),
                COLUMNS, 100);

        assertThat(DataComparison.syncScript(result, DIALECT, "\"orders\"", COLUMNS, KEYS, false))
                .containsExactly("UPDATE \"orders\" SET \"AMOUNT\" = '100' WHERE \"ID\" IS NULL;");
    }

    /** 值里的引号必须由方言转义 —— 一份同步脚本是先生成后执行的，这里漏了就是注入点。 */
    @Test
    void escapesValuesThroughTheDialect() {
        var result = DataComparison.compare(
                rows(row("1", "O'Brien'; DROP TABLE t;--", "1")), Map.of(), COLUMNS, 100);

        assertThat(DataComparison.syncScript(result, DIALECT, "\"orders\"", COLUMNS, KEYS, false).get(0))
                .contains("'O''Brien''; DROP TABLE t;--'");
    }

    private static Map<String, DataComparison.Row> rows(DataComparison.Row... rows) {
        Map<String, DataComparison.Row> map = new LinkedHashMap<>();
        for (DataComparison.Row row : rows) map.put(DataComparison.keyOf(row.key()), row);
        return map;
    }

    private static DataComparison.Row row(String id, String name, String amount) {
        return new DataComparison.Row(List.of(id), Arrays.asList(name, amount));
    }
}
