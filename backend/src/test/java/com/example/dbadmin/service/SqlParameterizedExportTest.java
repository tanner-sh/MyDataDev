package com.example.dbadmin.service;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class SqlParameterizedExportTest {
    @Test void fullExportExceedsLoadedPageAndLegacyLimitWithoutPersistingValues() throws Exception {
        var fixture = new SqlParameterizedExecutionTest(); fixture.service("dev");
        var exports = new ExportService(fixture.connections, new DialectRegistry(), new AppProperties(), new ObjectMapper(),
                new SqlStatementClassifier(), new SqlScriptSplitter(), fixture.audit, fixture.history, new ExecutionGuard());
        String sql = "SELECT X, CAST(:text AS VARCHAR) AS NOTE FROM SYSTEM_RANGE(1,10005) WHERE X >= :min";
        var parameters = Map.of("text", new SqlParameter("TEXT", "secret'; --"), "min", new SqlParameter("INTEGER", "1"));
        var prepared = exports.prepareParameterized(new ExportRequest(1L, sql, "csv", null, null, parameters, true, null), "admin", null, statement -> { assertThat(statement).isInstanceOf(java.sql.PreparedStatement.class); try { assertThat(statement.getMaxRows()).isZero(); } catch (java.sql.SQLException error) { throw new RuntimeException(error); } });
        var output = new ByteArrayOutputStream(); prepared.writeTo(output);
        String csv = output.toString(StandardCharsets.UTF_8);
        assertThat(csv.lines().count()).isEqualTo(10006);
        assertThat(csv).contains("\"10005\",\"secret'; --\"");
        assertThat(prepared.truncated()).isFalse();
        verify(fixture.audit).onConnection("admin", "SQL_EXPORT", 1L, sql);
        verify(fixture.history).insert(eq(1L), argThat(value -> !value.contains("secret")), eq("EXPORT_CSV"), eq("SUCCESS"), anyLong(), isNull(), eq("admin"));
        var limited = exports.prepareParameterized(new ExportRequest(1L, sql, "csv", null, null, parameters, false, null), "admin", null, statement -> {});
        limited.writeTo(new ByteArrayOutputStream());
        assertThat(limited.truncated()).isTrue();
    }
    @Test void rejectsMissingParametersAndWritesAndRedactsDriverErrors() throws Exception {
        var fixture = new SqlParameterizedExecutionTest(); fixture.service("dev");
        var exports = new ExportService(fixture.connections, new DialectRegistry(), new AppProperties(), new ObjectMapper(),
                new SqlStatementClassifier(), new SqlScriptSplitter(), fixture.audit, fixture.history, new ExecutionGuard());
        var values = Map.of("value", new SqlParameter("TEXT", "sensitive-value"));
        for (String sql : List.of("DELETE FROM data WHERE name=:value", "SELECT :missing", "SELECT :value; SELECT 2"))
            assertThatThrownBy(() -> exports.prepareParameterized(new ExportRequest(1L, sql, "csv", null, null, values, true, null), "admin", null, statement -> {})).isInstanceOf(IllegalArgumentException.class);
        verify(fixture.connections, never()).open(anyLong());
        assertThatThrownBy(() -> exports.prepareParameterized(new ExportRequest(1L, "SELECT CAST(:value AS INTEGER)", "csv", null, null, values, true, null), "admin", null, statement -> {})).isInstanceOf(java.sql.SQLException.class);
        verify(fixture.history).insert(eq(1L), anyString(), eq("EXPORT_CSV"), eq("FAILED"), anyLong(), eq("参数导出失败（参数值不记录）"), eq("admin"));
    }
}
