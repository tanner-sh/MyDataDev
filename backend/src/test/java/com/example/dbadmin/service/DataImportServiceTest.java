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
}
