package com.example.dbadmin.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class TrustedForwardedHeadersTest {
    @Test void ignoresSpoofedAddressAndHttpsHeadersFromOrdinaryClients() throws Exception {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.7"); request.addHeader("X-Forwarded-For", "203.0.113.9"); request.addHeader("X-Forwarded-Proto", "https");
        new TrustedForwardedHeaders(List.of()).doFilter(request, new MockHttpServletResponse(), (r,s) -> {
            var http = (jakarta.servlet.http.HttpServletRequest) r;
            assertThat(http.getRemoteAddr()).isEqualTo("192.0.2.7"); assertThat(http.getScheme()).isEqualTo("http");
            assertThat(http.getAttribute(TrustedForwardedHeaders.DECLARED_FOR)).isEqualTo("203.0.113.9");
        });
    }
    @Test void acceptsHttpsFromTrustedProxyButDoesNotTrustPrependedAttackerAddress() throws Exception {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1"); request.addHeader("X-Forwarded-For", "203.0.113.9, 192.0.2.7"); request.addHeader("Forwarded", "for=203.0.113.99"); request.addHeader("X-Forwarded-Proto", "https"); request.addHeader("X-Forwarded-Host", "db.example.com");
        new TrustedForwardedHeaders(List.of("127.0.0.1")).doFilter(request, new MockHttpServletResponse(), (r,s) -> {
            var http = (jakarta.servlet.http.HttpServletRequest) r;
            assertThat(http.getRemoteAddr()).isEqualTo("192.0.2.7"); assertThat(http.getScheme()).isEqualTo("https"); assertThat(http.getServerName()).isEqualTo("db.example.com");
            assertThat(http.getAttribute(TrustedForwardedHeaders.PEER)).isEqualTo("127.0.0.1");
        });
    }
}
