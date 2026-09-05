package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ExportService exportService;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE export_values(id INT PRIMARY KEY, name VARCHAR(40), note VARCHAR(80), active BOOLEAN, empty VARCHAR(10))");
            connection.createStatement().execute("INSERT INTO export_values VALUES (1, 'Alice', 'a, \"quoted\" value', TRUE, NULL)");
            connection.createStatement().execute("CREATE SCHEMA archive");
            connection.createStatement().execute("CREATE TABLE archive.export_values(id INT PRIMARY KEY, name VARCHAR(40))");
            connection.createStatement().execute("INSERT INTO archive.export_values VALUES (2, 'Archive')");
        }
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(new DbConnection(
                1L, "h2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()
        ));
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), anyString())).thenAnswer(invocation -> {
            var connection = DriverManager.getConnection(url, "sa", "");
            connection.setSchema(invocation.getArgument(1, String.class));
            return connection;
        });
        AppProperties properties = new AppProperties();
        properties.getSql().setTimeoutSeconds(10);
        exportService = new ExportService(
                connections,
                new DialectRegistry(),
                properties,
                mapper,
                new SqlStatementClassifier(),
                new SqlScriptSplitter(),
                mock(AuditRepository.class),
                mock(SqlHistoryRepository.class),
                new ExecutionGuard()
        );
    }

    @Test
    void streamsCsvWithUtf8BomAndEscapedValues() throws Exception {
        String body = export("select * from export_values", "csv");

        assertThat(body).startsWith("\uFEFF\"id\",\"name\",\"note\",\"active\",\"empty\"");
        assertThat(body).contains("\"1\",\"Alice\",\"a, \"\"quoted\"\" value\",\"true\",\"\"");
    }

    @Test
    void streamsJsonAsColumnDescriptorsAndPositionalRows() throws Exception {
        JsonNode body = mapper.readTree(export("select id as duplicate, name as duplicate from export_values", "json"));

        assertThat(body.get("columns").get(0).asText()).isEqualTo("duplicate");
        assertThat(body.get("columns").get(1).asText()).isEqualTo("duplicate");
        assertThat(body.get("rows").get(0).get(0).asInt()).isEqualTo(1);
        assertThat(body.get("rows").get(0).get(1).asText()).isEqualTo("Alice");
        assertThat(body.get("truncated").asBoolean()).isFalse();
        assertThat(body.get("maxRows").asInt()).isEqualTo(ExportService.EXPORT_MAX_ROWS);
    }

    @Test
    void exportsFromRequestedSchema() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        exportService.stream(1L, "select name from export_values", "json", "admin", null, "archive", output);

        JsonNode body = mapper.readTree(output.toByteArray());
        assertThat(body.get("rows").get(0).get(0).asText()).isEqualTo("Archive");
    }

    @Test
    void streamsBigIntAndDecimalAsExactJsonStrings() throws Exception {
        JsonNode body = mapper.readTree(export(
                "select cast(9007199254740993 as bigint) as big_id, cast(1234567890.123456789 as decimal(30, 9)) as amount",
                "json"
        ));

        assertThat(body.get("rows").get(0).get(0).asText()).isEqualTo("9007199254740993");
        assertThat(body.get("rows").get(0).get(1).asText()).isEqualTo("1234567890.123456789");
        assertThat(body.get("rows").get(0).get(0).isTextual()).isTrue();
        assertThat(body.get("rows").get(0).get(1).isTextual()).isTrue();
    }

    @Test
    void truncatesVeryLargeVarcharCellsInExports() throws Exception {
        JsonNode body = mapper.readTree(export("select repeat('x', 120000) as note", "json"));
        String note = body.get("rows").get(0).get(0).asText();

        assertThat(note).hasSizeLessThanOrEqualTo(100_000).contains("文本已截断");
    }

    @Test
    void streamsSqlSnapshotAndMakesDuplicateColumnNamesUnique() throws Exception {
        String body = export("select id as \"value\", name as \"value\" from export_values", "sql");

        assertThat(body).isEqualTo("INSERT INTO \"public\".\"export_values\" (\"value\", \"value_2\") VALUES (1, 'Alice');\n");
    }

    @Test
    void sqlExportKeepsCompleteClobAndBinaryValues() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        exportService.stream(
                1L,
                "select cast(repeat('x', 120000) as clob) as note, cast(X'00FF5C' as blob) as payload",
                "sql",
                "admin",
                null,
                null,
                java.util.List.of("copied_values"),
                output
        );

        String body = output.toString(StandardCharsets.UTF_8);
        assertThat(body).hasSizeGreaterThan(120_000)
                .contains("X'00ff5c'")
                .doesNotContain("已截断")
                .doesNotContain("<BLOB");
    }

    @Test
    void usesExplicitQualifiedTargetForSqlExport() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        exportService.stream(
                1L,
                "select id, name from export_values",
                "sql",
                "admin",
                null,
                null,
                java.util.List.of("archive", "copied_values"),
                output
        );

        assertThat(output.toString(StandardCharsets.UTF_8))
                .startsWith("INSERT INTO \"archive\".\"copied_values\" (\"id\", \"name\") VALUES");
    }

    @Test
    void rejectsAmbiguousSqlTargetInsteadOfUsingGenericTableName() {
        assertThatThrownBy(() -> export(
                "select current_values.id, archived_values.name from export_values current_values "
                        + "join archive.export_values archived_values on archived_values.id = current_values.id",
                "sql"
        ))
                .isInstanceOf(com.example.dbadmin.api.ApiProblemException.class)
                .hasMessageContaining("指定目标表");
    }

    @Test
    void streamsXmlWithEscapedLabelsAndValues() throws Exception {
        String body = export("select id as \"1 id\", '<tag>&' as note from export_values", "xml");

        assertThat(body).contains("<column name=\"1 id\">1</column>");
        assertThat(body).contains("<column name=\"note\">&lt;tag&gt;&amp;</column>");
        assertThat(body).contains("<truncated>false</truncated>");
    }

    @Test
    void rejectsWritesAndMultipleStatementsBeforeOpeningAnExportStream() {
        assertThatThrownBy(() -> export("update export_values set name = 'x'", "csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持单条查询");
        assertThatThrownBy(() -> export("select 1; select 2", "csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持单条查询");
    }

    @Test
    void rejectsUnsupportedFormat() {
        assertThatThrownBy(() -> export("select * from export_values", "parquet"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的导出格式：parquet");
    }

    @Test
    void exportsAWorkbookExcelCanOpenWithTypedCells() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exportService.export(1L, "select id, name, active from export_values", "xlsx", "admin", output);

        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(parts).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml");
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        // 第一行是表头，数值列按数字写，文本列按文本写。
        assertThat(sheet).contains("preserve\">id</t>");
        assertThat(sheet).contains("<c r=\"A2\" t=\"n\"><v>1</v></c>");
        assertThat(sheet).contains("preserve\">Alice</t>");
        assertThat(sheet).contains("<c r=\"C2\" t=\"b\"><v>1</v></c>");
    }

    @Test
    void preparesExportBeforeResponseAndReportsCsvTruncation() throws Exception {
        ExportService.PreparedExport prepared = exportService.prepare(
                1L, "select * from system_range(1, 10001)", "csv", "admin", null
        );
        try {
            assertThat(prepared.truncated()).isTrue();
            assertThat(prepared.size()).isPositive();
        } finally {
            prepared.discard();
        }
    }

    /**
     * Markdown 表格。
     *
     * <p>与前端 queryResultExport.ts 的 serializeMarkdown 写的必须是同一种表格 —— 两边任何一处
     * 转义规则分叉，同一份结果从「导出本批」和「重新查询并导出」两条路出来就会不一样。</p>
     */
    @Test
    void writesMarkdownTableWithNumericColumnsRightAligned() throws Exception {
        String markdown = export("select id, name from export_values order by id", "markdown");

        String[] lines = markdown.split("\n");
        assertThat(lines[0]).isEqualTo("| id | name |");
        // 数值列右对齐，和界面结果表格的规矩一致。
        assertThat(lines[1]).isEqualTo("| ---: | --- |");
        assertThat(lines[2]).isEqualTo("| 1 | Alice |");
    }

    @Test
    void escapesPipesAndFoldsNewlinesInMarkdown() throws Exception {
        String markdown = export(
                "select 'a|b' as piped, 'line1' || CHAR(10) || 'line2' as multiline from export_values", "markdown");

        String[] lines = markdown.split("\n");
        // 裸竖线会把整行的列数撑乱，后面所有列错位。
        assertThat(lines[2]).isEqualTo("| a\\|b | line1<br>line2 |");
        // 单元格不能跨行：表头 + 分隔行 + 一行数据。
        assertThat(lines).hasSize(3);
    }

    @Test
    void rendersNullAsAnEmptyMarkdownCell() throws Exception {
        String markdown = export("select id, empty from export_values", "markdown");

        assertThat(markdown.split("\n")[2]).isEqualTo("| 1 |  |");
    }

    private String export(String sql, String format) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exportService.export(1L, sql, format, "admin", output);
        return output.toString(StandardCharsets.UTF_8);
    }

    /**
     * 产品自己生成的查询走另一道门：不做「单条查询」的文本校验，参数由调用方绑定。上限、
     * 只读作用域与格式写出都还是同一套实现。
     */
    @Test
    void exportsAGeneratedQueryWithBoundParameters() throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();

        ExportService.PreparedExport prepared = exportService.prepareGenerated(
                1L, "SELECT id, name FROM export_values WHERE name = ?",
                statement -> statement.setString(1, "Alice"),
                "csv", "admin", null, null, "table:export_values");
        prepared.writeTo(output);

        String csv = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("Alice");
        assertThat(prepared.truncated()).isFalse();
    }

    /** 绑定的值不能改变语句结构 —— 导出走的也是同一条注入防线。 */
    @Test
    void doesNotLetABoundValueChangeTheGeneratedStatement() throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();

        exportService.prepareGenerated(
                1L, "SELECT id FROM export_values WHERE name = ?",
                statement -> statement.setString(1, "x' OR '1'='1"),
                "csv", "admin", null, null, "table:export_values").writeTo(output);

        // 只有表头，没有数据行。
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8).trim().lines().count()).isEqualTo(1);
    }
}
