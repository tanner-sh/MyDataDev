package com.example.dbadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;

/** The SDK version in use does not handle notifications/cancelled. Handle it after authentication. */
public class McpCancellationFilter extends OncePerRequestFilter {
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final McpCancellationRegistry requests;
    public McpCancellationFilter(ObjectMapper mapper, McpCancellationRegistry requests) {
        this.mapper = mapper; this.requests = requests;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) { chain.doFilter(request, response); return; }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) { response.setStatus(413); return; }
        HttpServletRequest replay = new BodyRequest(request, body);
        com.fasterxml.jackson.databind.JsonNode message;
        try { message = mapper.readTree(body); }
        catch (IOException error) { chain.doFilter(replay, response); return; }
        if (message == null) { chain.doFilter(replay, response); return; }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof McpAgentPrincipal principal)) {
            response.setStatus(401); return;
        }
        String session = request.getHeader(McpApiKeyAuthenticationFilter.SESSION_HEADER);
        String method = message.path("method").asText();
        if (session != null && "notifications/cancelled".equals(method) && !message.has("id")) {
            var id = message.path("params").path("requestId");
            if (id.isTextual() || id.isNumber()) {
                try { requests.cancel(principal.id(), session, id.toString()); }
                catch (java.util.concurrent.RejectedExecutionException busy) { response.setStatus(503); return; }
            }
            response.setStatus(202);
            return;
        }
        var id = message.path("id");
        if (session == null || !"tools/call".equals(method) || !(id.isTextual() || id.isNumber())) {
            chain.doFilter(replay, response); return;
        }
        try (var scope = requests.begin(principal.id(), session, id.toString())) {
            chain.doFilter(replay, response);
        } catch (ServletException | IOException error) { throw error; }
        catch (Exception error) { throw new ServletException(error); }
    }
    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
