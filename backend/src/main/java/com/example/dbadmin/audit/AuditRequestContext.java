package com.example.dbadmin.audit;

/** 当前 HTTP 请求附带到审计事件的上下文；后台任务没有请求上下文时字段保持为空。 */
public record AuditRequestContext(
        String remoteAddress,
        String forwardedFor,
        String userAgent,
        String requestId
) {
    private static final ThreadLocal<AuditRequestContext> CURRENT = new ThreadLocal<>();

    public static AuditRequestContext current() {
        return CURRENT.get();
    }

    public static void set(AuditRequestContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
