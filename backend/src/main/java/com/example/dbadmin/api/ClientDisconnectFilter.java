package com.example.dbadmin.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把「对端已经走了」的写失败挡在 Servlet 容器的日志之外。
 *
 * <p>{@link ApiExceptionHandler} 只管得到处理器抛出来的异常。响应体开始往外写之后再断开，
 * 异常是从消息转换器那一层冒出去的，Spring 已经没法再处理（响应都提交了），于是一路冒到
 * Tomcat 的 {@code StandardWrapperValve}，被记成
 * {@code Servlet.service() for servlet [dispatcherServlet] ... threw exception} 加一整页栈。
 * SSE 那条长连接尤其常撞上：它一直开着，浏览器一刷新就断。</p>
 *
 * <p>这里在最外层收住这类异常：{@link ClientDisconnects} 认定是对端断开就只留一条 debug，
 * 其余 I/O 失败照旧向上抛，该报错还是报错。它必须覆盖 ASYNC 与 ERROR 两种 dispatch ——
 * SSE 的失败正是在这两条路上冒出来的 —— 所以注册时显式列出 dispatcher 类型
 * （见 {@code WebConfig.clientDisconnectFilter}），这里的 {@code shouldNotFilter*} 也一并放开：
 * 两处缺任何一处，异步那条路上的异常都照旧冒到容器日志里。</p>
 */
public class ClientDisconnectFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ClientDisconnectFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException error) {
            if (!ClientDisconnects.isClientGone(error)) throw error;
            log.debug("客户端在 {} {} 的响应写完之前断开", request.getMethod(), request.getRequestURI(), error);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
