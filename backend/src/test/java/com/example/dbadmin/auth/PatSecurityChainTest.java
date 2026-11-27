package com.example.dbadmin.auth;

import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.MetadataWriteQueue;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pat-security-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.crypto-key-file=target/test-secrets/pat-security-test.key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-pat-test-backups",
        "app.auth.mode=LOCAL",
        "app.auth.username=rootadmin",
        "app.auth.password=root-admin-password-123"
})
@AutoConfigureMockMvc
class PatSecurityChainTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetadataWriteQueue writeQueue;
    @Autowired AuditRepository auditRepository;

    @Test
    void systemInfoIsAccessibleWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product").value("MyDataDev"))
                .andExpect(jsonPath("$.apiVersion").value(1))
                .andExpect(jsonPath("$.mode").value("web"))
                .andExpect(jsonPath("$.authMode").value("LOCAL"));
    }

    @Test
    void patSecurityChainEnforcesStatelessnessConnectionWhitelistAndReadOnlyQuery() throws Exception {
        LoginSession admin = login("rootadmin", "root-admin-password-123");

        // 创建两条连接：connA（设为 SHARED）与 connB（无显式策略 / SHARED）
        long connA = createH2Connection(admin, "pat-shared-a");
        long connB = createH2Connection(admin, "pat-shared-b");
        // 将 connA 与 connB 都设为 SHARED，验证 LISTED 令牌仍能拦住白名单之外的共享连接
        setConnectionMode(admin, connA, "SHARED", List.of());
        setConnectionMode(admin, connB, "SHARED", List.of());
        // 顺带建一条无策略记录的连接 connNoPolicy
        long connNoPolicy = createH2Connection(admin, "pat-nopolicy");
        jdbc.update("DELETE FROM connection_access_policy WHERE connection_id = ?", connNoPolicy);

        // 创建普通操作员账号
        long operatorId = createUser(admin, "ci.bot", "CI Bot", "OPERATOR", "ci-bot-password-123");
        LoginSession operator = login("ci.bot", "ci-bot-password-123");

        // 操作员签发仅允许 connA 的 PAT
        CreatedToken opToken = createPat(operator, "ci-readonly", "LISTED", List.of(connA));

        // 1) 仅带 PAT 访问：无 CSRF、无 Set-Cookie、无 session
        MvcResult connListResult = mvc.perform(get("/api/connections")
                        .header("Authorization", "Bearer " + opToken.plaintext()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertThat(connListResult.getRequest().getSession(false)).isNull();
        JsonNode visible = mapper.readTree(connListResult.getResponse().getContentAsString());
        assertThat(visible.size()).isEqualTo(1);
        assertThat(visible.get(0).path("id").asLong()).isEqualTo(connA);

        // whoami 返回 PAT 元信息
        mvc.perform(get("/api/auth/whoami")
                        .header("Authorization", "Bearer " + opToken.plaintext()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authType").value("PAT"))
                .andExpect(jsonPath("$.username").value("ci.bot"))
                .andExpect(jsonPath("$.tokenId").value(opToken.id()))
                .andExpect(jsonPath("$.connectionScope").value("LISTED"));

        // 2) 白名单外的 SHARED 连接与无策略连接必须被拒绝（403）
        mvc.perform(post("/api/sql/query")
                        .header("Authorization", "Bearer " + opToken.plaintext())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("connectionId", connB, "sql", "SELECT 1"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONNECTION_ACCESS_DENIED"));

        mvc.perform(post("/api/sql/query")
                        .header("Authorization", "Bearer " + opToken.plaintext())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("connectionId", connNoPolicy, "sql", "SELECT 1"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONNECTION_ACCESS_DENIED"));

        // 3) 白名单内的连接：只读查询成功；写语句在 /api/sql/query 被拒绝
        mvc.perform(post("/api/sql/query")
                        .header("Authorization", "Bearer " + opToken.plaintext())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("connectionId", connA, "sql", "SELECT 1 AS n"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultSet").value(true))
                .andExpect(jsonPath("$.rows[0][0]").value(1));

        mvc.perform(post("/api/sql/query")
                        .header("Authorization", "Bearer " + opToken.plaintext())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("connectionId", connA, "sql", "CREATE TABLE blocked_t(id INT)"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READONLY_QUERY_REQUIRED"));

        // 4) 撤回用户对 connA 的权限（改为 RESTRICTED 且无授权）：白名单不能扩大权限 -> 403
        setConnectionMode(admin, connA, "RESTRICTED", List.of());
        mvc.perform(post("/api/sql/query")
                        .header("Authorization", "Bearer " + opToken.plaintext())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("connectionId", connA, "sql", "SELECT 1"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONNECTION_ACCESS_DENIED"));
        setConnectionMode(admin, connA, "SHARED", List.of());

        // 5) 同时携带管理员 Cookie 与操作员 PAT：按 PAT 处理（无权调 /api/admin/users）
        mvc.perform(get("/api/admin/users")
                        .session(admin.session())
                        .header("Authorization", "Bearer " + opToken.plaintext()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 6) PAT 不能签发新令牌
        mvc.perform(post("/api/auth/tokens")
                        .header("Authorization", "Bearer " + opToken.plaintext())
                        .contentType("application/json")
                        .content("{\"name\":\"nested\",\"expiresInDays\":30,\"connectionScope\":\"ALL\"}"))
                .andExpect(status().isForbidden());

        // 7) 管理员的 LISTED PAT 同样受白名单约束，且不能访问 /api/admin/**
        CreatedToken adminToken = createPat(admin, "admin-scoped", "LISTED", List.of(connA));
        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken.plaintext()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/sql/query")
                        .header("Authorization", "Bearer " + adminToken.plaintext())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("connectionId", connB, "sql", "SELECT 1"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONNECTION_ACCESS_DENIED"));
        mvc.perform(post("/api/sql/query")
                        .header("Authorization", "Bearer " + adminToken.plaintext())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("connectionId", connA, "sql", "SELECT 2"))))
                .andExpect(status().isOk());

        // 8) 无效 PAT 立即返回 401（即使同时带了有效 Cookie）
        mvc.perform(get("/api/connections")
                        .session(admin.session())
                        .header("Authorization", "Bearer mdd_pat_fakeid_fakesecret"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        // 9) 仅带 Cookie 的 POST 仍然需要 CSRF
        mvc.perform(post("/api/auth/tokens")
                        .session(operator.session())
                        .contentType("application/json")
                        .content("{\"name\":\"no-csrf\",\"expiresInDays\":30,\"connectionScope\":\"ALL\"}"))
                .andExpect(status().isForbidden());

        // 10) 修改密码触发 auth_version 变化，旧 PAT 立即失效
        mvc.perform(put("/api/admin/users/{id}", operatorId)
                        .session(admin.session())
                        .cookie(admin.csrfCookie())
                        .header("X-XSRF-TOKEN", admin.csrfToken())
                        .contentType("application/json")
                        .content("""
                                {"username":"ci.bot","displayName":"CI Bot","role":"OPERATOR","password":"ci-bot-new-password-456","enabled":true}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/api/connections")
                        .header("Authorization", "Bearer " + opToken.plaintext()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        // 11) 审计记录带有 via=cli, token=<id>，且哈希链完整
        var drained = new java.util.concurrent.CountDownLatch(1);
        writeQueue.submit(drained::countDown);
        assertThat(drained.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        List<String> cliDetails = jdbc.queryForList(
                "SELECT detail FROM audit_log WHERE actor = 'ci.bot' AND action = 'SQL_QUERY'",
                String.class
        );
        assertThat(cliDetails).anySatisfy(detail ->
                assertThat(detail).contains("via=cli, token=" + opToken.id()));
        assertThat(auditRepository.verifyChain().valid()).isTrue();

        // 12) 删除连接时白名单级联清理
        mvc.perform(delete("/api/connections/{id}", connA)
                        .session(admin.session())
                        .cookie(admin.csrfCookie())
                        .header("X-XSRF-TOKEN", admin.csrfToken()))
                .andExpect(status().isOk());
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_access_token_connection WHERE connection_id = ?",
                Integer.class,
                connA
        );
        assertThat(remaining).isZero();
    }

    private long createH2Connection(LoginSession session, String name) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "name", name,
                "dbType", "h2",
                "environment", "dev",
                "jdbcUrl", "jdbc:h2:mem:" + name.replace('-', '_') + ";DB_CLOSE_DELAY=-1",
                "username", "sa",
                "password", "",
                "readonly", false
        ));
        MvcResult result = mvc.perform(post("/api/connections")
                        .session(session.session())
                        .cookie(session.csrfCookie())
                        .header("X-XSRF-TOKEN", session.csrfToken())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private void setConnectionMode(LoginSession admin, long connectionId, String mode, List<Map<String, Object>> grants) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "accessMode", mode,
                "grants", grants
        ));
        mvc.perform(put("/api/admin/access/connections/{id}", connectionId)
                        .session(admin.session())
                        .cookie(admin.csrfCookie())
                        .header("X-XSRF-TOKEN", admin.csrfToken())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
    }

    private long createUser(LoginSession admin, String username, String displayName, String role, String password) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "username", username,
                "displayName", displayName,
                "role", role,
                "password", password,
                "enabled", true
        ));
        MvcResult result = mvc.perform(post("/api/admin/users")
                        .session(admin.session())
                        .cookie(admin.csrfCookie())
                        .header("X-XSRF-TOKEN", admin.csrfToken())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private CreatedToken createPat(LoginSession session, String name, String scope, List<Long> connectionIds) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "name", name,
                "expiresInDays", 30,
                "connectionScope", scope,
                "connectionIds", connectionIds
        ));
        MvcResult result = mvc.perform(post("/api/auth/tokens")
                        .session(session.session())
                        .cookie(session.csrfCookie())
                        .header("X-XSRF-TOKEN", session.csrfToken())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        return new CreatedToken(json.path("tokenInfo").path("id").asText(), json.path("token").asText());
    }

    private LoginSession login(String username, String password) throws Exception {
        MvcResult statusResult = mvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode statusBody = mapper.readTree(statusResult.getResponse().getContentAsString());
        String token = statusBody.path("csrfToken").asText();
        Cookie csrfCookie = statusResult.getResponse().getCookie("XSRF-TOKEN");
        String body = mapper.writeValueAsString(Map.of("username", username, "password", password));
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return new LoginSession((MockHttpSession) login.getRequest().getSession(false), csrfCookie, token);
    }

    private record LoginSession(MockHttpSession session, Cookie csrfCookie, String csrfToken) {}
    private record CreatedToken(String id, String plaintext) {}
}
