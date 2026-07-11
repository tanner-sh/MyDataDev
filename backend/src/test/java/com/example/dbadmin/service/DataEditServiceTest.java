package com.example.dbadmin.service;

import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DataPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.RowChange;
import com.example.dbadmin.dto.ApiDtos.TableDataResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataEditServiceTest {
    @Test
    void browsesMySqlTableWithServerSideHasMorePagination() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE cash_ledger(id INT PRIMARY KEY, amount DECIMAL(12, 2))");
            connection.createStatement().execute("INSERT INTO cash_ledger VALUES (1, 10), (2, 20), (3, 30), (4, 40), (5, 50)");
        }
        DataEditService service = service(url);

        TableDataResponse middle = service.table(1L, null, "cash_ledger", 1, 2);
        TableDataResponse last = service.table(1L, null, "cash_ledger", 2, 2);

        assertThat(middle.page()).isEqualTo(1);
        assertThat(middle.pageSize()).isEqualTo(2);
        assertThat(middle.rows()).hasSize(2);
        assertThat(middle.hasMore()).isTrue();
        assertThat(middle.editable()).isTrue();
        assertThat(last.rows()).hasSize(1);
        assertThat(last.hasMore()).isFalse();
    }

    @Test
    void buildsMySqlEditSqlWithBackticksAndNullSafeKeys() throws Exception {
        DataEditService service = service("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("amount", 88);
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("external_id", null);

        var response = service.preview(new DataPreviewRequest(
                1L,
                "trading",
                "cash_ledger",
                List.of(new RowChange("UPDATE", key, values))
        ));

        assertThat(response.sql()).containsExactly(
                "UPDATE `trading`.`cash_ledger` SET `amount` = 88 WHERE `external_id` IS NULL;"
        );
    }

    private DataEditService service(String url) throws Exception {
        MetadataService metadata = mock(MetadataService.class);
        when(metadata.primaryOrUniqueColumns(anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of("id"));
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(new DbConnection(
                1L, "mysql", "mysql", url, "sa", "", "dev", false, Instant.now(), Instant.now()
        ));
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        return new DataEditService(metadata, connections, mock(AuditRepository.class), new DialectRegistry());
    }
}
