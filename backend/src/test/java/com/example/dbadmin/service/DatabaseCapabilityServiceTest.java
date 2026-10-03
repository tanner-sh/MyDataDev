package com.example.dbadmin.service;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.core.DatabaseServerInfo;
import com.example.dbadmin.core.MySqlDialect;
import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class DatabaseCapabilityServiceTest {
    @Test void distinguishesPermissionReadOnlyAndUnverifiedObjectRights() {
        var db = new DbConnection(1, "test", "mysql", "jdbc:mysql://localhost/test", "user", "", "dev", true, Instant.now(), Instant.now());
        var info = new DatabaseServerInfo("MySQL", "8", "driver", null, null, null, null, List.of());
        var result = DatabaseCapabilityService.base(db, new MySqlDialect().capabilities(), info, List.of(ConnectionPermission.QUERY, ConnectionPermission.DATA_WRITE));
        assertThat(result).filteredOn(f -> f.key().equals("tableEdit")).allSatisfy(f -> { assertThat(f.status()).isEqualTo("UNAVAILABLE"); assertThat(f.reason()).contains("只读"); });
        assertThat(result).filteredOn(f -> f.key().equals("tableDesign")).allSatisfy(f -> assertThat(f.reason()).contains("DDL"));
        assertThat(result).filteredOn(f -> f.key().equals("explain")).allSatisfy(f -> assertThat(f.status()).isEqualTo("UNVERIFIED"));
    }
    @Test void unknownOceanBaseModeDisablesEveryOperation() {
        var db = new DbConnection(1, "test", "oceanbase-mysql", "jdbc:oceanbase://localhost/test", "user", "", "dev", false, Instant.now(), Instant.now());
        var info = new DatabaseServerInfo("OceanBase", "4", "driver", null, null, null, null, List.of());
        assertThat(DatabaseCapabilityService.base(db, new MySqlDialect().capabilities(), info, List.of(ConnectionPermission.CONNECTION_ADMIN)))
                .allSatisfy(f -> assertThat(f.status()).isEqualTo("UNAVAILABLE"));
    }
}
