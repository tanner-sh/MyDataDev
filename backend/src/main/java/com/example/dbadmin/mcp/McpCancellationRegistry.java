package com.example.dbadmin.mcp;

import com.example.dbadmin.service.RequestCancellation;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

@Component
public class McpCancellationRegistry {
    private record Key(String agent, String session, String request) { }
    private final ConcurrentMap<Key, RequestCancellation> running = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> {
                Thread thread = new Thread(task, "dbadmin-mcp-cancel"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public AutoCloseable begin(String agent, String session, String request) {
        Key key = new Key(agent, session, request);
        RequestCancellation scope = new RequestCancellation(executor);
        if (running.putIfAbsent(key, scope) != null) {
            scope.close();
            throw new IllegalArgumentException("MCP 请求 ID 已在执行中");
        }
        return () -> { running.remove(key, scope); scope.close(); };
    }

    public void cancel(String agent, String session, String request) {
        RequestCancellation scope = running.get(new Key(agent, session, request));
        if (scope != null) scope.cancel();
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
