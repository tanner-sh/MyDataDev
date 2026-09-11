package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 哪些方言不认多行 VALUES。
 *
 * <p>这份清单原来写死在备份的 service 里（按 dbType 字符串比对），导入那边却漏了，于是 Oracle 21
 * 上导入两行以上就报 ORA-00933。收进方言之后只剩这一处定义，这里把它钉住。</p>
 */
class MultiRowValuesCapabilityTest {
    @Test
    void oracleFamilyWritesOneRowPerStatement() {
        for (DatabaseDialect dialect : List.of(new OracleDialect(), new OceanBaseOracleDialect(), new DamengDialect())) {
            assertThat(dialect.supportsMultiRowValues()).as(dialect.getClass().getSimpleName()).isFalse();
        }
    }

    @Test
    void otherDialectsKeepBatchedValues() {
        for (DatabaseDialect dialect : List.of(new MySqlDialect(), new MariaDbDialect(), new OceanBaseMySqlDialect(),
                new PostgreSqlDialect(), new SqlServerDialect(), new SqliteDialect(), new ClickHouseDialect(),
                new DefaultDialect())) {
            assertThat(dialect.supportsMultiRowValues()).as(dialect.getClass().getSimpleName()).isTrue();
        }
    }
}
