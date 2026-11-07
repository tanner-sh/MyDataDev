package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseFamilyTest {
    @Test
    void mapsEveryAliasBothFormerCopiesRecognised() {
        // 原来对象目录认 dameng、不认 pg；恢复认 pg、不认 dameng。合并后两边都认。
        assertThat(DatabaseFamily.of("pg")).isEqualTo("postgresql");
        assertThat(DatabaseFamily.of("dameng")).isEqualTo("oracle");
        assertThat(DatabaseFamily.of("dm")).isEqualTo("oracle");
        assertThat(DatabaseFamily.of("OceanBase-Oracle")).isEqualTo("oracle");
        assertThat(DatabaseFamily.of("oceanbase-mysql")).isEqualTo("mysql");
        assertThat(DatabaseFamily.of("mssql")).isEqualTo("sqlserver");
        assertThat(DatabaseFamily.of(null)).isEmpty();
        assertThat(DatabaseFamily.of("Snowflake")).isEqualTo("snowflake");
    }

    @Test
    void treatsMariaDbAsMySqlCompatible() {
        assertThat(DatabaseFamily.isMySqlCompatible("mariadb")).isTrue();
        assertThat(DatabaseFamily.isMySqlCompatible("oceanbase-mysql")).isTrue();
        assertThat(DatabaseFamily.isMySqlCompatible("postgresql")).isFalse();
    }

    @Test
    void onlyMySqlAndOracleFamiliesCommitImplicitlyAroundDdl() {
        // 与 RestoreService 原来那份写死的清单一致：mysql / mariadb / oceanbase-mysql / oracle / oceanbase-oracle / 达梦。
        assertThat(new MySqlDialect().ddlCommitsImplicitly()).isTrue();
        assertThat(new MariaDbDialect().ddlCommitsImplicitly()).isTrue();
        assertThat(new OceanBaseMySqlDialect().ddlCommitsImplicitly()).isTrue();
        assertThat(new OracleDialect().ddlCommitsImplicitly()).isTrue();
        assertThat(new OceanBaseOracleDialect().ddlCommitsImplicitly()).isTrue();
        assertThat(new DamengDialect().ddlCommitsImplicitly()).isTrue();
        assertThat(new PostgreSqlDialect().ddlCommitsImplicitly()).isFalse();
        assertThat(new SqlServerDialect().ddlCommitsImplicitly()).isFalse();
        assertThat(new H2Dialect().ddlCommitsImplicitly()).isFalse();
    }
}
