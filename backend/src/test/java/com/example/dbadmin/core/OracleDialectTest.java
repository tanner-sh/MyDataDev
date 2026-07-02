package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OracleDialectTest {
    private final OracleDialect dialect = new OracleDialect();

    @Test
    void supportsOracleTypeAndUrl() {
        assertThat(dialect.supports("oracle", "jdbc:oracle:thin:@//localhost:1521/ORCLPDB1")).isTrue();
        assertThat(dialect.supports("mysql", "jdbc:oracle:thin:@localhost:1521:ORCL")).isTrue();
        assertThat(dialect.supports("mysql", "jdbc:mysql://localhost:3306/demo")).isFalse();
    }

    @Test
    void buildsOracle11gCompatiblePagination() {
        String sql = dialect.pageQuery("SELECT * FROM \"APP\".\"USER\"", 100, 200);

        assertThat(sql).isEqualTo("SELECT * FROM (SELECT inner_query.*, ROWNUM dbadmin_rn FROM (SELECT * FROM \"APP\".\"USER\") inner_query WHERE ROWNUM <= 300) WHERE dbadmin_rn > 200");
    }
}
