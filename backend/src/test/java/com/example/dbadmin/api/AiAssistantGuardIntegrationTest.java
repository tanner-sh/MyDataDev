package com.example.dbadmin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 问答入口的两道闸门。
 *
 * <p>这些用例都在真正调用模型之前就该被拒绝，因此不需要任何上游服务：闸门失效的表现恰恰是
 * 请求继续往下走。三条被守住的承诺 —— 功能关着时什么都不给、连接没授权时取不到结构、
 * 只授权结构的连接不能把查询结果发出去。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-guard-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.crypto-key-file=target/test-secrets/ai-guard-test.key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-ai-guard-test-backups"
})
@AutoConfigureMockMvc
class AiAssistantGuardIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void refusesEverythingWhileTheFeatureIsDisabled() throws Exception {
        long connectionId = createConnection("ai-guard-disabled");

        mvc.perform(post("/api/ai/sql/diagnose").contentType("application/json").content(body(
                        "connectionId", connectionId, "sql", "SELECT 1", "errorMessage", "boom")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_DISABLED"));
    }

    @Test
    void refusesAConnectionThatWasNeverShared() throws Exception {
        long connectionId = createConnection("ai-guard-unshared");
        enableAi();

        mvc.perform(post("/api/ai/sql/diagnose").contentType("application/json").content(body(
                        "connectionId", connectionId, "sql", "SELECT 1", "errorMessage", "boom")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AI_CONNECTION_NOT_SHARED"));
    }

    /** 只授权了结构的连接不能把查询结果发出去，哪怕只有几行。 */
    @Test
    void refusesToInterpretResultsFromAStructureOnlyConnection() throws Exception {
        long connectionId = createConnection("ai-guard-structure");
        enableAi();
        share(connectionId, "STRUCTURE", 0);

        mvc.perform(post("/api/ai/sql/interpret/stream").contentType("application/json").content(body(
                        "connectionId", connectionId, "sql", "SELECT 1", "preview", "id\n1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AI_SAMPLE_NOT_ALLOWED"));
    }

    @Test
    void refusesADocumentRequestWithoutTables() throws Exception {
        long connectionId = createConnection("ai-guard-document");
        enableAi();
        share(connectionId, "STRUCTURE", 0);

        mvc.perform(post("/api/ai/sql/document/stream").contentType("application/json").content(
                        mapper.writeValueAsString(Map.of("connectionId", connectionId, "tables", List.of("")))))
                .andExpect(status().isBadRequest());
    }

    private void enableAi() throws Exception {
        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body(
                        "enabled", true, "provider", "ANTHROPIC", "model", "claude-opus-5",
                        "apiKey", "sk-ant-not-used-by-these-tests", "effort", "HIGH")))
                .andExpect(status().isOk());
    }

    private void share(long connectionId, String sharing, int sampleRowLimit) throws Exception {
        mvc.perform(put("/api/ai/connections/" + connectionId + "/policy").contentType("application/json")
                        .content(body("sharing", sharing, "sampleRowLimit", sampleRowLimit)))
                .andExpect(status().isOk());
    }

    private long createConnection(String name) {
        jdbc.update("""
                INSERT INTO db_connection(name, db_type, jdbc_url, username, encrypted_password, environment, readonly)
                VALUES (?, 'H2', 'jdbc:h2:mem:ai-guard-target', 'sa', NULL, 'dev', FALSE)
                """, name);
        return jdbc.queryForObject("SELECT id FROM db_connection WHERE name = ?", Long.class, name);
    }

    private String body(Object... pairs) throws Exception {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        return mapper.writeValueAsString(values);
    }
}
