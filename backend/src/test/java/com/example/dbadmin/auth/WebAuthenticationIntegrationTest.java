package com.example.dbadmin.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:web-auth-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.crypto-key=web-auth-test-crypto-key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-web-auth-test-backups",
        "app.auth.mode=LOCAL",
        "app.auth.username=operator",
        "app.auth.password=correct-horse-battery-staple"
})
@AutoConfigureMockMvc
class WebAuthenticationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void requiresLoginAndCreatesServerSessionWithCsrfProtection() throws Exception {
        mvc.perform(get("/api/connections"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        MvcResult statusResult = mvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andReturn();
        JsonNode statusBody = mapper.readTree(statusResult.getResponse().getContentAsString());
        String token = statusBody.path("csrfToken").asText();
        Cookie csrfCookie = statusResult.getResponse().getCookie("XSRF-TOKEN");

        mvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content("{\"username\":\"operator\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        MvcResult login = mvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content("{\"username\":\"operator\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("operator"))
                .andExpect(jsonPath("$.displayName").value("operator"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.provider").value("LOCAL"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/connections").session(session))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/logout")
                        .session(session)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(get("/api/connections").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void administratorCanManageUsersAndDisabledUsersLoseExistingSessions() throws Exception {
        LoginSession administrator = login("operator", "correct-horse-battery-staple");
        String username = "readonly.user";
        String createBody = """
                {"username":"%s","displayName":"Read Only User","role":"OPERATOR","password":"operator-password-123","enabled":true}
                """.formatted(username);
        MvcResult created = mvc.perform(post("/api/admin/users")
                        .session(administrator.session())
                        .cookie(administrator.csrfCookie())
                        .header("X-XSRF-TOKEN", administrator.csrfToken())
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn();
        long userId = mapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();

        LoginSession operator = login(username, "operator-password-123");
        mvc.perform(get("/api/connections").session(operator.session()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").session(operator.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        String disableBody = """
                {"username":"%s","displayName":"Read Only User","role":"OPERATOR","password":null,"enabled":false}
                """.formatted(username);
        mvc.perform(put("/api/admin/users/{id}", userId)
                        .session(administrator.session())
                        .cookie(administrator.csrfCookie())
                        .header("X-XSRF-TOKEN", administrator.csrfToken())
                        .contentType("application/json")
                        .content(disableBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(get("/api/connections").session(operator.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private LoginSession login(String username, String password) throws Exception {
        MvcResult statusResult = mvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode statusBody = mapper.readTree(statusResult.getResponse().getContentAsString());
        String token = statusBody.path("csrfToken").asText();
        Cookie csrfCookie = statusResult.getResponse().getCookie("XSRF-TOKEN");
        String body = mapper.writeValueAsString(java.util.Map.of("username", username, "password", password));
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andReturn();
        return new LoginSession((MockHttpSession) login.getRequest().getSession(false), csrfCookie, token);
    }

    private record LoginSession(MockHttpSession session, Cookie csrfCookie, String csrfToken) {}
}
