package com.example.dbadmin.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** An already-started SSE response cannot be replaced with a JSON error document. */
@Component
public class CommittedResponseExceptionResolver implements HandlerExceptionResolver, Ordered {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CommittedResponseExceptionResolver.class);
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                                   Object handler, Exception error) {
        boolean eventStream = response.getContentType() != null && response.getContentType().startsWith("text/event-stream");
        if (!response.isCommitted() && !eventStream) return null;
        if (!response.isCommitted()) response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        if (ClientDisconnects.isClientGone(error)) log.debug("客户端已断开 {}", request.getRequestURI());
        else log.warn("流式响应或已提交响应出错，不能另写 JSON 错误响应：{} {}", request.getMethod(), request.getRequestURI(), error);
        return new ModelAndView();
    }
}
