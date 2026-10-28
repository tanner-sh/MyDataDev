package com.example.dbadmin.api;

import com.example.dbadmin.service.RemoteDataSourceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:remote-pool-settings-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.crypto-key-file=target/test-secrets/remote-pool-settings-api.key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-remote-pool-settings-backups"
})
@AutoConfigureMockMvc
class RemotePoolSettingsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RemoteDataSourceRegistry pools;

    @Test
    void validatesPersistsAndAppliesSettingsWithoutRestart() throws Exception {
        mvc.perform(get("/api/admin/connection-pool-settings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maximumPoolSize").value(10));
        for (String body : new String[]{"{}", "{\"maximumPoolSize\":null}", "{\"maximumPoolSize\":1}", "{\"maximumPoolSize\":51}"}) {
            mvc.perform(put("/api/admin/connection-pool-settings").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(pools.maximumPoolSize()).isEqualTo(10);
        for (int size : new int[]{50, 2, 12}) {
            mvc.perform(put("/api/admin/connection-pool-settings").contentType("application/json")
                            .content("{\"maximumPoolSize\":" + size + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.maximumPoolSize").value(size));
            assertThat(pools.maximumPoolSize()).isEqualTo(size);
            assertThat(jdbc.queryForObject("SELECT maximum_pool_size FROM remote_pool_settings WHERE id = 1", Integer.class))
                    .isEqualTo(size);
        }
    }
}
