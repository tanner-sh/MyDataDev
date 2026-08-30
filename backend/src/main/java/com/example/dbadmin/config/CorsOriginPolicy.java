package com.example.dbadmin.config;

import java.util.List;
import java.util.Locale;

/**
 * CORS origin pattern 的安全判定。
 *
 * <p>会话认证启用后，跨域请求会带上会话 Cookie，而 {@code /api/auth/status} 的响应里就有
 * CSRF 令牌。Spring 的 {@code allowedOrigins("*")} 与 {@code allowCredentials(true)} 互斥，
 * 但 {@code allowedOriginPatterns} 没有这个限制 —— 配成能匹配任意来源的 pattern 时，任何网页
 * 都能带着受害者的 Cookie 读到令牌，然后发起任意写操作。这种组合只能在启动时拦下来。</p>
 *
 * <p>只拒绝主机名整个是通配的写法。{@code https://*.corp.example} 这类子域通配是正常部署需求，
 * 不在拦截范围内。</p>
 */
public final class CorsOriginPolicy {
    private CorsOriginPolicy() {
    }

    /** 返回会匹配任意来源的 pattern；为空表示配置安全。 */
    public static List<String> patternsMatchingAnyOrigin(List<String> patterns) {
        return patterns.stream().filter(CorsOriginPolicy::matchesAnyOrigin).toList();
    }

    static boolean matchesAnyOrigin(String pattern) {
        String value = pattern == null ? "" : pattern.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        if (value.equals("*")) return true;
        int scheme = value.indexOf("://");
        String host = scheme < 0 ? value : value.substring(scheme + 3);
        int path = host.indexOf('/');
        if (path >= 0) host = host.substring(0, path);
        int port = host.lastIndexOf(':');
        if (port >= 0) host = host.substring(0, port);
        return host.equals("*");
    }
}
