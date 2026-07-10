package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void resolvesCurrentOracleSessionSchema() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("FBA_DEV_20230210");

        assertThat(dialect.currentSchema(connection)).isEqualTo("FBA_DEV_20230210");
    }
}
