package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.ExportRequest;
import com.example.dbadmin.service.ExportService;
import com.example.dbadmin.service.SqlService;
import com.example.dbadmin.service.SqlTransactionService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlControllerTest {
    @Test
    void exposesTheActualPreparedExportTruncationState() throws Exception {
        ExportService exports = mock(ExportService.class);
        var constructor = ExportService.PreparedExport.class
                .getDeclaredConstructor(java.nio.file.Path.class, String.class, boolean.class, long.class);
        constructor.setAccessible(true);
        ExportService.PreparedExport prepared = constructor.newInstance(Files.createTempFile("controller-export-", ".csv"), "csv", true, 123L);
        when(exports.prepare(1L, "select * from events", "csv", "admin", null, "public", List.of("events")))
                .thenReturn(prepared);
        SqlController controller = new SqlController(
                mock(SqlService.class), exports, mock(SqlTransactionService.class),
                mock(com.example.dbadmin.access.ConnectionAccessService.class)
        );

        try {
            var response = controller.export(
                    new ExportRequest(1L, "select * from events", "csv", "public", List.of("events")),
                    "admin",
                    null
            );

            assertThat(response.getHeaders().getFirst("X-Export-Truncated")).isEqualTo("true");
            assertThat(response.getHeaders().getContentLength()).isEqualTo(123L);
        } finally {
            prepared.discard();
        }
    }
}
