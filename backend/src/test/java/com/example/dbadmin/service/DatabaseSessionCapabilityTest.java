package com.example.dbadmin.service;

import com.example.dbadmin.core.DefaultDialect;
import com.example.dbadmin.core.MariaDbDialect;
import com.example.dbadmin.core.MySqlDialect;
import com.example.dbadmin.core.OceanBaseMySqlDialect;
import com.example.dbadmin.core.OceanBaseOracleDialect;
import com.example.dbadmin.core.OracleDialect;
import com.example.dbadmin.core.PostgreSqlDialect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「能不能终止会话」必须是一个能力位，不能靠拿假会话号去试探。
 *
 * <p>曾经用 {@code killSessionSql("1") != null} 来判断，结果 Oracle 的会话标识是
 * {@code SID,SERIAL#} 形式，"1" 不合法直接抛异常 —— 整个活动会话列表在 Oracle 上都打不开。</p>
 */
class DatabaseSessionCapabilityTest {
    @Test
    void dialectsThatCanKillSessionsSaySoWithoutBeingProbed() {
        assertThat(new MySqlDialect().supportsKillSession()).isTrue();
        assertThat(new MariaDbDialect().supportsKillSession()).isTrue();
        assertThat(new PostgreSqlDialect().supportsKillSession()).isTrue();
        assertThat(new OracleDialect().supportsKillSession()).isTrue();
        assertThat(new DefaultDialect().supportsKillSession()).isFalse();
    }

    @Test
    void everyDialectThatSupportsKillAlsoAdvertisesActiveSessions() {
        // 能终止却读不到列表没有意义：用户根本看不到要终止哪一个。
        assertThat(new MySqlDialect().activeSessionsSql()).isNotBlank();
        assertThat(new PostgreSqlDialect().activeSessionsSql()).isNotBlank();
        assertThat(new OracleDialect().activeSessionsSql()).isNotBlank();
    }

    /**
     * 每个能列会话的方言都必须把工具自己那条会话排除掉。
     *
     * <p>会话面板每 5 秒自动刷新，不排除的话「正在执行」里永远挂着本查询自身，把真正在跑
     * 的语句挤下去。PostgreSQL 一直是这么写的，MySQL / MariaDB / Oracle 之前漏了 ——
     * 逐个方言各写一份 SQL，就会逐个漏，所以这条断言按方言清单一起过。</p>
     */
    @Test
    void everySessionListingDialectExcludesItsOwnSession() {
        assertThat(new MySqlDialect().activeSessionsSql()).contains("CONNECTION_ID()");
        assertThat(new MariaDbDialect().activeSessionsSql()).contains("CONNECTION_ID()");
        assertThat(new OceanBaseMySqlDialect().activeSessionsSql()).contains("CONNECTION_ID()");
        assertThat(new PostgreSqlDialect().activeSessionsSql()).contains("pg_backend_pid()");
        assertThat(new OracleDialect().activeSessionsSql()).contains("SYS_CONTEXT('USERENV', 'SID')");
        assertThat(new OceanBaseOracleDialect().activeSessionsSql()).contains("SYS_CONTEXT('USERENV', 'SID')");
    }

    @Test
    void oracleStillRejectsAMalformedSessionId() {
        assertThatThrownBy(() -> new OracleDialect().killSessionSql("1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new OracleDialect().killSessionSql("42,7")).contains("'42,7'");
    }

    @Test
    void numericDialectsRejectInjectionAttempts() {
        assertThatThrownBy(() -> new MySqlDialect().killSessionSql("1; DROP TABLE users"))
                .isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> new PostgreSqlDialect().killSessionSql("1) OR true--"))
                .isInstanceOf(NumberFormatException.class);
    }
}
