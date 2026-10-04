package com.example.dbadmin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Keeps the transport peer for audit and accepts forwarding only from configured proxies. */
public final class TrustedForwardedHeaders extends OncePerRequestFilter {
    public static final String PEER = TrustedForwardedHeaders.class.getName() + ".peer";
    public static final String DECLARED_FOR = TrustedForwardedHeaders.class.getName() + ".declaredFor";
    private final List<IpAddressMatcher> proxies;
    private final ForwardedHeaderFilter trusted = new ForwardedHeaderFilter();
    private final ForwardedHeaderFilter untrusted = new ForwardedHeaderFilter();
    public TrustedForwardedHeaders(List<String> proxies) {
        this.proxies = proxies.stream().map(IpAddressMatcher::new).toList();
        untrusted.setRemoveOnly(true);
    }
    private boolean trusted(String address) {
        return proxies.stream().anyMatch(proxy -> proxy.matches(address));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        request.setAttribute(PEER, request.getRemoteAddr());
        String declared = request.getHeader("X-Forwarded-For");
        request.setAttribute(DECLARED_FOR, declared == null ? "" : declared);
        if (!trusted(request.getRemoteAddr())) { untrusted.doFilter(request, response, chain); return; }
        String client = request.getRemoteAddr();
        if (declared != null) {
            String[] hops = declared.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (!hop.matches("[0-9a-fA-F:.]+")) break;
                client = hop;
                if (!trusted(hop)) break;
            }
        }
        String address = client;
        HttpServletRequest sanitized = new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                if ("Forwarded".equalsIgnoreCase(name)) return null;
                if ("X-Forwarded-For".equalsIgnoreCase(name)) return address;
                return super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                if ("Forwarded".equalsIgnoreCase(name)) return Collections.emptyEnumeration();
                if ("X-Forwarded-For".equalsIgnoreCase(name)) return Collections.enumeration(List.of(address));
                return super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(name -> !"Forwarded".equalsIgnoreCase(name)).toList());
            }
        };
        trusted.doFilter(sanitized, response, chain);
    }
}
