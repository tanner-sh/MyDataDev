package com.example.dbadmin.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.sql.SQLTimeoutException;
import java.time.Duration;

/**
 * SQL 执行的指标入口；没有启用 Micrometer registry 时保持无操作。
 *
 * <p>MCP、连接池注册表、备份与大文件协调器、AI 早就有埋点了，唯独所有人每天都在走的这条
 * 路没有 —— 出问题时只能翻审计一条条看。这里补上四个数：跑了多少次、耗时分布、失败率，
 * 以及**超时单独成一类**。超时和一般报错要分开：前者说明查询本身或者库慢了，后者通常是
 * 语句写错了，混在一个数里的话，「今天错误率高」根本指不出该看哪边。</p>
 *
 * <p>标签只有 {@code kind} 和 {@code outcome} 两个，取值都是固定集合。带上连接名或 SQL 会让
 * 时间序列的基数随用户行为无限增长，那是把监控系统拖垮的经典做法。</p>
 */
@Component
public class SqlExecutionMetrics {
    /** 单条查询。 */
    public static final String KIND_QUERY = "query";
    /** 分页取一批结果，也是单条可分页 SELECT 走的那条路。 */
    public static final String KIND_PAGE = "page";
    /** 多语句脚本。 */
    public static final String KIND_SCRIPT = "script";
    /** 执行计划。 */
    public static final String KIND_EXPLAIN = "explain";

    private final MeterRegistry registry;

    @Autowired
    public SqlExecutionMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
    }

    /** 无操作实例：测试与嵌入式调用用它，免得为了埋点去搭一个 registry。 */
    public SqlExecutionMetrics() {
        this.registry = null;
    }

    public void success(String kind, long startedNanos) {
        record(kind, "success", startedNanos);
    }

    /** 失败时按异常类型分流：超时和写错语句是两回事。 */
    public void failure(String kind, long startedNanos, Throwable error) {
        record(kind, outcome(error), startedNanos);
    }

    private void record(String kind, String outcome, long startedNanos) {
        if (registry == null) return;
        registry.counter("dbadmin.sql.executions", "kind", kind, "outcome", outcome).increment();
        registry.timer("dbadmin.sql.duration", "kind", kind, "outcome", outcome)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)));
    }

    private static String outcome(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLTimeoutException) return "timeout";
            if (current == current.getCause()) break;
        }
        return "error";
    }
}
