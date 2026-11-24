package com.example.dbadmin.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Completes the HTTP lifecycle of SSE responses from the MCP SDK's WebMVC transport. */
public class McpStreamableHttpFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);

        String contentType = response.getContentType();
        if (!request.isAsyncStarted() || response.getStatus() != HttpServletResponse.SC_OK || contentType == null
                || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(MediaType.parseMediaType(contentType))) {
            return;
        }

        if (HttpMethod.GET.matches(request.getMethod()) && !response.isCommitted()) {
            // The listening stream may stay idle. Commit HTTP headers as soon as the SDK registers it.
            request.getAsyncContext().getResponse().flushBuffer();
        } else if (HttpMethod.POST.matches(request.getMethod()) && response.isCommitted()
                && !WebAsyncUtils.getAsyncManager(request).hasConcurrentResult()) {
            // SDK 0.18.3 blocks until the POST response is written. The unknown-method path writes
            // its error without completing Spring's DeferredResult (upstream java-sdk #1041).
            request.getAsyncContext().complete();
        }
    }
}
