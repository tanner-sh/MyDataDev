package com.example.dbadmin.service;

import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SqlExecutionRegistry {
    private final ConcurrentMap<String, RunningStatement> running = new ConcurrentHashMap<>();

    public String register(String requestedId, long connectionId, Statement statement) {
        String executionId = normalize(requestedId);
        RunningStatement previous = running.putIfAbsent(executionId, new RunningStatement(connectionId, statement));
        if (previous != null) throw new IllegalArgumentException("SQL 执行标识已在使用中");
        return executionId;
    }

    public boolean cancel(String executionId) throws Exception {
        if (executionId == null || executionId.isBlank()) return false;
        RunningStatement statement = running.get(executionId);
        if (statement == null) return false;
        statement.statement().cancel();
        return true;
    }

    public void unregister(String executionId, Statement statement) {
        if (executionId != null) running.computeIfPresent(executionId, (ignored, current) -> current.statement() == statement ? null : current);
    }

    int size() {
        return running.size();
    }

    private String normalize(String requestedId) {
        if (requestedId == null || requestedId.isBlank()) return UUID.randomUUID().toString();
        try {
            return UUID.fromString(requestedId).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SQL 执行标识无效");
        }
    }

    private record RunningStatement(long connectionId, Statement statement) {
    }
}
