package com.example.dbadmin.service;

import com.example.dbadmin.core.H2Dialect;
import org.junit.jupiter.api.Test;

import java.sql.ResultSetMetaData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结果来源解析。这里只有一条容易说错的规则：解析不出来源表时，「驱动没报表名」和
 * 「结果来自多张表」是两件完全不同的事，界面上给的说明必须分得清。
 */
class ResultSetSourceResolverTest {
    @Test
    void readsTheSingleSourceTableFromColumnMetadata() throws Exception {
        ResultSetMetaData metadata = metadata("CUSTOMERS", "CUSTOMERS");

        assertThat(ResultSetSourceResolver.resolve(metadata, new H2Dialect()).nameParts())
                .containsExactly("CUSTOMERS");
    }

    @Test
    void reportsADriverThatNamesNoTableAtAll() throws Exception {
        // Oracle 的 ojdbc 就是这样：每一列的 getTableName() 都是空串，一条普通的单表查询
        // 同样解析不出来源表。说成「不是来自单张表」是与事实相反的话。
        assertThat(ResultSetSourceResolver.classifyUnknownSource(metadata("", ""), 2))
                .isEqualTo(ResultSetSourceResolver.UnknownSource.NO_TABLE_NAMES);
        assertThat(ResultSetSourceResolver.classifyUnknownSource(metadata(null, null), 2))
                .isEqualTo(ResultSetSourceResolver.UnknownSource.NO_TABLE_NAMES);
    }

    @Test
    void reportsAResultThatSpansSeveralTables() throws Exception {
        assertThat(ResultSetSourceResolver.classifyUnknownSource(metadata("CUSTOMERS", "ORDERS"), 2))
                .isEqualTo(ResultSetSourceResolver.UnknownSource.MULTIPLE_TABLES);
    }

    @Test
    void treatsAFailingDriverAsOneThatNamesNoTable() throws Exception {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getTableName(1)).thenThrow(new java.sql.SQLException("不支持"));

        assertThat(ResultSetSourceResolver.classifyUnknownSource(metadata, 1))
                .isEqualTo(ResultSetSourceResolver.UnknownSource.NO_TABLE_NAMES);
    }

    private static ResultSetMetaData metadata(String... tableNames) throws Exception {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(tableNames.length);
        for (int index = 0; index < tableNames.length; index++) {
            when(metadata.getTableName(index + 1)).thenReturn(tableNames[index]);
        }
        return metadata;
    }
}
