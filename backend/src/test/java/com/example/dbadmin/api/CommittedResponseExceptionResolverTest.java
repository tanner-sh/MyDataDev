package com.example.dbadmin.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class CommittedResponseExceptionResolverTest {
    @Test void leavesNormalErrorsToTheAdviceButNeverRewritesCommittedSse() {
        var resolver = new CommittedResponseExceptionResolver();
        var request = new MockHttpServletRequest("GET", "/mcp");
        var response = new MockHttpServletResponse();
        var error = new IllegalStateException("async stream ended");
        assertThat(resolver.resolveException(request, response, null, error)).isNull();
        response.setContentType("text/event-stream");
        assertThat(resolver.resolveException(request, response, null, error)).isNotNull();
        assertThat(response.getStatus()).isEqualTo(500);
        response.setStatus(200);
        response.setCommitted(true);
        assertThat(resolver.resolveException(request, response, null, error)).isNotNull();
        assertThat(response.getContentType()).isEqualTo("text/event-stream");
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
