package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerDialectTest {
    private final SqlServerDialect dialect = new SqlServerDialect();

    @Test
    void paginationOnlyAcceptsATopLevelOrderByClause() {
        assertThat(dialect.pageQuery("SELECT 'order by' AS note", 20, 40))
                .isEqualTo("SELECT 'order by' AS note ORDER BY (SELECT NULL) OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY");
        assertThat(dialect.pageQuery("SELECT * FROM (SELECT * FROM t ORDER BY id) q", 20, 0))
                .contains("q ORDER BY (SELECT NULL) OFFSET 0 ROWS");
        assertThat(dialect.pageQuery("SELECT * FROM t /* order by fake */ ORDER /* keep */ BY id", 20, 0))
                .isEqualTo("SELECT * FROM t /* order by fake */ ORDER /* keep */ BY id OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY");
    }

    @Test
    void generatedLiteralsUseSqlServerBooleanUnicodeAndBinarySyntax() {
        assertThat(dialect.scriptLiteral(true)).isEqualTo("1");
        assertThat(dialect.scriptLiteral("中文'O")).isEqualTo("N'中文''O'");
        assertThat(dialect.scriptLiteral(new byte[]{0, (byte) 0xff})).isEqualTo("0x00ff");
    }

    @Test
    void resetsShowplanAfterQueryFailureAndDiscardsSessionIfResetFails() throws Exception {
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var enable = org.mockito.Mockito.mock(java.sql.Statement.class);
        var query = org.mockito.Mockito.mock(java.sql.Statement.class);
        var reset = org.mockito.Mockito.mock(java.sql.Statement.class);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(enable, query, reset);
        var queryError = new java.sql.SQLException("query failed");
        var resetError = new java.sql.SQLException("reset failed");
        org.mockito.Mockito.when(query.executeQuery("SELECT 1")).thenThrow(queryError);
        org.mockito.Mockito.when(reset.execute("SET SHOWPLAN_XML OFF")).thenThrow(resetError);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dialect.explain(connection, "SELECT 1", 10, 5))
                .isSameAs(queryError);
        org.mockito.Mockito.verify(enable).execute("SET SHOWPLAN_XML ON");
        org.mockito.Mockito.verify(reset).execute("SET SHOWPLAN_XML OFF");
        org.mockito.Mockito.verify(connection).abort(org.mockito.ArgumentMatchers.any());
        assertThat(queryError.getSuppressed()).containsExactly(resetError);
    }

    @Test
    void rejectsUnsupportedIdentityDefinitions() {
        var invalid = new com.example.dbadmin.dto.ApiDtos.ColumnDesign("id", "VARCHAR", 20, false, null, null, false, null, true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dialect.columnDefinition(invalid)).hasMessageContaining("非空整数");
        var original = new com.example.dbadmin.dto.ApiDtos.ColumnInfo("id", "INT", 10, false, null, 1, null, true, false);
        var changed = new com.example.dbadmin.dto.ApiDtos.ColumnDesign("id", "INT", null, false, null, "id", false, null, false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dialect.alterColumnSql("[dbo].[t]", "id", original, changed)).hasMessageContaining("IDENTITY");
    }
}
