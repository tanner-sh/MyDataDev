package com.example.dbadmin.service.ai;

import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.ExecutionGuard;
import com.example.dbadmin.service.MetadataCacheService;
import com.example.dbadmin.service.MetadataService;
import com.example.dbadmin.service.ai.llm.LlmToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSchemaToolsTest {
    private static final long CONNECTION_ID = 7L;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void searchesTableAndColumnCommentsThenDescribesRealRelations() throws Exception {
        String url = "jdbc:h2:mem:ai-schema-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE APP_ROLE (ID BIGINT PRIMARY KEY, ROLE_NAME VARCHAR(80))");
            statement.execute("CREATE TABLE APP_USER (ID BIGINT PRIMARY KEY, DISPLAY_NAME VARCHAR(120), ROLE_ID BIGINT, CONSTRAINT FK_USER_ROLE FOREIGN KEY (ROLE_ID) REFERENCES APP_ROLE(ID))");
            statement.execute("COMMENT ON TABLE APP_USER IS '系统用户资料'");
            statement.execute("COMMENT ON COLUMN APP_USER.DISPLAY_NAME IS '用户名称'");
        }

        AiSchemaTools tools = tools(url);
        var search = tools.execute(CONNECTION_ID, "PUBLIC", call("search-1", "search_schema", """
                {"query":"查询用户名称","limit":10}
                """));

        assertThat(search.error()).isFalse();
        JsonNode searchResult = json.readTree(search.content());
        JsonNode user = findByName(searchResult.path("results"), "APP_USER");
        assertThat(user.path("comment").asText()).isEqualTo("系统用户资料");
        assertThat(findByName(user.path("matchingColumns"), "DISPLAY_NAME").path("comment").asText())
                .isEqualTo("用户名称");

        var describe = tools.execute(CONNECTION_ID, "PUBLIC", call("describe-1", "describe_objects", """
                {"names":["APP_USER"]}
                """));

        assertThat(describe.error()).isFalse();
        JsonNode object = json.readTree(describe.content()).path("objects").path(0);
        assertThat(object.path("error").asText()).withFailMessage(describe.content()).isBlank();
        assertThat(findByName(object.path("columns"), "DISPLAY_NAME").path("type").asText()).isNotBlank();
        assertThat(object.path("importedKeys").toString()).containsIgnoringCase("APP_ROLE");

        var related = tools.execute(CONNECTION_ID, "PUBLIC", call("related-1", "find_related_objects", """
                {"names":["APP_USER"]}
                """));
        assertThat(related.error()).isFalse();
        assertThat(json.readTree(related.content()).path("relatedObjects").toString()).containsIgnoringCase("APP_ROLE");

        var validation = tools.execute(CONNECTION_ID, "PUBLIC", call("validate-1", "validate_sql", """
                {"sql":"SELECT DISPLAY_NAME FROM APP_USER"}
                """));
        assertThat(json.readTree(validation.content()).path("valid").asBoolean()).isTrue();
    }

    @Test
    void refusesToDescribeOrReadDdlOutsideTheBoundSchema() throws Exception {
        String url = "jdbc:h2:mem:ai-schema-tools-scope;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE PUBLIC.VISIBLE_TABLE (ID BIGINT PRIMARY KEY)");
            statement.execute("CREATE SCHEMA PRIVATE_DATA");
            statement.execute("CREATE TABLE PRIVATE_DATA.SECRET_TABLE (ID BIGINT PRIMARY KEY)");
        }

        AiSchemaTools tools = tools(url);
        var describe = tools.execute(CONNECTION_ID, "PUBLIC", call("describe-2", "describe_objects", """
                {"names":["PRIVATE_DATA.SECRET_TABLE"]}
                """));
        var ddl = tools.execute(CONNECTION_ID, "PUBLIC", call("ddl-1", "get_object_ddl", """
                {"name":"PRIVATE_DATA.SECRET_TABLE"}
                """));

        assertThat(describe.content()).contains("不在当前命名空间").doesNotContain("SECRET_TABLE (ID");
        assertThat(ddl.error()).isTrue();
        assertThat(ddl.content()).contains("不在当前命名空间").doesNotContain("CREATE TABLE");
    }

    @Test
    void usesBusinessGlossaryToFindObjectsWithUnrelatedPhysicalNames() throws Exception {
        String url = "jdbc:h2:mem:ai-schema-tools-glossary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE FACT_2026_X9 (ID BIGINT PRIMARY KEY, TOTAL_AMOUNT DECIMAL(18,2))");
        }
        AiBusinessTerm businessTerm = new AiBusinessTerm(1, CONNECTION_ID, "订单", List.of("交易单"),
                List.of("FACT_2026_X9"), "订单事实表");

        AiSchemaTools tools = tools(url, List.of(businessTerm));
        var search = tools.execute(CONNECTION_ID, "PUBLIC", call("search-glossary", "search_schema", """
                {"query":"统计订单金额","limit":10}
                """));

        JsonNode result = json.readTree(search.content());
        assertThat(findByName(result.path("results"), "FACT_2026_X9")).isNotNull();
        assertThat(result.path("glossaryMatches").path(0).path("term").asText()).isEqualTo("订单");
    }

    /**
     * 历史里带着真实业务值。抹不干净，这条工具就等于把「只发结构」这一档的承诺作废了。
     */
    @Test
    void returnsQueryShapesFromHistoryWithoutLiteralsAndSkipsWrites() throws Exception {
        String url = "jdbc:h2:mem:ai-history-tool;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE APP_ROLE (ID BIGINT PRIMARY KEY, ROLE_NAME VARCHAR(80))");
            statement.execute("CREATE TABLE APP_USER (ID BIGINT PRIMARY KEY, DISPLAY_NAME VARCHAR(120), ROLE_ID BIGINT)");
        }

        var result = tools(url).execute(CONNECTION_ID, "PUBLIC", call("h-1", "search_query_history", """
                {"tables":["APP_USER"],"keywords":"角色"}
                """));

        assertThat(result.error()).isFalse();
        JsonNode queries = json.readTree(result.content()).path("queries");
        assertThat(queries).hasSize(1);
        assertThat(queries.path(0).path("sql").asText())
                .contains("JOIN APP_ROLE")
                .contains("ROLE_NAME = ?")
                .doesNotContain("管理员");
        assertThat(queries.path(0).path("tables")).hasSize(2);
        // 写语句不是「怎么查」的参考，历史里有也不给模型看。
        assertThat(result.content()).doesNotContain("DELETE");
        assertThat(result.evidence()).allMatch(item -> "QUERY_HISTORY".equals(item.kind()));
    }

    @Test
    void refusesHistorySearchWithNeitherTablesNorKeywords() throws Exception {
        var result = tools("jdbc:h2:mem:ai-history-empty;DB_CLOSE_DELAY=-1")
                .execute(CONNECTION_ID, "PUBLIC", call("h-2", "search_query_history", "{\"tables\":[]}"));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("表名或关键词");
    }

    private AiSchemaTools tools(String url) throws Exception {
        return tools(url, List.of());
    }

    private AiSchemaTools tools(String url, List<AiBusinessTerm> terms) throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(CONNECTION_ID)).thenReturn(new DbConnection(
                CONNECTION_ID, "test", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(connections.open(anyLong())).thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), nullable(String.class)))
                .thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));
        MetadataCacheService cache = new MetadataCacheService();
        MetadataService metadata = new MetadataService(
                connections, new DialectRegistry(), mock(AuditRepository.class), cache, new ExecutionGuard());
        AiGlossaryService glossary = mock(AiGlossaryService.class);
        when(glossary.terms(CONNECTION_ID)).thenReturn(terms);
        AiSqlValidationService validator = new AiSqlValidationService(
                connections, new DialectRegistry(), new com.example.dbadmin.service.SqlScriptSplitter(),
                new com.example.dbadmin.service.SqlStatementClassifier(), new AppProperties());
        AiQueryHistoryService history = new AiQueryHistoryService(
                historyRepository(), new com.example.dbadmin.service.SqlStatementClassifier());
        return new AiSchemaTools(connections, new DialectRegistry(), metadata, cache, glossary, history, validator, json);
    }

    private com.example.dbadmin.repo.SqlHistoryRepository historyRepository() {
        var repository = mock(com.example.dbadmin.repo.SqlHistoryRepository.class);
        when(repository.findRecent(anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse(1, CONNECTION_ID,
                        "SELECT u.DISPLAY_NAME FROM APP_USER u JOIN APP_ROLE r ON r.ID = u.ROLE_ID"
                                + " WHERE r.ROLE_NAME = '管理员'",
                        "EXECUTE", "SUCCESS", 12, null, "tanner", "2026-09-01"),
                new com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse(2, CONNECTION_ID,
                        "DELETE FROM APP_USER WHERE ID = 9", "EXECUTE", "SUCCESS", 3, null, "tanner", "2026-09-02")));
        return repository;
    }

    private LlmToolCall call(String id, String name, String arguments) throws Exception {
        return new LlmToolCall(id, name, json.readTree(arguments));
    }

    private static JsonNode findByName(JsonNode values, String name) {
        for (JsonNode value : values) {
            if (name.equalsIgnoreCase(value.path("name").asText())) return value;
        }
        throw new AssertionError("没有找到对象：" + name + "，实际值：" + values);
    }
}
