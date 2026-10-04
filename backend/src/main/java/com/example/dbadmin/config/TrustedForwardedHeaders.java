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
    /** 有些代理（如 Azure Application Gateway）写的是 ip:port 或 [v6]:port。 */
    private static String stripPort(String hop) {
        if (hop.startsWith("[")) {
            int end = hop.indexOf(']');
            return end > 0 ? hop.substring(1, end) : hop;
        }
        return hop.matches("\\d{1,3}(\\.\\d{1,3}){3}:\\d+") ? hop.substring(0, hop.indexOf(':')) : hop;
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
                String hop = stripPort(hops[i].trim());
                if (!hop.matches("[0-9a-fA-F:.]+")) break;
                boolean proxy;
                // 通过了字符检查也未必是 IP 字面量，IpAddressMatcher 对这种值直接抛异常；当作链条到此为止。
                try { proxy = trusted(hop); } catch (IllegalArgumentException malformed) { break; }
                client = hop;
                if (!proxy) break;
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
