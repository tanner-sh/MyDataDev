package com.example.dbadmin.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话认证启用后跨域请求会带 Cookie，而 /api/auth/status 会返回 CSRF 令牌 ——
 * 能匹配任意来源的 pattern 加上 allowCredentials 等于把全部写接口交给任意网页。
 */
class CorsOriginPolicyTest {
    @Test
    void rejectsPatternsThatMatchEveryOrigin() {
        assertThat(CorsOriginPolicy.matchesAnyOrigin("*")).isTrue();
        assertThat(CorsOriginPolicy.matchesAnyOrigin("http://*")).isTrue();
        assertThat(CorsOriginPolicy.matchesAnyOrigin("https://*")).isTrue();
        assertThat(CorsOriginPolicy.matchesAnyOrigin("*://*")).isTrue();
        assertThat(CorsOriginPolicy.matchesAnyOrigin("https://*:8443")).isTrue();
        assertThat(CorsOriginPolicy.matchesAnyOrigin("  HTTPS://*  ")).isTrue();
    }

    @Test
    void allowsConcreteAndSubdomainPatterns() {
        // 子域通配是正常部署需求，不在拦截范围内。
        assertThat(CorsOriginPolicy.matchesAnyOrigin("https://*.corp.example")).isFalse();
        assertThat(CorsOriginPolicy.matchesAnyOrigin("https://db.example.com")).isFalse();
        assertThat(CorsOriginPolicy.matchesAnyOrigin("http://localhost:5173")).isFalse();
        assertThat(CorsOriginPolicy.matchesAnyOrigin("")).isFalse();
    }

    @Test
    void reportsEveryOffendingPattern() {
        assertThat(CorsOriginPolicy.patternsMatchingAnyOrigin(
                List.of("https://db.example.com", "*", "https://*.corp.example", "http://*")))
                .containsExactly("*", "http://*");
    }
}
