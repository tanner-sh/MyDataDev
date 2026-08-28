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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
        assertThatThrownBy(() -> export("select * from export_values", "xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的导出格式：xlsx");
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

    private String export(String sql, String format) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exportService.export(1L, sql, format, "admin", output);
        return output.toString(StandardCharsets.UTF_8);
    }
}
