package com.example.dbadmin.service;

import com.example.dbadmin.core.H2Dialect;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨连接传输的读取端。
 *
 * <p>用真的 ResultSet 而不是 mock：这些用例校验的全是驱动交回来的类型长什么样（NULL 与空串
 * 分不分得开、布尔怎么落地、Timestamp 带不带那个 .0），mock 里这些正是被假设掉的部分。</p>
 */
class ResultSetRowSourceTest {
    private static final H2Dialect DIALECT = new H2Dialect();
    private static final Set<String> TARGET_COLUMNS =
            new LinkedHashSet<>(List.of("id", "name", "note", "flag", "created_at"));

    private interface WithResultSet {
        void run(ResultSet rs) throws Exception;
    }

    private static void query(String ddl, String insert, String select, WithResultSet body) throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            if (insert != null) statement.execute(insert);
            try (ResultSet rs = statement.executeQuery(select)) {
                body.run(rs);
            }
        }
    }

    /**
     * 这条是整个传输功能相对「导出 CSV 再导入」的核心价值：空字符串到了对面还是空字符串。
     *
     * <p>CSV 里空串和 NULL 都是一个空单元格，往返一趟之后空串会变成 NULL —— 在 NOT NULL 列上
     * 直接失败，在可空列上则要等到有人对账才发现。</p>
     */
    @Test
    void keepsEmptyStringsDistinctFromNull() throws Exception {
        query(
                "CREATE TABLE t (id INT, name VARCHAR(50), note VARCHAR(50))",
                "INSERT INTO t VALUES (1, '', NULL)",
                "SELECT id, name, note FROM t",
                rs -> {
                    ResultSetRowSource source = new ResultSetRowSource(rs);
                    assertThat(source.distinguishesNull()).isTrue();
                    assertThat(source.readRow()).containsExactly("ID", "NAME", "NOTE");
                    assertThat(source.readRow()).containsExactly("1", "", null);

                    // 生成的脚本里空串是 ''，只有 null 才是 NULL。
                    assertThat(DataImportService.literal(DIALECT, "", true)).isEqualTo("''");
                    assertThat(DataImportService.literal(DIALECT, null, true)).isEqualTo("NULL");
                    // 文件导入那条路的语义没有被改动：它分不清两者，所以一律按 NULL。
                    assertThat(DataImportService.literal(DIALECT, "", false)).isEqualTo("NULL");
                });
    }

    /**
     * 布尔一律写成 1/0。
     *
     * <p>它在各家的落点完全不同（MySQL 是 TINYINT、PostgreSQL 是 boolean、SQL Server 是 BIT），
     * 而 '1' 是四家都接受的字符串字面量；'TRUE' 在 MySQL 的 TINYINT 列上会直接报错。</p>
     */
    @Test
    void writesBooleansAsOneAndZero() throws Exception {
        query(
                "CREATE TABLE t (flag BOOLEAN)",
                "INSERT INTO t VALUES (TRUE), (FALSE)",
                "SELECT flag FROM t ORDER BY flag DESC",
                rs -> {
                    ResultSetRowSource source = new ResultSetRowSource(rs);
                    source.readRow();
                    assertThat(source.readRow()).containsExactly("1");
                    assertThat(source.readRow()).containsExactly("0");
                });
    }

    /** 时间列的文本形式必须与结果表格、执行计划一致：没有小数秒时不补那个 .0。 */
    @Test
    void formatsTimestampsWithTheSharedCellRule() throws Exception {
        query(
                "CREATE TABLE t (created_at TIMESTAMP)",
                "INSERT INTO t VALUES ('2025-09-18 14:30:00')",
                "SELECT created_at FROM t",
                rs -> {
                    ResultSetRowSource source = new ResultSetRowSource(rs);
                    source.readRow();
                    assertThat(source.readRow()).containsExactly("2025-09-18 14:30:00");
                });
    }

    /**
     * 二进制列在构造时就拒绝，而不是写一个各库解释不同的值进去。
     *
     * <p>报错要点出是哪几列 —— 用户接下来要做的事（在源查询里去掉它们）需要知道名字。</p>
     */
    @Test
    void refusesBinaryColumnsUpFrontAndNamesThem() throws Exception {
        query(
                "CREATE TABLE t (id INT, payload VARBINARY(32))",
                null,
                "SELECT id, payload FROM t",
                rs -> assertThatThrownBy(() -> new ResultSetRowSource(rs))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("PAYLOAD")
                        .hasMessageContaining("二进制"));
    }

    /** 列标签就是表头，所以源 SQL 里的 AS 就是列映射的手段。 */
    @Test
    void usesColumnLabelsAsTheHeaderSoAliasesRemapColumns() throws Exception {
        query(
                "CREATE TABLE t (id INT, title VARCHAR(50))",
                "INSERT INTO t VALUES (7, '订单')",
                "SELECT id, title AS name FROM t",
                rs -> {
                    StringWriter out = new StringWriter();
                    ResultSetRowSource source = new ResultSetRowSource(rs);
                    long rows = DataImportService.convert(source, out, DIALECT, "shop", "orders",
                            TARGET_COLUMNS, "连接「主库」的表 t", com.example.dbadmin.core.DatabaseDialect.ImportConflictStyle.plain());

                    assertThat(rows).isEqualTo(1);
                    assertThat(source.rowCount()).isEqualTo(1);
                    // 目标表里叫 name 的列被匹配上了，靠的是别名而不是原列名。
                    assertThat(out.toString()).contains("\"id\", \"name\"").contains("('7', '订单')");
                });
    }

    /** 源列在目标表里不存在时要报清楚是哪一列，而不是让脚本执行到一半失败。 */
    @Test
    void rejectsSourceColumnsMissingFromTheTargetTable() throws Exception {
        query(
                "CREATE TABLE t (id INT, missing_col VARCHAR(10))",
                "INSERT INTO t VALUES (1, 'x')",
                "SELECT id, missing_col FROM t",
                rs -> assertThatThrownBy(() -> DataImportService.convert(new ResultSetRowSource(rs),
                        new StringWriter(), DIALECT, "shop", "orders", TARGET_COLUMNS, "传输",
                        com.example.dbadmin.core.DatabaseDialect.ImportConflictStyle.plain()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("MISSING_COL"));
    }
}
