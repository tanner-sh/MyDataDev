package com.example.dbadmin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 设置接口的端到端行为。
 *
 * <p>重点不在字段能不能存下来，而在两条对用户的承诺：Key 存进去就再也读不回来，
 * 以及生产连接不接受样本档 —— 这两条如果哪天被改坏，从接口这一层能立刻看出来。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-settings-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.crypto-key-file=target/test-secrets/ai-settings-test.key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-ai-settings-test-backups"
})
@AutoConfigureMockMvc
class AiSettingsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void startsDisabledAndKeepsTheSavedKeyOutOfEveryResponse() throws Exception {
        mvc.perform(get("/api/ai/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$.model").value("claude-opus-5"))
                .andExpect(jsonPath("$.apiKeyConfigured").value(false));

        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body(
                        "enabled", true, "provider", "ANTHROPIC", "model", "claude-opus-5",
                        "apiKey", "sk-ant-integration-secret", "effort", "HIGH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        String stored = jdbc.queryForObject("SELECT api_key_cipher FROM ai_settings WHERE id = 1", String.class);
        assertThat(stored).isNotBlank().doesNotContain("sk-ant-integration-secret");

        // 掩码提交只改模型，Key 必须原样留着 —— 改个模型名就把 Key 抹掉是最容易犯的错。
        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body(
                        "enabled", true, "provider", "ANTHROPIC", "model", "claude-sonnet-5",
                        "apiKey", "******", "effort", "LOW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true));
        assertThat(jdbc.queryForObject("SELECT api_key_cipher FROM ai_settings WHERE id = 1", String.class))
                .isEqualTo(stored);
    }

    @Test
    void refusesToEnableTheOfficialApiWithoutAKey() throws Exception {
        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body(
                        "enabled", true, "provider", "ANTHROPIC", "model", "claude-opus-5",
                        "apiKey", "", "effort", "HIGH")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsEveryConnectionAsUnsharedUntilAnAdministratorOptsIn() throws Exception {
        long production = createConnection("ai-prod", "prod");
        long staging = createConnection("ai-staging", "dev");

        mvc.perform(get("/api/ai/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.connectionId == " + staging + ")].sharing").value("NONE"))
                .andExpect(jsonPath("$[?(@.connectionId == " + production + ")].production").value(true));

        mvc.perform(put("/api/ai/connections/" + staging + "/policy")
                        .contentType("application/json")
                        .content(body("sharing", "STRUCTURE_AND_SAMPLE", "sampleRowLimit", 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharing").value("STRUCTURE_AND_SAMPLE"))
                .andExpect(jsonPath("$.sampleRowLimit").value(3));

        mvc.perform(put("/api/ai/connections/" + production + "/policy")
                        .contentType("application/json")
                        .content(body("sharing", "STRUCTURE_AND_SAMPLE", "sampleRowLimit", 3)))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/ai/connections/" + production + "/policy")
                        .contentType("application/json")
                        .content(body("sharing", "STRUCTURE", "sampleRowLimit", 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleRowLimit").value(0));
    }

    private long createConnection(String name, String environment) {
        jdbc.update("""
                INSERT INTO db_connection(name, db_type, jdbc_url, username, encrypted_password, environment, readonly)
                VALUES (?, 'H2', 'jdbc:h2:mem:ai-target', 'sa', NULL, ?, FALSE)
                """, name, environment);
        return jdbc.queryForObject("SELECT id FROM db_connection WHERE name = ?", Long.class, name);
    }

    private String body(Object... pairs) throws Exception {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        return mapper.writeValueAsString(values);
    }
}
