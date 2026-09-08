package com.example.dbadmin.api;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.config.WebConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;

class ClientDisconnectFilterTest {
    private final ClientDisconnectFilter filter = new ClientDisconnectFilter();

    @Test
    void swallowsAWriteFailureCausedByTheClientLeaving() {
        // 不挡住的话它会一路冒到 Tomcat 的 StandardWrapperValve，被记成
        // 「Servlet.service() ... threw exception」加一整页栈 —— 也就是用户看到的那一段。
        Throwable thrown = catchThrowable(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/restores/operations/stream"),
                new MockHttpServletResponse(),
                (request, response) -> {
                    throw new AsyncRequestNotUsableException("Servlet container error notification for disconnected client");
                }));

        assertThat(thrown).isNull();
    }

    @Test
    void letsRealIoFailuresThrough() {
        IOException error = new IOException("Input/output error");
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.io.FileInputStream", "read", "FileInputStream.java", 1)
        });

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/backups/1/download"),
                new MockHttpServletResponse(),
                (request, response) -> { throw error; }))
                .isSameAs(error);
    }

    @Test
    void alsoCoversAsyncAndErrorDispatches() throws Exception {
        // SSE 的写失败正是从这两条 dispatch 上冒出来的，不覆盖等于这个过滤器白加。
        // 两件事都要成立：容器把这两种 dispatch 交给它，OncePerRequestFilter 也不自己跳过。
        assertThat(registeredDispatcherTypes())
                .containsExactlyInAnyOrder(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse();
        assertThat(filter.shouldNotFilterErrorDispatch()).isFalse();
    }

    /** 注册用的 dispatcher 类型没有公开的读取口，只能看它最终往容器里注册成什么样。 */
    @SuppressWarnings("unchecked")
    private static EnumSet<DispatcherType> registeredDispatcherTypes() throws jakarta.servlet.ServletException {
        ServletContext context = mock(ServletContext.class);
        FilterRegistration.Dynamic registration = mock(FilterRegistration.Dynamic.class);
        when(context.addFilter(anyString(), any(Filter.class))).thenReturn(registration);

        new WebConfig(new AppProperties()).clientDisconnectFilter().onStartup(context);

        ArgumentCaptor<EnumSet<DispatcherType>> captor = ArgumentCaptor.forClass(EnumSet.class);
        verify(registration).addMappingForUrlPatterns(captor.capture(), eq(false), eq("/*"));
        return captor.getValue();
    }
}
