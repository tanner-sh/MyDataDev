package com.example.dbadmin.service;

import com.example.dbadmin.core.MySqlDialect;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataImportServiceTest {
    private static final MySqlDialect DIALECT = new MySqlDialect();
    private static final Set<String> COLUMNS = new LinkedHashSet<>(java.util.List.of("id", "name", "note"));

    private static String convert(String csv, Set<String> columns) throws Exception {
        StringWriter out = new StringWriter();
        try (CsvStreamReader reader = new CsvStreamReader(new StringReader(csv))) {
            DataImportService.convert(reader, out, DIALECT, "shop", "orders", columns, "orders.csv");
        }
        return out.toString();
    }

    /**
     * 含反斜杠的值不能写成带引号的字面量：MySQL 默认把反斜杠当转义符（以反斜杠结尾会吃掉
     * 闭合引号，后面的内容跑到字符串外面变成 SQL），而开了 NO_BACKSLASH_ESCAPES 又不当转义符
     * ——同一份脚本在两种会话下会得到两个不同的值。十六进制字面量不参与任何转义，两种模式下
     * 都还原成同一串字节。
     */
    @Test
    void writesBackslashValuesAsHexSoTheyDoNotDependOnSqlMode() throws Exception {
        String sql = convert("id,note\n1,\"c:\\tmp\"\n", COLUMNS);

        assertThat(sql).contains("('1', _utf8mb4 0x" + hex("c:\\tmp") + ")");
        // 既没有反斜杠也没有引号留在脚本里，转义规则的分歧就无从谈起。
        assertThat(sql).doesNotContain("'c:");
    }

    @Test
    void keepsOrdinaryValuesReadableRatherThanHexEncodingEverything() throws Exception {
        String sql = convert("id,name\n1,张三\n", COLUMNS);

        assertThat(sql).contains("('1', '张三')");
        assertThat(sql).doesNotContain("0x");
    }

    @Test
    void injectionShapedValuesCannotBreakOutOfTheStatement() throws Exception {
        String csv = "id,note\n1,\"x\\',(SELECT 1)); DROP TABLE orders; -- \"\n";
        String sql = convert(csv, COLUMNS);

        // 整段内容以十六进制原样落进一个值里，语句数量不变。
        assertThat(sql).contains("_utf8mb4 0x" + hex("x\\',(SELECT 1)); DROP TABLE orders; -- "));
        assertThat(sql).doesNotContain("DROP TABLE orders;");
        assertThat(sql.lines().filter(line -> line.startsWith("INSERT INTO")).count()).isEqualTo(1L);
    }

    /** 单引号仍然走普通字面量，只翻倍就够了 —— 引号的含义不随 sql_mode 变化。 */
    @Test
    void quotesStillUseOrdinaryDoubling() throws Exception {
        String sql = convert("id,note\n1,\"O'Brien\"\n", COLUMNS);

        assertThat(sql).contains("('1', 'O''Brien')");
    }

    private static String hex(String value) {
        return java.util.HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void keepsFileNameCommentOnASingleLine() throws Exception {
        StringWriter out = new StringWriter();
        try (CsvStreamReader reader = new CsvStreamReader(new StringReader("id,name\n1,a\n"))) {
            DataImportService.convert(reader, out, DIALECT, "shop", "orders", COLUMNS,
                    "evil.csv\nDROP TABLE orders;\n--");
        }
        String sql = out.toString();

        assertThat(sql.lines().filter(line -> !line.startsWith("--") && !line.isBlank()))
                .allSatisfy(line -> assertThat(line).doesNotContain("DROP TABLE"));
        assertThat(sql).contains("-- 由 evil.csv DROP TABLE orders; -- 转换而来的导入脚本");
    }

    @Test
    void generatesQualifiedBatchInsert() throws Exception {
        String sql = convert("id,name\n1,张三\n2,李四\n", COLUMNS);
        assertThat(sql).contains("INSERT INTO `shop`.`orders` (`id`, `name`) VALUES");
        assertThat(sql).contains("('1', '张三')");
        assertThat(sql).contains("('2', '李四')");
        // 一条语句装下两行，中间用逗号连接，末尾一个分号。
        assertThat(sql.chars().filter(ch -> ch == ';').count()).isEqualTo(1);
    }

    @Test
    void matchesHeaderCaseInsensitivelyAndUsesTableCasing() throws Exception {
        // 目标表字段是 id/name，CSV 里写成 ID/NAME 也要能对上，且生成的语句用表里的大小写。
        String sql = convert("ID,NAME\n1,a\n", COLUMNS);
        assertThat(sql).contains("(`id`, `name`)");
    }

    @Test
    void stripsUtf8BomFromFirstHeader() throws Exception {
        String sql = convert("﻿id,name\n1,a\n", COLUMNS);
        assertThat(sql).contains("(`id`, `name`)");
    }

    @Test
    void emptyFieldBecomesNullAndQuotesAreEscaped() throws Exception {
        String sql = convert("id,note\n1,\n2,\"O'Brien\"\n", COLUMNS);
        assertThat(sql).contains("('1', NULL)");
        assertThat(sql).contains("('2', 'O''Brien')");
    }

    @Test
    void startsANewStatementEveryBatch() throws Exception {
        StringBuilder csv = new StringBuilder("id\n");
        int rows = DataImportService.ROWS_PER_STATEMENT + 1;
        for (int index = 0; index < rows; index++) csv.append(index).append('\n');
        String sql = convert(csv.toString(), COLUMNS);
        // 201 行 → 200 行一条 + 剩下 1 行一条。
        assertThat(sql.split("INSERT INTO", -1).length - 1).isEqualTo(2);
        assertThat(sql.chars().filter(ch -> ch == ';').count()).isEqualTo(2);
    }

    @Test
    void rejectsHeaderColumnMissingFromTable() {
        assertThatThrownBy(() -> convert("id,nope\n1,2\n", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("id");
    }

    @Test
    void rejectsRowWithWrongFieldCountAndReportsFileLineNumber() {
        assertThatThrownBy(() -> convert("id,name\n1,a\n2\n", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class)
                // 表头是第 1 行，出错的是文件里的第 3 行。
                .hasMessageContaining("第 3 行");
    }

    @Test
    void rejectsHeaderOnlyFile() {
        assertThatThrownBy(() -> convert("id,name\n", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有数据行");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> convert("", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少表头");
    }

    @Test
    void scriptNameKeepsSourceAndTargetButStaysASqlFile() {
        assertThat(DataImportService.importScriptName("orders.csv", "orders")).isEqualTo("orders-导入-orders.sql");
        assertThat(DataImportService.importScriptName(null, "t")).isEqualTo("import-导入-t.sql");
        // 路径分隔符与非法字符不能带进文件名。
        assertThat(DataImportService.importScriptName("../../etc/passwd.csv", "t")).isEqualTo("passwd-导入-t.sql");
        assertThat(DataImportService.importScriptName("a/b:c*.csv", "t")).doesNotContain("/", ":", "*");
    }

    @Test
    void scriptNameStaysWithinFileSystemLimits() {
        String name = DataImportService.importScriptName("x".repeat(400) + ".csv", "orders");
        assertThat(name).hasSizeLessThanOrEqualTo(200).endsWith(".sql");
    }

    /**
     * 导入最常见的场景是「上次导错了，改完再来一次」，而此前只会生成裸 INSERT：目标表里已有
     * 同主键的行时整批失败。各家的写法完全不同，所以收敛在方言里。
     */
    @Test
    void writesSkipAndUpsertClausesPerDialect() throws Exception {
        var columns = java.util.List.of("id", "name");
        var keys = java.util.List.of("id");

        var pgSkip = new com.example.dbadmin.core.PostgreSqlDialect().importConflictStyle("SKIP", columns, keys);
        var pgUpsert = new com.example.dbadmin.core.PostgreSqlDialect().importConflictStyle("UPSERT", columns, keys);
        var mysqlSkip = new com.example.dbadmin.core.MySqlDialect().importConflictStyle("SKIP", columns, keys);
        var mysqlUpsert = new com.example.dbadmin.core.MySqlDialect().importConflictStyle("UPSERT", columns, keys);

        assertThat(pgSkip.conflictClause()).isEqualTo(" ON CONFLICT DO NOTHING");
        assertThat(pgUpsert.conflictClause()).isEqualTo(" ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\"");
        // MySQL 改的是语句前缀，不是后缀。
        assertThat(mysqlSkip.insertKeyword()).isEqualTo("INSERT IGNORE INTO");
        assertThat(mysqlUpsert.conflictClause()).isEqualTo(" ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)");
    }

    /** PostgreSQL 的更新档必须给出冲突目标；猜一个列去当主键，猜错就是覆盖别人的数据。 */
    @Test
    void refusesUpsertWithoutAKey() {
        assertThat(new com.example.dbadmin.core.PostgreSqlDialect()
                .importConflictStyle("UPSERT", java.util.List.of("a"), java.util.List.of())).isNull();
        // 没有实现这套写法的方言一律返回 null，由上层给出说得清楚的错误。
        assertThat(new com.example.dbadmin.core.OracleDialect()
                .importConflictStyle("SKIP", java.util.List.of("a"), java.util.List.of("a"))).isNull();
    }

    /** 除主键外没有别的列可更新时，「更新已存在」实际就等于「跳过」。 */
    @Test
    void degradesUpsertToSkipWhenEveryColumnIsPartOfTheKey() {
        var style = new com.example.dbadmin.core.PostgreSqlDialect()
                .importConflictStyle("UPSERT", java.util.List.of("id"), java.util.List.of("id"));

        assertThat(style.conflictClause()).isEqualTo(" ON CONFLICT (\"id\") DO NOTHING");
    }

    @Test
    void putsTheConflictClauseAtTheEndOfEachStatement() throws Exception {
        java.io.StringWriter out = new java.io.StringWriter();
        try (CsvStreamReader reader = new CsvStreamReader(new java.io.StringReader("id,name\n1,A\n"))) {
            DataImportService.convert(reader, out, DIALECT, "shop", "orders", COLUMNS, "orders.csv",
                    new com.example.dbadmin.core.DatabaseDialect.ImportConflictStyle(
                            "INSERT INTO", " ON CONFLICT DO NOTHING"));
        }

        assertThat(out.toString()).contains("ON CONFLICT DO NOTHING;");
    }
}
