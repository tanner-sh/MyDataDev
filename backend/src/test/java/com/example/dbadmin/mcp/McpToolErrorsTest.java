package com.example.dbadmin.mcp;

import com.example.dbadmin.api.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolErrorsTest {
    @Test
    void keepsTheProblemCodeAndTellsTheAgentWhatToSend() {
        var production = McpToolErrors.translate(new ApiProblemException(HttpStatus.CONFLICT, "PRODUCTION_CONFIRMATION_REQUIRED",
                "生产连接上的写操作需要输入连接名确认。", Map.of("confirmationText", "orders-prod")), McpToolErrors.Channel.EXECUTE, 60);
        assertThat(production.code()).isEqualTo("PRODUCTION_CONFIRMATION_REQUIRED");
        assertThat(production).hasMessageStartingWith("[PRODUCTION_CONFIRMATION_REQUIRED] ").hasMessageContaining("「orders-prod」");

        var unscoped = McpToolErrors.translate(new ApiProblemException(HttpStatus.CONFLICT, "UNSCOPED_MUTATION_CONFIRMATION_REQUIRED",
                "检测到未包含顶层 WHERE 条件的 UPDATE/DELETE。"), McpToolErrors.Channel.EXECUTE, 60);
        assertThat(unscoped).hasMessageContaining("unscopedMutationConfirmed=true");
    }

    @Test
    void reportsTimeoutsSeparatelyWithChannelSpecificAdvice() {
        var query = McpToolErrors.translate(new RuntimeException(new SQLTimeoutException("statement timeout")), McpToolErrors.Channel.QUERY, 30);
        assertThat(query.code()).isEqualTo(McpToolErrors.SQL_TIMEOUT);
        assertThat(query).hasMessageContaining("超过 30 秒").hasMessageContaining("查询超时");

        var write = McpToolErrors.translate(new SQLException("ORA-01013: user requested cancel of current operation", "72000", 1013),
                McpToolErrors.Channel.EXECUTE, 60);
        assertThat(write.code()).isEqualTo(McpToolErrors.SQL_TIMEOUT);
        assertThat(write).hasMessageContaining("db_execute_script").hasMessageContaining("不能证明已回滚");

        assertThat(McpToolErrors.isTimeout(new SQLException("canceling statement due to statement timeout", "57014"))).isTrue();
        assertThat(McpToolErrors.isTimeout(new SQLException("ORA-00942: table or view does not exist", "42000", 942))).isFalse();
        assertThat(McpToolErrors.isTimeout(new SQLException("ORA-00600: internal error code, arguments: -4012, Timeout", "HY000", 600))).isTrue();
    }

    @Test
    void poolExhaustionIsAConnectionProblemNotAStatementTimeout() {
        var pool = McpToolErrors.translate(new java.sql.SQLTransientConnectionException(
                "remote-33 - Connection is not available, request timed out after 10000ms."), McpToolErrors.Channel.EXECUTE, 60);
        assertThat(pool.code()).isEqualTo("CONNECTION_UNAVAILABLE");
        assertThat(pool).hasMessageContaining("可能根本没有发出").hasMessageNotContaining("已请求数据库取消");
        assertThat(McpToolErrors.translate(new SQLException("Connection is closed", "08003"), McpToolErrors.Channel.QUERY, 30).code())
                .isEqualTo("CONNECTION_UNAVAILABLE");
    }

    @Test
    void sqlErrorsCarryStateAndVendorCodeAndCancellationIsNotATimeout() {
        var sql = McpToolErrors.translate(new IllegalStateException("脚本收尾失败",
                new SQLException("ORA-00942: table or view does not exist", "42000", 942)), McpToolErrors.Channel.OTHER, 0);
        assertThat(sql.code()).isEqualTo("SQL_ERROR");
        assertThat(sql).hasMessageContaining("SQLState=42000").hasMessageContaining("942").hasMessageContaining("ORA-00942");

        var cancelled = McpToolErrors.translate(new SQLException("MCP 请求已取消", "HY008"), McpToolErrors.Channel.QUERY, 30);
        assertThat(cancelled.code()).isEqualTo("CANCELLED");

        assertThat(McpToolErrors.translate(new IllegalArgumentException("query 不能为空"), McpToolErrors.Channel.OTHER, 0))
                .hasMessage("[INVALID_REQUEST] query 不能为空");
    }

    @Test
    void neverAttachesACauseBecauseTheSdkReportsTheRootCauseMessage() {
        var original = new IllegalArgumentException("坏参数", new SQLException("底层原文"));
        var translated = McpToolErrors.translate(original, McpToolErrors.Channel.OTHER, 0);
        assertThat(translated.getCause()).isNull();
        assertThat(translated.getSuppressed()).containsExactly(original);
        assertThat(McpToolErrors.translate(translated, McpToolErrors.Channel.OTHER, 0)).isSameAs(translated);
    }
}
