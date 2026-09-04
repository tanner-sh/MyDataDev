package com.example.dbadmin.service.ai;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Agent 指标统一入口；没有启用 Micrometer registry 时保持无操作。 */
@Component
public class AiAgentMetrics {
    private final MeterRegistry registry;

    public AiAgentMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
    }

    public void request(String outcome, long startedNanos, long inputTokens, long outputTokens, long cacheReadTokens) {
        if (registry == null) return;
        registry.counter("dbadmin.ai.agent.requests", "outcome", outcome).increment();
        registry.timer("dbadmin.ai.agent.duration", "outcome", outcome)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)));
        registry.counter("dbadmin.ai.agent.tokens", "type", "input").increment(Math.max(0, inputTokens));
        registry.counter("dbadmin.ai.agent.tokens", "type", "output").increment(Math.max(0, outputTokens));
        registry.counter("dbadmin.ai.agent.tokens", "type", "cache_read").increment(Math.max(0, cacheReadTokens));
    }

    public void tool(String name, boolean error) {
        if (registry == null) return;
        registry.counter("dbadmin.ai.agent.tool.calls", "tool", safeTool(name),
                "outcome", error ? "error" : "success").increment();
    }

    public void validation(boolean valid) {
        if (registry == null) return;
        registry.counter("dbadmin.ai.agent.validation", "outcome", valid ? "valid" : "invalid").increment();
    }

    private static String safeTool(String name) {
        return switch (name) {
            case "search_schema", "describe_objects", "find_related_objects", "search_query_history",
                 "get_object_ddl" -> name;
            default -> "unknown";
        };
    }
}
