package com.example.dbadmin.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:connection-access-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.crypto-key-file=target/test-secrets/connection-access-test.key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-connection-access-test-backups",
        "app.auth.mode=LOCAL",
        "app.auth.username=access.admin",
        "app.auth.password=access-admin-password-123"
})
@AutoConfigureMockMvc
class ConnectionAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void newConnectionBelongsToCreatorAndGroupGrantEnforcesEachOperation() throws Exception {
        LoginSession administrator = login("access.admin", "access-admin-password-123");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long ownerId = createOperator(administrator, "owner." + suffix);
        long readerId = createOperator(administrator, "reader." + suffix);
        LoginSession owner = login("owner." + suffix, "operator-password-123");
        LoginSession reader = login("reader." + suffix, "operator-password-123");

        String connectionBody = mapper.writeValueAsString(Map.of(
                "name", "private-" + suffix,
                "dbType", "H2",
                "jdbcUrl", "jdbc:h2:mem:acl-target-" + suffix + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "username", "sa",
                "password", "",
                "environment", "dev",
                "readonly", false
        ));
        MvcResult created = mvc.perform(post("/api/connections")
                        .session(owner.session()).cookie(owner.csrfCookie())
                        .header("X-XSRF-TOKEN", owner.csrfToken())
                        .contentType("application/json").content(connectionBody))
                .andExpect(status().isOk())
                .andReturn();
        long connectionId = mapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();

        Map<String, Object> policy = jdbc.queryForMap(
                "SELECT access_mode, owner_user_id FROM connection_access_policy WHERE connection_id = ?", connectionId);
        assertThat(policy.get("access_mode")).isEqualTo("RESTRICTED");
        assertThat(((Number) policy.get("owner_user_id")).longValue()).isEqualTo(ownerId);
        assertThat(connectionIds(owner)).contains(connectionId);
        assertThat(connectionIds(reader)).doesNotContain(connectionId);

        mvc.perform(post("/api/ai/sql/chat/stream")
                        .session(reader.session()).cookie(reader.csrfCookie())
                        .header("X-XSRF-TOKEN", reader.csrfToken())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "connectionId", connectionId,
                                "message", "查询用户"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONNECTION_ACCESS_DENIED"));

        MvcResult group = mvc.perform(post("/api/admin/access/groups")
                        .session(administrator.session()).cookie(administrator.csrfCookie())
                        .header("X-XSRF-TOKEN", administrator.csrfToken())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "readers-" + suffix,
                                "description", "只读查询组",
                                "memberUserIds", new long[]{readerId}
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        long groupId = mapper.readTree(group.getResponse().getContentAsString()).path("id").asLong();

        mvc.perform(put("/api/admin/access/connections/{id}", connectionId)
                        .session(administrator.session()).cookie(administrator.csrfCookie())
                        .header("X-XSRF-TOKEN", administrator.csrfToken())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "accessMode", "RESTRICTED",
                                "ownerUserId", ownerId,
                                "grants", new Object[]{Map.of(
                                        "granteeType", "GROUP",
                                        "granteeId", groupId,
                                        "permissions", new String[]{"VIEW_METADATA", "QUERY"}
                                )}
                        ))))
                .andExpect(status().isOk());

        assertThat(connectionIds(reader)).contains(connectionId);
        mvc.perform(get("/api/access/me").param("connectionIds", Long.toString(connectionId)).session(reader.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + connectionId + "']").isArray())
                .andExpect(jsonPath("$['" + connectionId + "']").value(org.hamcrest.Matchers.containsInAnyOrder("VIEW_METADATA", "QUERY")));

        mvc.perform(post("/api/sql/execute")
                        .session(reader.session()).cookie(reader.csrfCookie())
                        .header("X-XSRF-TOKEN", reader.csrfToken())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("connectionId", connectionId, "sql", "SELECT 1", "maxRows", 10))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/sql/execute")
                        .session(reader.session()).cookie(reader.csrfCookie())
                        .header("X-XSRF-TOKEN", reader.csrfToken())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("connectionId", connectionId, "sql", "CREATE TABLE denied(id INT)", "maxRows", 10))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONNECTION_ACCESS_DENIED"));
    }

    @Test
    void loginFailureAuditCapturesRequestContextWithoutTrustingForwardedAddress() throws Exception {
        MvcResult statusResult = mvc.perform(get("/api/auth/status")).andExpect(status().isOk()).andReturn();
        JsonNode statusBody = mapper.readTree(statusResult.getResponse().getContentAsString());
        Cookie csrfCookie = statusResult.getResponse().getCookie("XSRF-TOKEN");
        String requestId = "login-audit-" + UUID.randomUUID();

        mvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", statusBody.path("csrfToken").asText())
                        .header("X-Request-ID", requestId)
                        .header("User-Agent", "MyDataDev-test-agent")
                        .header("X-Forwarded-For", "198.51.100.24")
                        .contentType("application/json")
                        .content("{\"username\":\"missing-user\",\"password\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-ID", requestId));

        Map<String, Object> event = waitForAudit(requestId);
        assertThat(event.get("action")).isEqualTo("AUTH_LOGIN_FAILED");
        assertThat(event.get("remote_address")).isEqualTo("127.0.0.1");
        assertThat(event.get("forwarded_for")).isEqualTo("198.51.100.24");
        assertThat(event.get("user_agent")).isEqualTo("MyDataDev-test-agent");
    }

    private long createOperator(LoginSession administrator, String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/users")
                        .session(administrator.session()).cookie(administrator.csrfCookie())
                        .header("X-XSRF-TOKEN", administrator.csrfToken())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "username", username,
                                "displayName", username,
                                "role", "OPERATOR",
                                "password", "operator-password-123",
                                "enabled", true
                        ))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long[] connectionIds(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/connections").session(session.session()))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        long[] ids = new long[body.size()];
        for (int index = 0; index < body.size(); index++) ids[index] = body.get(index).path("id").asLong();
        return ids;
    }

    private Map<String, Object> waitForAudit(String requestId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            var rows = jdbc.queryForList("SELECT action, remote_address, forwarded_for, user_agent FROM audit_log WHERE request_id = ?", requestId);
            if (!rows.isEmpty()) return rows.get(0);
            Thread.sleep(10);
        }
        throw new AssertionError("审计记录未在超时前落库：" + requestId);
    }

    private LoginSession login(String username, String password) throws Exception {
        MvcResult statusResult = mvc.perform(get("/api/auth/status")).andExpect(status().isOk()).andReturn();
        JsonNode statusBody = mapper.readTree(statusResult.getResponse().getContentAsString());
        String token = statusBody.path("csrfToken").asText();
        Cookie csrfCookie = statusResult.getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession((MockHttpSession) login.getRequest().getSession(false), csrfCookie, token);
    }

    private record LoginSession(MockHttpSession session, Cookie csrfCookie, String csrfToken) {}
}
