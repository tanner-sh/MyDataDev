package com.example.dbadmin.service;

import com.example.dbadmin.core.H2Dialect;
import com.example.dbadmin.core.OracleDialect;
import com.example.dbadmin.dto.ApiDtos.ResultSourceTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 文本兜底认来源表。
 *
 * <p>这条路只在驱动一列都不报表名时才走（Oracle）。认错表等于把改动写进另一张表，所以这里
 * 的用例大半是「必须认不出来」：宁可不给编辑，也不能给错。</p>
 */
class SelectSourceTableTest {
    @Test
    void readsThePlainSingleTableSelect() {
        assertThat(parse("select * from BD_ACCASOA", "FBIP81").nameParts())
                .containsExactly("FBIP81", "BD_ACCASOA");
        assertThat(parse("select a, b from t where x = 1 order by a", null).nameParts())
                .containsExactly("T");
    }

    @Test
    void keepsAnExplicitSchemaOverTheCurrentOne() {
        assertThat(parse("select * from OTHER.BD_ACCASOA", "FBIP81").nameParts())
                .containsExactly("OTHER", "BD_ACCASOA");
    }

    @Test
    void foldsUnquotedNamesTheWayTheDatabaseStoresThem() {
        // 用户写小写，Oracle 存的是大写。按原样拿去查主键会一无所获，一张有主键的表于是
        // 被报成「没有主键」—— 折叠是这条兜底能不能真的用起来的关键一步。
        assertThat(parse("select * from bd_accasoa", null).nameParts()).containsExactly("BD_ACCASOA");
        // 加了引号的名字是用户明确写下的大小写，不能折。
        assertThat(parse("select * from \"bd_accasoa\"", null).nameParts()).containsExactly("bd_accasoa");
        assertThat(parse("select * from \"My Table\"", null).nameParts()).containsExactly("My Table");
        // 折叠规则跟着方言走。
        assertThat(SelectSourceTable.parse("select * from Customers", "h2", null, new H2Dialect()).nameParts())
                .containsExactly("CUSTOMERS");
    }

    @Test
    void refusesAnythingWithMoreThanOneSource() {
        assertThat(parse("select * from a join b on a.id = b.id", null)).isNull();
        assertThat(parse("select * from a, b", null)).isNull();
        assertThat(parse("select * from (select * from a) x", null)).isNull();
        assertThat(parse("with c as (select 1 from dual) select * from c", null)).isNull();
        assertThat(parse("select * from a union all select * from b", null)).isNull();
    }

    @Test
    void refusesResultsWhoseRowsNoLongerMatchTableRows() {
        // 去重和分组之后，结果里的一行不再对应表里的一行，定位不到该改哪一行。
        assertThat(parse("select distinct a from t", null)).isNull();
        assertThat(parse("select a, count(*) from t group by a", null)).isNull();
    }

    @Test
    void refusesNonSelectAndUnparseableText() {
        assertThat(parse("update t set a = 1", null)).isNull();
        assertThat(parse("select * from", null)).isNull();
        assertThat(parse("", null)).isNull();
        assertThat(parse(null, null)).isNull();
    }

    @Test
    void refusesSourcesThatAreNotAPlainTableName() {
        // dblink 与表函数指向的都不是本地的那张表。
        assertThat(parse("select * from t@remote", null)).isNull();
        assertThat(parse("select * from table(f(1))", null)).isNull();
    }

    private static ResultSourceTable parse(String sql, String defaultSchema) {
        return SelectSourceTable.parse(sql, "oracle", defaultSchema, new OracleDialect());
    }
}
