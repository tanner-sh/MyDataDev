package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DamengDialectTest {
    private final DamengDialect dialect = new DamengDialect();

    @Test
    void supportsDmUrlAndLimitOffsetPagination() {
        assertThat(dialect.supports("dm", "jdbc:dm://localhost:5236")).isTrue();
        assertThat(dialect.supports("mysql", "jdbc:dm://localhost:5236")).isTrue();
        assertThat(dialect.namespaceKind()).isEqualTo(DatabaseDialect.NamespaceKind.SCHEMA);
        assertThat(dialect.pageQuery("SELECT * FROM \"APP\".\"USERS\"", 101, 200))
                .isEqualTo("SELECT * FROM \"APP\".\"USERS\" LIMIT 101 OFFSET 200");
    }

    @Test
    void usesStructuredExplainWithTimeoutAndLimitsWithoutExecutingQuery() throws Exception {
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var statement = org.mockito.Mockito.mock(java.sql.Statement.class);
        var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        var md = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
        org.mockito.Mockito.when(statement.executeQuery("EXPLAIN FOR SELECT * FROM T")).thenReturn(rs);
        org.mockito.Mockito.when(rs.getMetaData()).thenReturn(md);
        org.mockito.Mockito.when(md.getColumnCount()).thenReturn(1);
        org.mockito.Mockito.when(md.getColumnLabel(1)).thenReturn("OPERATION");
        org.mockito.Mockito.when(md.getColumnTypeName(1)).thenReturn("VARCHAR");
        org.mockito.Mockito.when(rs.next()).thenReturn(true, true, false);
        org.mockito.Mockito.when(rs.getObject(1)).thenReturn("NSET2");
        var result = dialect.explain(connection, "SELECT * FROM T", 1, 7);
        assertThat(dialect.capabilities().explain()).isTrue();
        assertThat(result.rows()).containsExactly(java.util.List.of("NSET2"));
        assertThat(result.truncated()).isTrue();
        org.mockito.Mockito.verify(statement).setQueryTimeout(7);
        org.mockito.Mockito.verify(statement).setMaxRows(2);
        org.mockito.Mockito.verify(statement).executeQuery("EXPLAIN FOR SELECT * FROM T");
        org.mockito.Mockito.verify(rs).close();
        org.mockito.Mockito.verify(statement).close();
        org.mockito.Mockito.verifyNoMoreInteractions(statement);
        org.mockito.Mockito.verify(connection).createStatement();
        org.mockito.Mockito.verify(connection).isReadOnly();
        org.mockito.Mockito.verifyNoMoreInteractions(connection);
    }

    @Test
    void closesStatementOnCompilationFailureWithoutRetryingOriginalQuery() throws Exception {
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var statement = org.mockito.Mockito.mock(java.sql.Statement.class);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
        var error = new java.sql.SQLException("invalid column");
        org.mockito.Mockito.when(statement.executeQuery("EXPLAIN FOR SELECT MISSING FROM T")).thenThrow(error);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dialect.explain(connection, "SELECT MISSING FROM T", 10, 5)).isSameAs(error);
        org.mockito.Mockito.verify(statement).setQueryTimeout(5);
        org.mockito.Mockito.verify(statement).setMaxRows(11);
        org.mockito.Mockito.verify(statement).executeQuery("EXPLAIN FOR SELECT MISSING FROM T");
        org.mockito.Mockito.verify(statement).close();
        org.mockito.Mockito.verifyNoMoreInteractions(statement);
    }

    @Test
    void changesOnlyRequestedColumnAttributesAndPersistsComments() {
        var original = new com.example.dbadmin.dto.ApiDtos.ColumnInfo("NOTE", "VARCHAR", 80, false, "原说明", 2, "'old'");
        var changed = new com.example.dbadmin.dto.ApiDtos.ColumnDesign("NOTE", "VARCHAR", 80, true, null, "NOTE", false, "新说明");
        assertThat(dialect.alterColumnSql("\"APP\".\"T\"", "NOTE", original, changed)).containsExactly(
                "ALTER TABLE \"APP\".\"T\" MODIFY (\"NOTE\" DEFAULT NULL NULL)",
                "COMMENT ON COLUMN \"APP\".\"T\".\"NOTE\" IS '新说明'");
        var same = new com.example.dbadmin.dto.ApiDtos.ColumnDesign("NOTE", "VARCHAR", 80, false, "'old'", "NOTE", false);
        assertThat(dialect.alterColumnSql("T", "NOTE", original, same)).isEmpty();
    }

    @Test
    void textPlansPreserveTreeIndentationAndBoundOutput() throws Exception {
        var plan = DamengDialect.readTextPlan("\n1 #NSET2: [1, 1, 4]\n2   #CSCN2: [1, 1, 4]\n", 1, 12);
        assertThat(plan.rows()).containsExactly(java.util.List.of("1 #NSET2: [1, 1, 4]"));
        assertThat(plan.truncated()).isTrue();
        var complete = DamengDialect.readTextPlan("1 #NSET2\n2   #CSCN2\n", 2, 12);
        assertThat(complete.rows().get(1).get(0)).isEqualTo("2   #CSCN2");
        assertThat(complete.truncated()).isFalse();
        assertThat(DamengDialect.readTextPlan("x".repeat(100001), 1, 0).truncated()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DamengDialect.readTextPlan(null, 1, 0))
                .isInstanceOf(java.sql.SQLException.class);
    }
}
