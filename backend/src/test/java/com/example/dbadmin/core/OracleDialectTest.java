package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

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

        assertThat(sql).isEqualTo("SELECT * FROM (SELECT dbadmin_page_source.*, ROWNUM __DBADMIN_PAGE_RN__ FROM (SELECT * FROM \"APP\".\"USER\") dbadmin_page_source WHERE ROWNUM <= 300) WHERE __DBADMIN_PAGE_RN__ > 200");
        assertThat(dialect.paginationHelperColumn()).isEqualTo("__DBADMIN_PAGE_RN__");
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

    @Test
    void usesOracleAddAndModifyTableSyntax() {
        ColumnInfo id = new ColumnInfo("ID", "NUMBER", 0, false, null, 1, null);
        ObjectDetail original = new ObjectDetail("APP", "USERS", "TABLE", List.of(id), List.of(), List.of("ID"), "PK_USERS", 0L, "", "GENERATED");
        TableDesignRequest design = new TableDesignRequest(
                "APP",
                "USERS",
                List.of(
                        new ColumnDesign("ID", "NUMBER", null, false, null, "ID", false),
                        new ColumnDesign("DISPLAY_NAME", "VARCHAR2", 80, true, null, null, false)
                ),
                List.of(),
                List.of("ID"),
                null
        );

        assertThat(dialect.alterTableSql("APP", "USERS", original, design))
                .contains("ALTER TABLE \"APP\".\"USERS\" ADD (\"DISPLAY_NAME\" VARCHAR2(80))")
                .noneMatch(sql -> sql.contains(" ADD COLUMN "));
    }
}
