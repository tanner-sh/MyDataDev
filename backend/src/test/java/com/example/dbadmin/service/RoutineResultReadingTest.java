package com.example.dbadmin.service;

import com.example.dbadmin.core.H2Dialect;
import com.example.dbadmin.dto.ApiDtos.RoutineResultItem;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 例程返回的结果集与 SQL 工作台读的是同一份 {@link SqlResultReader}：同样的上限、同样的截断标记与原因。
 * 例程结果就渲染在同一张结果网格里，两边说法不一致时，用户会把截掉一半的值当成完整值。
 */
class RoutineResultReadingTest {

    @Test
    void marksCellTextTruncationInsteadOfPresentingACutValueAsComplete() throws Exception {
        SqlResult result = readSingle("SELECT REPEAT('a', 150000) AS long_text");

        assertThat((String) result.rows().get(0).get(0)).hasSize(100_000);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("text_limit");
    }

    @Test
    void capsRowsAtFiveHundredAndSaysWhy() throws Exception {
        SqlResult result = readSingle("SELECT X FROM SYSTEM_RANGE(1, 600)");

        assertThat(result.rows()).hasSize(500);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("row_limit");
    }

    @Test
    void describesBinaryColumnsInsteadOfSendingTheirBytes() throws Exception {
        SqlResult result = readSingle("SELECT CAST(X'010203' AS VARBINARY) AS payload");

        assertThat(result.rows().get(0).get(0)).isEqualTo("<BINARY 3 bytes>");
        assertThat(result.truncated()).isFalse();
        assertThat(result.truncationReason()).isEqualTo("none");
    }

    @Test
    void reportsUpdateCountsAlongsideResultSets() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE T (ID INT)");
            boolean hasResult = statement.execute("INSERT INTO T VALUES (1), (2)");

            List<RoutineResultItem> items = RoutineInvocationService.readResults(statement, hasResult, System.nanoTime(), new H2Dialect());

            assertThat(items).singleElement().satisfies(item -> {
                assertThat(item.kind()).isEqualTo("UPDATE_COUNT");
                assertThat(item.updateCount()).isEqualTo(2);
            });
        }
    }

    private static SqlResult readSingle(String sql) throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            List<RoutineResultItem> items = RoutineInvocationService.readResults(statement, statement.execute(sql), System.nanoTime(), new H2Dialect());
            assertThat(items).singleElement().extracting(RoutineResultItem::kind).isEqualTo("RESULT_SET");
            return items.get(0).result();
        }
    }

    @Test
    void oracleRefCursorParametersAreRecognisedByTypeName() {
        // Oracle 目录给 SYS_REFCURSOR 的是 REF CURSOR + OTHER(1111)；按 1111 注册 OUT 参数会报 ORA-17004。
        assertThat(RoutineInvocationService.isCursor(parameter("REF CURSOR", java.sql.Types.OTHER))).isTrue();
        assertThat(RoutineInvocationService.isCursor(parameter("SYS_REFCURSOR", null))).isTrue();
        assertThat(RoutineInvocationService.isCursor(parameter("refcursor", java.sql.Types.REF_CURSOR))).isTrue();
        assertThat(RoutineInvocationService.isCursor(parameter("NUMBER", java.sql.Types.NUMERIC))).isFalse();
        assertThat(RoutineInvocationService.isCursor(parameter("jsonb", java.sql.Types.OTHER))).isFalse();
    }

    private static com.example.dbadmin.dto.ApiDtos.SchemaObjectParameter parameter(String type, Integer jdbcType) {
        return new com.example.dbadmin.dto.ApiDtos.SchemaObjectParameter(1, "p", "OUT", type, jdbcType, true);
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
    }
}
