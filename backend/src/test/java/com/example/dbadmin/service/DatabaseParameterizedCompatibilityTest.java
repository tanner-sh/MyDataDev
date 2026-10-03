package com.example.dbadmin.service;
import com.example.dbadmin.dto.ApiDtos.*;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
@Timeout(value=2, unit=TimeUnit.MINUTES, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseParameterizedCompatibilityTest {
    @ParameterizedTest @ValueSource(strings={"mysql", "mariadb", "oracle", "dm", "oceanbase-mysql", "oceanbase-oracle", "postgresql", "sqlserver"})
    void boundValuesSurvivePagingFiltersAndExport(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type)) {
            String table = f.table(f.pk("id") + ", " + f.varchar("note", 100));
            String text = "中文 O'Reilly; --";
            try (var statement = f.jdbc.prepareStatement("INSERT INTO " + f.q(table) + " VALUES (?, ?)")) {
                for (int i=1; i<=11; i++) { statement.setInt(1, i); statement.setString(2, text); statement.addBatch(); }
                statement.executeBatch();
            }
            var history = mock(SqlHistoryRepository.class);
            var service = new SqlService(f.connections, f.properties, f.audit, f.dialects, history, f.metadata,
                    new SqlScriptSplitter(), new SqlStatementClassifier(), f.guard, new SqlExecutionRegistry(), f.edits, new SqlExecutionMetrics());
            String sql = "SELECT id, note FROM " + f.q(table) + " WHERE id >= :min AND :min > 0 AND note=:note ORDER BY id";
            var parameters = Map.of("min", new SqlParameter("INTEGER", "2"), "note", new SqlParameter("TEXT", text));
            var first = service.executeParameterized(new SqlParameterizedRequest(1L, sql, null, null, f.schema, parameters, 3), "ci", null);
            assertThat(first.rows()).hasSize(3);
            assertThat(first.page().hasMore()).isTrue();
            assertThat(first.edit()).isNull();
            if (type.equals("sqlserver")) {
                var cte = service.executeParameterized(new SqlParameterizedRequest(1L,
                        "WITH source AS (SELECT id FROM " + f.q(table) + " WHERE id >= :min) SELECT * FROM source ORDER BY id",
                        3, null, f.schema, Map.of("min", parameters.get("min")), 3), "ci", null);
                assertThat(cte.rows()).hasSize(3);
                assertThat(cte.page()).isNull();
                assertThat(cte.truncated()).isTrue();
            }
            var second = service.executePage(1L, sql, 3, 3, "ci", null, null, f.schema, f.col("id"), "DESC",
                    List.of(new SqlResultFilter(f.col("note"), "equals", text)), parameters);
            assertThat(second.rows()).hasSize(3);
            assertThat(second.rows().get(0).get(0).toString()).isEqualTo("8");
            var exports = new ExportService(f.connections, f.dialects, f.properties, f.mapper, new SqlStatementClassifier(),
                    new SqlScriptSplitter(), f.audit, history, f.guard);
            var export = exports.prepareParameterized(new ExportRequest(1L, sql, "csv", f.schema, null, parameters, true, null), "ci", null, statement -> {});
            var output = new ByteArrayOutputStream(); export.writeTo(output);
            assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8).lines().count()).isEqualTo(11);
            assertThat(export.truncated()).isFalse();
        }
    }
}
