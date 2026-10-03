package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseServerInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@Timeout(value=2, unit=TimeUnit.MINUTES, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseOceanBaseCompatibilityTest {
    @ParameterizedTest @ValueSource(strings={"oceanbase-mysql", "oceanbase-oracle"})
    void verifiesTenantAndModeAndRejectsWrongDialect(String type) throws Exception {
        try (var fixture = new DatabaseCompatibilityTest.Fixture(type)) {
            var info = DatabaseServerInfo.inspect(fixture.jdbc, type);
            assertThat(info.product()).isEqualTo("OceanBase");
            assertThat(info.typeMatches()).isTrue();
            assertThat(info.tenantId()).isNotBlank();
            assertThat(info.tenantName()).isNotBlank();
            assertThat(info.version()).isNotBlank();
            String other = type.equals("oceanbase-mysql") ? "oceanbase-oracle" : "oceanbase-mysql";
            assertThatThrownBy(() -> DatabaseServerInfo.validateOceanBase(fixture.jdbc, other)).hasMessageContaining("不一致");
            var table = fixture.table(fixture.pk("id"));
            assertThat(fixture.dialect.explain(fixture.jdbc, "SELECT * FROM " + fixture.q(table), 100, 10).rows()).isNotEmpty();
        }
    }
}
