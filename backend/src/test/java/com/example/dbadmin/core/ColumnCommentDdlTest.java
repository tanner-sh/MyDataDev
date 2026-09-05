package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列注释的 DDL。
 *
 * <p>注释是这个产品最依赖的元数据（资源树、结构对比、AI 的结构搜索都在读它），此前却只读
 * 不可写。两家写法完全不同：标准 SQL 用独立的 {@code COMMENT ON}，MySQL 系写在列定义里 ——
 * 后者还有个陷阱：{@code MODIFY COLUMN} 要重写整个列定义，不带上原注释就等于把它删了。</p>
 */
class ColumnCommentDdlTest {
    private static final PostgreSqlDialect POSTGRES = new PostgreSqlDialect();
    private static final MySqlDialect MYSQL = new MySqlDialect();

    @Test
    void writesCommentOnForStandardDialects() {
        List<String> sql = POSTGRES.createTableSql("shop", "orders", design(
                column("id", "BIGINT", null, "主键"),
                column("note", "VARCHAR", null, null)));

        assertThat(sql).anySatisfy(line -> assertThat(line).isEqualTo(
                "COMMENT ON COLUMN \"shop\".\"orders\".\"id\" IS '主键'"));
        // 没写注释的列不该生成一条空注释语句。
        assertThat(sql).noneSatisfy(line -> assertThat(line).contains("\"note\" IS"));
    }

    @Test
    void putsTheCommentInsideTheColumnDefinitionForMysql() {
        List<String> sql = MYSQL.createTableSql("shop", "orders", design(column("id", "BIGINT", null, "主键")));

        assertThat(sql).first().asString().contains("`id` BIGINT COMMENT '主键'");
        assertThat(sql).noneSatisfy(line -> assertThat(line).contains("COMMENT ON"));
    }

    @Test
    void emitsACommentStatementWhenOnlyTheCommentChanged() {
        ObjectDetail original = table(existing("id", "BIGINT", "旧注释"));

        List<String> sql = POSTGRES.alterTableSql("shop", "orders", original,
                design(edit("id", "BIGINT", "id", "新注释")));

        assertThat(sql).containsExactly("COMMENT ON COLUMN \"shop\".\"orders\".\"id\" IS '新注释'");
    }

    /** 空串是「清空注释」，与「这次不改」必须分开 —— 否则改一次类型就会把注释抹掉。 */
    @Test
    void tellsClearingApartFromNotSubmitting() {
        ObjectDetail original = table(existing("id", "BIGINT", "旧注释"));

        assertThat(POSTGRES.alterTableSql("shop", "orders", original, design(edit("id", "BIGINT", "id", ""))))
                .containsExactly("COMMENT ON COLUMN \"shop\".\"orders\".\"id\" IS ''");
        assertThat(POSTGRES.alterTableSql("shop", "orders", original, design(edit("id", "BIGINT", "id", null))))
                .isEmpty();
    }

    /**
     * MySQL 的 MODIFY COLUMN 要重写整个列定义。只改类型、没提交注释时必须把原注释填回去，
     * 否则一次改类型就顺手删掉了注释 —— 而用户完全不会预期这件事。
     */
    @Test
    void keepsTheExistingCommentWhenMysqlRewritesTheColumn() {
        ObjectDetail original = table(existing("id", "BIGINT", "主键"));

        List<String> sql = MYSQL.alterTableSql("shop", "orders", original, design(edit("id", "VARCHAR", "id", null)));

        assertThat(sql).singleElement().asString().contains("COMMENT '主键'");
    }

    @Test
    void addsTheCommentForANewColumnToo() {
        ObjectDetail original = table(existing("id", "BIGINT", null));
        TableDesignRequest request = design(edit("id", "BIGINT", "id", null), column("note", "VARCHAR", null, "备注"));

        assertThat(POSTGRES.alterTableSql("shop", "orders", original, request))
                .anySatisfy(line -> assertThat(line).startsWith("ALTER TABLE").contains("ADD COLUMN"))
                .anySatisfy(line -> assertThat(line).isEqualTo(
                        "COMMENT ON COLUMN \"shop\".\"orders\".\"note\" IS '备注'"));
    }

    /** 注释里的单引号必须转义 —— 这份 DDL 是拼出来给用户执行的。 */
    @Test
    void escapesQuotesInsideTheComment() {
        List<String> sql = POSTGRES.createTableSql("shop", "orders", design(column("id", "BIGINT", null, "O'Brien 的单号")));

        assertThat(sql).anySatisfy(line -> assertThat(line).contains("'O''Brien 的单号'"));
    }

    /** SQLite 没有注释，SQL Server 要走扩展属性：两家都明确不支持，而不是给半个实现。 */
    @Test
    void staysSilentWhereCommentsAreNotSupported() {
        assertThat(new SqliteDialect().supportsColumnComments()).isFalse();
        assertThat(new SqlServerDialect().supportsColumnComments()).isFalse();
        assertThat(new SqliteDialect().capabilities().columnComments()).isFalse();
        assertThat(POSTGRES.capabilities().columnComments()).isTrue();
    }

    private static TableDesignRequest design(ColumnDesign... columns) {
        return new TableDesignRequest("shop", "orders", List.of(columns), List.of(), List.of(), null);
    }

    private static ColumnDesign column(String name, String type, Integer size, String remarks) {
        return new ColumnDesign(name, type, size, true, null, null, false, remarks);
    }

    private static ColumnDesign edit(String name, String type, String originalName, String remarks) {
        return new ColumnDesign(name, type, null, true, null, originalName, false, remarks);
    }

    private static ColumnInfo existing(String name, String type, String remarks) {
        return new ColumnInfo(name, type, 0, true, remarks, 1, null);
    }

    private static ObjectDetail table(ColumnInfo... columns) {
        return new ObjectDetail("shop", "orders", "TABLE", List.of(columns), List.of(), List.of(), null);
    }
}
