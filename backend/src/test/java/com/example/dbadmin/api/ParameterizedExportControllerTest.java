package com.example.dbadmin.api;
import com.example.dbadmin.access.*;
import com.example.dbadmin.dto.ApiDtos.*;
import com.example.dbadmin.service.*;
import org.junit.jupiter.api.Test;
import java.sql.Statement;
import java.util.Map;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ParameterizedExportControllerTest {
    private final ConnectionAccessService access = mock(ConnectionAccessService.class);
    private final ExportService exports = mock(ExportService.class);
    private final SqlExecutionRegistry executions = new SqlExecutionRegistry();
    private final ParameterizedExportController controller = new ParameterizedExportController(exports, access, executions);
    private final String id = "33333333-3333-4333-8333-333333333333";
    private final ExportRequest request = new ExportRequest(1L, "SELECT :value", "csv", null, null,
            Map.of("value", new SqlParameter("TEXT", "secret")), true, id);
    @Test void requiresExportPermissionBeforeOpeningDatabase() throws Exception {
        doThrow(new IllegalArgumentException("denied")).when(access).require(1L, ConnectionPermission.EXPORT);
        assertThatThrownBy(() -> controller.export(request, "user", null)).hasMessage("denied");
        verify(access).require(1L, ConnectionPermission.QUERY);
        verifyNoInteractions(exports);
    }
    @Test void registersCancellableStatementAndUnregistersAfterFailure() throws Exception {
        Statement statement = mock(Statement.class);
        when(exports.prepareParameterized(eq(request), eq("user"), isNull(), any())).thenAnswer(call -> {
            Consumer<Statement> callback = call.getArgument(3);
            callback.accept(statement);
            assertThat(executions.connectionId(id)).hasValue(1L);
            assertThat(executions.cancel(id)).isTrue();
            throw new java.sql.SQLException("cancelled");
        });
        assertThatThrownBy(() -> controller.export(request, "user", null)).hasMessage("cancelled");
        verify(statement).cancel();
        assertThat(executions.connectionId(id)).isEmpty();
    }
}
