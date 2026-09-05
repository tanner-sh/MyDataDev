package com.example.dbadmin.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlExecutionMetricsTest {
    @Test
    void countsEachKindAndOutcomeSeparately() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SqlExecutionMetrics metrics = new SqlExecutionMetrics(provider(registry));

        metrics.success(SqlExecutionMetrics.KIND_QUERY, System.nanoTime());
        metrics.success(SqlExecutionMetrics.KIND_QUERY, System.nanoTime());
        metrics.success(SqlExecutionMetrics.KIND_PAGE, System.nanoTime());

        assertThat(count(registry, SqlExecutionMetrics.KIND_QUERY, "success")).isEqualTo(2);
        assertThat(count(registry, SqlExecutionMetrics.KIND_PAGE, "success")).isEqualTo(1);
        assertThat(registry.find("dbadmin.sql.duration")
                .tags("kind", SqlExecutionMetrics.KIND_QUERY, "outcome", "success").timer().count()).isEqualTo(2);
    }

    /**
     * 超时单独成一类：它说明查询本身或者库慢了，而一般报错通常是语句写错了。混在一个数里，
     * 「今天错误率高」就指不出该看哪一边。
     */
    @Test
    void separatesTimeoutsFromOrdinaryFailures() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SqlExecutionMetrics metrics = new SqlExecutionMetrics(provider(registry));

        metrics.failure(SqlExecutionMetrics.KIND_QUERY, System.nanoTime(), new SQLTimeoutException("超时"));
        metrics.failure(SqlExecutionMetrics.KIND_QUERY, System.nanoTime(), new SQLException("语法错误"));
        // 驱动常把超时包在别的异常里，所以要顺着 cause 链找。
        metrics.failure(SqlExecutionMetrics.KIND_QUERY, System.nanoTime(),
                new RuntimeException("包装", new SQLTimeoutException("超时")));

        assertThat(count(registry, SqlExecutionMetrics.KIND_QUERY, "timeout")).isEqualTo(2);
        assertThat(count(registry, SqlExecutionMetrics.KIND_QUERY, "error")).isEqualTo(1);
    }

    @Test
    void treatsAMissingErrorAsAnOrdinaryFailure() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SqlExecutionMetrics metrics = new SqlExecutionMetrics(provider(registry));

        metrics.failure(SqlExecutionMetrics.KIND_SCRIPT, System.nanoTime(), null);

        assertThat(count(registry, SqlExecutionMetrics.KIND_SCRIPT, "error")).isEqualTo(1);
    }

    /** 没有 registry 时必须完全无操作 —— 桌面模式默认不开指标端点。 */
    @Test
    void staysSilentWithoutARegistry() {
        SqlExecutionMetrics metrics = new SqlExecutionMetrics();

        metrics.success(SqlExecutionMetrics.KIND_QUERY, System.nanoTime());
        metrics.failure(SqlExecutionMetrics.KIND_QUERY, System.nanoTime(), new SQLException("x"));
    }

    private static double count(MeterRegistry registry, String kind, String outcome) {
        return registry.find("dbadmin.sql.executions").tags("kind", kind, "outcome", outcome).counter().count();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}
