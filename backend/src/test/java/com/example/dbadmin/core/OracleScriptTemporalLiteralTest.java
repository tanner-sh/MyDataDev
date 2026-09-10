package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle 的时间字面量必须自带格式串。
 *
 * <p>Oracle 按会话的 NLS_DATE_FORMAT 解析裸字符串（默认 DD-MON-RR），于是
 * '2026-09-09 12:34:56' 直接 ORA-01843「月份无效」—— 导出的 SQL 和备份文件里的时间列在
 * Oracle 上跑不回去。脚本是先生成、后执行的，执行时的会话设置无从假设，所以格式只能写死在
 * 字面量里。这条是 Oracle 实库回归报出来的，测试守着它不再退回去。</p>
 */
class OracleScriptTemporalLiteralTest {
    private final OracleDialect dialect = new OracleDialect();

    @Test
    void wrapsTimestampsInToTimestampWithAnExplicitFormat() {
        assertThat(dialect.scriptLiteral(Timestamp.valueOf("2026-09-09 12:34:56.123456")))
                .isEqualTo("TO_TIMESTAMP('2026-09-09 12:34:56.123456', 'YYYY-MM-DD HH24:MI:SS.FF')");
        // 没有小数秒时格式串里也不该带 .FF，否则 Oracle 要求那一段必须存在。
        assertThat(dialect.scriptLiteral(Timestamp.valueOf("2026-09-09 12:34:56")))
                .isEqualTo("TO_TIMESTAMP('2026-09-09 12:34:56', 'YYYY-MM-DD HH24:MI:SS')");
    }

    @Test
    void wrapsDatesInToDate() {
        assertThat(dialect.scriptLiteral(LocalDate.of(2026, 9, 9)))
                .isEqualTo("TO_DATE('2026-09-09', 'YYYY-MM-DD')");
        assertThat(dialect.scriptLiteral(java.sql.Date.valueOf("2026-09-09")))
                .isEqualTo("TO_DATE('2026-09-09', 'YYYY-MM-DD')");
    }

    @Test
    void keepsTheZoneOffsetInsteadOfLettingItBecomeGarbage() {
        OffsetDateTime value = OffsetDateTime.of(2026, 9, 9, 12, 34, 56, 123_456_000, ZoneOffset.ofHours(8));
        assertThat(dialect.scriptLiteral(value))
                .isEqualTo("TO_TIMESTAMP_TZ('2026-09-09 12:34:56.123456+08:00', 'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM')");
    }

    @Test
    void leavesOtherDialectsOnTheQuotedIsoForm() {
        // MySQL、PostgreSQL、SQL Server 都按 ISO 解析裸字符串，不该被这次改动带上转换函数。
        for (DatabaseDialect other : new DatabaseDialect[]{new MySqlDialect(), new PostgreSqlDialect(), new SqlServerDialect()}) {
            assertThat(other.scriptLiteral(Timestamp.valueOf("2026-09-09 12:34:56.123456")))
                    .as(other.getClass().getSimpleName())
                    .isEqualTo("'2026-09-09 12:34:56.123456'");
        }
    }
}
