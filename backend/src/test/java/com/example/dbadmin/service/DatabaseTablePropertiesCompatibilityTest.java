package com.example.dbadmin.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static com.example.dbadmin.service.MySqlTablePropertiesService.*;

@Timeout(value=2, unit=TimeUnit.MINUTES, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseTablePropertiesCompatibilityTest {
    @ParameterizedTest @ValueSource(strings={"mysql", "mariadb"})
    void tableDefaultsAndAutoIncrementRoundTripWithoutConvertingExistingText(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type)) {
            var table = f.reserveTable();
            f.execute("CREATE TABLE " + f.q(table) + " (id BIGINT AUTO_INCREMENT PRIMARY KEY, note VARCHAR(80)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            f.execute("INSERT INTO " + f.q(table) + " (note) VALUES ('中文')");
            var service = new MySqlTablePropertiesService(f.connections, f.metadata, f.dialects, new MetadataCacheService(), f.guard, f.audit);
            var before = service.inspect(1, f.schema, table);
            assertThat(before.engine()).isEqualTo("InnoDB");
            assertThat(before.characterSet()).isEqualTo("utf8mb4");
            var change = new Change(f.schema, table, "InnoDB", "latin1", "latin1_swedish_ci", "100", before.version(), f.schema + "." + table);
            assertThat(service.preview(1, change).sql()).hasSize(1);
            service.execute(1, change, "ci", null);
            var after = service.inspect(1, f.schema, table);
            assertThat(after.characterSet()).isEqualTo("latin1");
            assertThat(after.collation()).isEqualTo("latin1_swedish_ci");
            f.execute("INSERT INTO " + f.q(table) + " (note) VALUES ('第二行')");
            assertThat(f.number("SELECT MAX(id) FROM " + f.q(table))).isEqualTo(100);
            assertThat(f.scalar("SELECT note FROM " + f.q(table) + " WHERE id=1")).isEqualTo("中文");
            assertThatThrownBy(() -> service.preview(1, change)).hasMessageContaining("已变化");
            var latest = service.inspect(1, f.schema, table);
            if (latest.engines().contains("MyISAM")) {
                var engineChange = new Change(f.schema, table, "MyISAM", latest.characterSet(), latest.collation(), latest.autoIncrement(), latest.version(), f.schema + "." + table);
                service.execute(1, engineChange, "ci", null);
                assertThat(service.inspect(1, f.schema, table).engine()).isEqualTo("MyISAM");
                assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isEqualTo(2);
            }
        }
    }
}
