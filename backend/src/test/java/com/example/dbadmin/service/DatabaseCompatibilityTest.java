package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DataPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.RowChange;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** CI 提供独立的 MySQL/PostgreSQL 服务；本地不配置测试库时跳过，不访问用户连接。 */
class DatabaseCompatibilityTest {
    @Test void mysql() throws Exception { exercise("mysql", "MYSQL"); }
    @Test void postgres() throws Exception { exercise("postgresql", "POSTGRES"); }

    private void exercise(String type, String prefix) throws Exception {
        String url = System.getenv("TEST_" + prefix + "_URL");
        assumeTrue(url != null && !url.isBlank(), "未配置独立测试数据库 TEST_" + prefix + "_URL");
        String user = System.getenv("TEST_" + prefix + "_USER");
        String password = System.getenv("TEST_" + prefix + "_PASSWORD");
        String table = "polish_" + UUID.randomUUID().toString().replace("-", "");
        DbConnection db = new DbConnection(1, "兼容性测试", type, url, user, "", "dev", false, Instant.now(), Instant.now());
        var connections = mock(ConnectionService.class);
        when(connections.require(1)).thenReturn(db);
        when(connections.open(anyLong())).thenAnswer(call -> DriverManager.getConnection(url, user, password));
        when(connections.open(anyLong(), nullable(String.class))).thenAnswer(call -> DriverManager.getConnection(url, user, password));
        var dialects = new DialectRegistry();
        var dialect = dialects.dialectFor(db);
        var properties = new AppProperties();
        var audit = mock(AuditRepository.class);
        var mapper = new ObjectMapper();
        var crypto = new CryptoService("compatibility-test-only-key");
        var guard = new ExecutionGuard();
        var metadata = new MetadataService(connections, dialects, audit, new MetadataCacheService(), guard);
        var edits = new DataEditService(metadata, connections, audit, dialects, properties, new TableCursorCodec(mapper, crypto), new RowLocatorCodec(mapper, crypto), guard);
        try (var jdbc = DriverManager.getConnection(url, user, password); var statement = jdbc.createStatement()) {
            String schema = type.equals("mysql") ? jdbc.getCatalog() : "public";
            String qualified = dialect.qualifiedName(schema, table);
            statement.execute("CREATE TABLE " + qualified + " (id BIGINT PRIMARY KEY, name VARCHAR(80), amount DECIMAL(20,2))");
            try {
                statement.executeUpdate("INSERT INTO " + qualified + " VALUES (1, '原值', 123.45), (2, '第二行', 99.01)");
                var first = edits.table(1L, schema, table, null, 1);
                assertThat(first.editable()).isTrue();
                assertThat(first.hasMore()).isTrue();
                assertThat(edits.table(1L, schema, table, first.nextCursor(), 1).rows().get(0).get("name")).isEqualTo("第二行");
                String token = first.rowKeyTokens().get(0);
                var change = new RowChange("UPDATE", null, Map.of("name", "我的修改"), Map.of("name", "原值"), token);
                var request = new DataPreviewRequest(1L, schema, table, List.of(change));
                statement.executeUpdate("UPDATE " + qualified + " SET name='其他会话' WHERE id=1");
                assertThatThrownBy(() -> edits.commit(request, "ci")).isInstanceOf(ApiProblemException.class);
                assertThat(edits.conflictRow(request, "ci")).containsEntry("values", Map.of("name", "其他会话"));
                var rebased = new RowChange("UPDATE", null, Map.of("name", "我的修改"), Map.of("name", "其他会话"), token);
                assertThat(edits.commit(new DataPreviewRequest(1L, schema, table, List.of(rebased)), "ci").affectedRows()).isEqualTo(1);
                var exports = new ExportService(connections, dialects, properties, mapper, new SqlStatementClassifier(), new SqlScriptSplitter(), audit, mock(SqlHistoryRepository.class), guard);
                var prepared = exports.prepare(1L, "SELECT * FROM " + qualified + " ORDER BY id", "csv", "ci", null);
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    prepared.writeTo(output);
                    assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("我的修改", "123.45", "第二行");
                } finally { prepared.discard(); }
            } finally { statement.execute("DROP TABLE " + qualified); }
        }
    }
}
