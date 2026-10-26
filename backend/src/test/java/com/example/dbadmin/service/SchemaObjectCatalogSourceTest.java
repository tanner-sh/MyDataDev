package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialClob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SchemaObjectCatalogSourceTest {
    private final SchemaObjectCatalog catalog = new SchemaObjectCatalog(new AppProperties());
    private final Connection jdbc = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final DbConnection configured = new DbConnection(1L, "target", "oceanbase-oracle", "jdbc:test", "user", "",
            "dev", false, Instant.now(), Instant.now());

    @Test
    void readsTheWholeLongSingleClobBeforeMcpCharacterPaging() throws Exception {
        String source = "CREATE OR REPLACE PROCEDURE SP_LONG AS BEGIN " + "NULL; /* 正文 */ ".repeat(10_000) + "END SP_LONG;";
        sourceResult(new SerialClob(source.toCharArray()));

        assertThat(readSource()).isEqualTo(source).endsWith("END SP_LONG;");
        verify(jdbc).prepareStatement("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual");
        verify(statement).setObject(1, "PROCEDURE");
        verify(statement).setObject(2, "SP_LONG");
        verify(statement).setObject(3, "ETL");
        verify(rows).close();
        verify(statement).close();
    }

    @Test
    void rejectsAnOversizedClobInsteadOfReportingAPrefixAsCompleteSource() throws Exception {
        Clob oversized = mock(Clob.class);
        when(oversized.length()).thenReturn(2_000_001L);
        sourceResult(oversized);

        assertThatThrownBy(this::readSource).isInstanceOf(IllegalStateException.class).hasMessageContaining("超过 200 万字符");
        verify(oversized, never()).getSubString(anyLong(), anyInt());
        verify(rows).close();
        verify(statement).close();
    }

    private void sourceResult(Object source) throws Exception {
        when(jdbc.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1)).thenReturn(source);
    }

    private String readSource() throws Exception {
        return catalog.source(jdbc, configured, new DialectRegistry().dialectFor(configured), SchemaObjectKind.PROCEDURE,
                new SchemaObjectCatalog.CatalogObject("ETL", "SP_LONG", "1", "", "VALID"));
    }
}
