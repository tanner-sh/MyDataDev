package com.example.dbadmin.service;

import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SqlExecutionRegistry {
    private final ConcurrentMap<String, RunningStatement> running = new ConcurrentHashMap<>();

    public String register(String requestedId, long connectionId, Statement statement) {
        String executionId = normalize(requestedId);
        RunningStatement previous = running.putIfAbsent(executionId, new RunningStatement(connectionId, statement, new AtomicBoolean()));
        if (previous != null) throw new IllegalArgumentException("SQL 执行标识已在使用中");
        return executionId;
    }

    public boolean cancel(String executionId) throws Exception {
        if (executionId == null || executionId.isBlank()) return false;
        RunningStatement statement = running.get(executionId);
        if (statement == null) return false;
        statement.cancelled().set(true);
        statement.statement().cancel();
        return true;
    }

    public OptionalLong connectionId(String executionId) {
        if (executionId == null || executionId.isBlank()) return OptionalLong.empty();
        RunningStatement statement = running.get(executionId);
        return statement == null ? OptionalLong.empty() : OptionalLong.of(statement.connectionId());
    }

    /**
     * 这条执行是不是被用户主动取消的。驱动报「取消」与「超时」常用同一个错误（PostgreSQL 57014、Oracle ORA-01013），
     * 只有这里记得是谁让它停的。必须在 {@link #unregister} 之前问。
     */
    public boolean cancelRequested(String executionId, Statement statement) {
        if (executionId == null) return false;
        RunningStatement current = running.get(executionId);
        return current != null && current.statement() == statement && current.cancelled().get();
    }

    /**
     * 用户点了取消时把驱动的错误换成 SQL_CANCELLED。不换的话，PostgreSQL 57014 / Oracle ORA-01013 会被当成
     * 超时（SQL_TIMEOUT）报出去，界面上进的是「操作问题」，还提示用户去调大超时 —— 而这是他自己让它停的。
     * 必须在 {@link #unregister} 之前调用。
     */
    public Exception translateCancel(String executionId, Statement statement, Exception error) {
        if (!cancelRequested(executionId, statement)) return error;
        return new com.example.dbadmin.api.ApiProblemException(org.springframework.http.HttpStatus.CONFLICT, "SQL_CANCELLED",
                "执行已取消，数据库已中止这条语句；已经提交的部分不会回滚。");
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

    private record RunningStatement(long connectionId, Statement statement, AtomicBoolean cancelled) {
    }
}
