package com.example.dbadmin.service;

import com.example.dbadmin.core.OracleDialect;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import org.junit.jupiter.api.Test;

import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResultColumnSourceResolverTest {
    private List<ResultColumn> resolve(String sql, ResultSetMetaData metadata, String... labels) {
        var columns = IntStream.range(0, labels.length).mapToObj(index -> new ResultColumn("c" + (index + 1), labels[index], "VARCHAR2")).toList();
        return ResultColumnSourceResolver.resolve(columns, metadata, sql, "oracle", 7L, "APP", new OracleDialect());
    }

    @Test
    void resolvesOracleStarEvenWhenDriverOmitsTableNames() {
        var columns = resolve("select * from SIM_STOCKTRADE", mock(ResultSetMetaData.class), "CODE", "DEF15");
        assertThat(columns).allSatisfy(column -> {
            assertThat(column.source().connectionId()).isEqualTo(7);
            assertThat(column.source().schemaName()).isEqualTo("APP");
            assertThat(column.source().tableName()).isEqualTo("SIM_STOCKTRADE");
            assertThat(column.source().columnName()).isEqualTo(column.label());
        });
    }

    @Test
    void mapsAliasesByProjectionPositionRatherThanLabel() {
        var columns = resolve("select nickname as code, code as nickname from people", mock(ResultSetMetaData.class), "CODE", "NICKNAME");
        assertThat(columns.get(0).source().columnName()).isEqualTo("nickname");
        assertThat(columns.get(1).source().columnName()).isEqualTo("code");
    }

    @Test
    void distinguishesJoinSourcesAndExplicitSchemas() {
        var columns = resolve("select a.id as ID, b.id as ID from app.users a join audit.users b on a.id=b.id", mock(ResultSetMetaData.class), "ID", "ID");
        assertThat(columns.get(0).source().schemaName()).isEqualTo("app");
        assertThat(columns.get(1).source().schemaName()).isEqualTo("audit");
    }

    @Test
    void usesJdbcForUnqualifiedJoinColumnsOnlyWhenSourceIsUnambiguous() throws Exception {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getTableName(1)).thenReturn("CUSTOMERS");
        var columns = resolve("select name, id from orders o join customers c on o.customer_id=c.id", metadata, "NAME", "ID");
        assertThat(columns.get(0).source().tableName()).isEqualTo("customers");
        assertThat(columns.get(1).source()).isNull();
    }

    @Test
    void keepsQuotedIdentifiersAndDoesNotAssignExpressionsToColumns() {
        var columns = resolve("select t.\"Foo\" as name, upper(t.code) as code from \"CaseSchema\".\"CaseTable\" t", mock(ResultSetMetaData.class), "NAME", "CODE");
        assertThat(columns.get(0).source().schemaName()).isEqualTo("CaseSchema");
        assertThat(columns.get(0).source().tableName()).isEqualTo("CaseTable");
        assertThat(columns.get(0).source().columnName()).isEqualTo("Foo");
        assertThat(columns.get(1).source()).isNull();
    }

    @Test
    void leavesComplexOrMalformedProjectionsUnresolved() {
        for (String sql : List.of("select id from a union all select id from b", "with x as (select * from a) select id from x", "select id from (select id from a) x", "not a query")) {
            assertThat(resolve(sql, mock(ResultSetMetaData.class), "ID").get(0).source()).isNull();
        }
    }

    @Test
    void expandsStarBesideAnExpressionWithoutShiftingSources() {
        var columns = resolve("select a.*, 1 as ID from accounts a", mock(ResultSetMetaData.class), "ID", "CODE", "ID");
        assertThat(columns.get(0).source().columnName()).isEqualTo("ID");
        assertThat(columns.get(1).source().columnName()).isEqualTo("CODE");
        assertThat(columns.get(2).source()).isNull();
    }
}
