package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlParameter;
import com.example.dbadmin.dto.ApiDtos.SqlParameterizedRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqlParameterizedExecutionTest {
    final ConnectionService connections = mock(ConnectionService.class);
    final SqlHistoryRepository history = mock(SqlHistoryRepository.class);
    final AuditRepository audit = mock(AuditRepository.class);
    final SqlExecutionRegistry executions = new SqlExecutionRegistry();
    SqlService service(String environment) throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        when(connections.open(1L)).thenAnswer(invocation -> DriverManager.getConnection(url));
        when(connections.require(1L)).thenReturn(new DbConnection(1L, "test-db", "h2", url, "sa", "", environment, true, Instant.now(), Instant.now()));
        return new SqlService(connections, new AppProperties(), audit, new DialectRegistry(), history, mock(MetadataService.class),
                new SqlScriptSplitter(), new SqlStatementClassifier(), new ExecutionGuard(), executions, mock(DataEditService.class), new SqlExecutionMetrics());
    }
    SqlParameterizedRequest request(String sql) {
        return new SqlParameterizedRequest(1L, sql, 2, "22222222-2222-4222-8222-222222222222", null, Map.of("value", new SqlParameter("TEXT", "secret'; --")));
    }
    @Test void realPreparedExecutionUsesExistingLimitsAndOnlyRecordsTemplate() throws Exception {
        var result = service("dev").executeParameterized(request("select cast(:value as varchar) from system_range(1, 3)"), "admin", null);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsExactly("secret'; --");
        assertThat(result.truncated()).isTrue();
        assertThat(result.edit()).isNull();
        verify(history).insert(eq(1L), eq("select cast(:value as varchar) from system_range(1, 3)"), eq("EXECUTE"), eq("SUCCESS"), anyLong(), isNull(), eq("admin"));
        verify(audit).onConnection(eq("admin"), eq("SQL_EXECUTE"), eq(1L), eq("select cast(:value as varchar) from system_range(1, 3)"));
        assertThat(executions.cancel("22222222-2222-4222-8222-222222222222")).isFalse();
    }
    @Test void rejectsWriteAndMultiStatementBeforeOpeningConnection() throws Exception {
        var service = service("dev");
        assertThatThrownBy(() -> service.executeParameterized(request("delete from users where name=:value"), "admin", null)).hasMessageContaining("SELECT");
        assertThatThrownBy(() -> service.executeParameterized(request("select :value; delete from users"), "admin", null)).hasMessageContaining("一条 SQL");
        verify(connections, never()).open(anyLong());
    }
    @Test void keepsProductionConfirmationAndSanitizesFailureHistory() throws Exception {
        var service = service("prod");
        assertThatThrownBy(() -> service.executeParameterized(request("select :value"), "admin", null)).isInstanceOf(RuntimeException.class);
        verify(connections, never()).open(anyLong());
        assertThatThrownBy(() -> service.executeParameterized(request("select cast(:value as integer)"), "admin", "test-db")).isInstanceOf(Exception.class);
        verify(history).insert(eq(1L), eq("select cast(:value as integer)"), eq("EXECUTE"), eq("FAILED"), anyLong(), eq("参数查询执行失败（参数值不记录）"), eq("admin"));
        assertThat(executions.cancel("22222222-2222-4222-8222-222222222222")).isFalse();
    }
}
