package com.example.dbadmin.mcp;

import com.example.dbadmin.service.RequestCancellation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

class McpCancellationFilterTest {
    @Test void cancelsOnlyTheMatchingAgentSessionAndRequest() throws Exception {
        try (var registry = new RegistryResource()) {
            var filter = new McpCancellationFilter(new ObjectMapper(), registry.value);
            var watching = new CountDownLatch(1);
            var cancelled = new CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var worker = new Thread(() -> {
                authenticate("one");
                try {
                    filter.doFilter(request("s1", "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\"}"), new MockHttpServletResponse(), (req, res) -> {
                        try (var registration = RequestCancellation.onCancel(cancelled::countDown)) {
                            watching.countDown();
                            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (Exception e) { throw new jakarta.servlet.ServletException(e); }
                    });
                } catch (Throwable e) { failure.set(e); }
                finally { SecurityContextHolder.clearContext(); }
            });
            worker.start();
            assertThat(watching.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                authenticate("two");
                notify(filter, "s1");
                authenticate("one");
                notify(filter, "s2");
                assertThat(cancelled.getCount()).isEqualTo(1);
                notify(filter, "s1");
                worker.join(5000);
                assertThat(worker.isAlive()).isFalse();
                assertThat(failure.get()).isNull();
            } finally { SecurityContextHolder.clearContext(); }
        }
    }
    private void notify(McpCancellationFilter filter, String session) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7}}"), response,
                (req, res) -> { throw new AssertionError("Notification should not reach the SDK"); });
        assertThat(response.getStatus()).isEqualTo(202);
    }
    private MockHttpServletRequest request(String session, String json) {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader(McpApiKeyAuthenticationFilter.SESSION_HEADER, session);
        request.setContent(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }
    private static void authenticate(String id) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new McpAgentPrincipal(id, Map.of(), false), null, List.of()));
    }
    private static class RegistryResource implements AutoCloseable {
        final McpCancellationRegistry value = new McpCancellationRegistry();
        public void close() { value.close(); }
    }
}
