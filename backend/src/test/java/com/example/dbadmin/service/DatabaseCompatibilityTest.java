package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.*;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 独立实库测试；本地未配置时跳过，CI 必须提供配置。每例只操作随机命名的测试表。 */
class DatabaseCompatibilityTest {
    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void metadataPaginationConflictAndCsvExport(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.table("id BIGINT PRIMARY KEY, name VARCHAR(80), amount DECIMAL(20,2)");
            f.execute("INSERT INTO " + f.q(table) + " VALUES (1, '原值', 123.45), (2, '第二行', 99.01)");
            var first = f.edits.table(1L, f.schema, table, null, 1);
            assertThat(first.editable()).isTrue();
            assertThat(first.hasMore()).isTrue();
            var second = f.edits.table(1L, f.schema, table, first.nextCursor(), 1);
            assertThat(second.rows().get(0).get("name")).isEqualTo("第二行");
            assertThat(second.hasMore()).isFalse();
            String token = first.rowKeyTokens().get(0);
            var request = f.change(table, "原值", "我的修改", token);
            f.execute("UPDATE " + f.q(table) + " SET name='其他会话' WHERE id=1");
            assertThatThrownBy(() -> f.edits.commit(request, "ci")).isInstanceOf(ApiProblemException.class);
            assertThat(f.edits.conflictRow(request, "ci")).containsEntry("values", Map.of("name", "其他会话"));
            assertThat(f.edits.commit(f.change(table, "其他会话", "我的修改", token), "ci").affectedRows()).isEqualTo(1);
            assertThat(f.export(table, "csv", null)).contains("我的修改", "123.45", "第二行");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void nullEmptyStringAndEscapedTextRemainDistinct(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.table("id BIGINT PRIMARY KEY, name VARCHAR(200)");
            f.execute("INSERT INTO " + f.q(table) + " VALUES (1, NULL)");
            Object previous = null;
            for (Object next : new Object[]{"", "中文🙂 O'Reilly\\path\n第二行", null}) {
                var page = f.edits.table(1L, f.schema, table, null, 10);
                assertThat(page.rows().get(0).get("name")).isEqualTo(previous);
                assertThat(f.edits.commit(f.change(table, previous, next, page.rowKeyTokens().get(0)), "ci").affectedRows()).isEqualTo(1);
                assertThat(f.scalar("SELECT name FROM " + f.q(table))).isEqualTo(next);
                previous = next;
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void sqlExportRoundTripsPrecisionTimestampBinaryAndText(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String columns = "id BIGINT PRIMARY KEY, amount DECIMAL(30,8), happened TIMESTAMP(6), payload "
                    + (type.equals("mysql") ? "VARBINARY(30)" : "BYTEA") + ", note VARCHAR(200)";
            String source = f.table(columns), target = f.table(columns);
            long id = 9007199254740993L;
            BigDecimal amount = new BigDecimal("12345678901234567890.12345678");
            Timestamp happened = Timestamp.valueOf("2026-09-09 12:34:56.123456");
            byte[] payload = new byte[]{0, 1, 39, 92, (byte) 255};
            String note = "中文🙂 O'Reilly\\path\n第二行";
            try (var insert = f.jdbc.prepareStatement("INSERT INTO " + f.q(source) + " VALUES (?, ?, ?, ?, ?)")) {
                insert.setLong(1, id); insert.setBigDecimal(2, amount); insert.setTimestamp(3, happened);
                insert.setBytes(4, payload); insert.setString(5, note); insert.executeUpdate();
            }
            var page = f.edits.table(1L, f.schema, source, null, 10);
            assertThat(page.rows().get(0).get("id")).isEqualTo(Long.toString(id));
            assertThat(page.rows().get(0).get("amount")).isEqualTo(amount.toPlainString());
            for (var segment : new SqlScriptSplitter().split(f.export(source, "sql", List.of(f.schema, target)))) f.execute(segment.sql());
            try (var statement = f.jdbc.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + f.q(target))) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("id")).isEqualTo(id);
                assertThat(rows.getBigDecimal("amount")).isEqualByComparingTo(amount);
                assertThat(rows.getTimestamp("happened")).isEqualTo(happened);
                assertThat(rows.getBytes("payload")).isEqualTo(payload);
                assertThat(rows.getString("note")).isEqualTo(note);
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void failedGridBatchRollsBackEarlierChanges(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.table("id BIGINT PRIMARY KEY, name VARCHAR(80)");
            f.execute("INSERT INTO " + f.q(table) + " VALUES (1, '原值'), (2, '第二行')");
            var page = f.edits.table(1L, f.schema, table, null, 10);
            f.execute("UPDATE " + f.q(table) + " SET name='外部修改' WHERE id=2");
            var changes = List.of(
                    new RowChange("UPDATE", null, Map.of("name", "不应保存"), Map.of("name", "原值"), page.rowKeyTokens().get(0)),
                    new RowChange("UPDATE", null, Map.of("name", "冲突修改"), Map.of("name", "第二行"), page.rowKeyTokens().get(1)));
            assertThatThrownBy(() -> f.edits.commit(new DataPreviewRequest(1L, f.schema, table, changes), "ci"))
                    .isInstanceOf(ApiProblemException.class);
            assertThat(f.scalar("SELECT name FROM " + f.q(table) + " WHERE id=1")).isEqualTo("原值");
            assertThat(f.scalar("SELECT name FROM " + f.q(table) + " WHERE id=2")).isEqualTo("外部修改");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void manualTransactionsCommitAndRollbackAfterFailure(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.table("id BIGINT PRIMARY KEY, amount INT");
            f.execute("INSERT INTO " + f.q(table) + " VALUES (1, 100)");
            var registry = new SqlTransactionRegistry();
            var sql = new SqlService(f.connections, f.properties, f.audit, f.dialects, mock(SqlHistoryRepository.class),
                    f.metadata, new SqlScriptSplitter(), new SqlStatementClassifier(), f.guard,
                    new SqlExecutionRegistry(), f.edits, new SqlExecutionMetrics());
            var transactions = new SqlTransactionService(f.connections, f.dialects, new SqlScriptSplitter(),
                    new SqlStatementClassifier(), f.guard, registry, sql, f.audit, mock(SqlHistoryRepository.class), f.metadata);
            var tx = transactions.begin(1L, f.schema, "ci", null);
            try {
                assertThat(transactions.execute(tx.id(), "UPDATE " + f.q(table) + " SET amount=42 WHERE id=1", null, "ci", false).status())
                        .isNotEqualTo("FAILED");
                assertThat(f.scalar("SELECT amount FROM " + f.q(table))).isEqualTo(100);
                transactions.finish(tx.id(), true, "ci");
                assertThat(f.scalar("SELECT amount FROM " + f.q(table))).isEqualTo(42);
            } finally { registry.close(tx.id()); }
            tx = transactions.begin(1L, f.schema, "ci", null);
            try {
                var result = transactions.execute(tx.id(), "UPDATE " + f.q(table) + " SET amount=7 WHERE id=1; INSERT INTO "
                        + f.q(table) + " VALUES (1, 99)", null, "ci", false);
                assertThat(result.status()).isEqualTo("FAILED");
                transactions.finish(tx.id(), false, "ci");
                assertThat(f.scalar("SELECT amount FROM " + f.q(table))).isEqualTo(42);
            } finally { registry.close(tx.id()); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void tableLifecycleAndDesignExecuteAgainstRealMetadata(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.reserveTable(), renamed = f.reserveTable(), index = "idx_" + table;
            var create = new TableLifecycleRequest("CREATE", f.schema, table, null,
                    List.of(new ColumnDesign("id", "BIGINT", null, false, null, null, false),
                            new ColumnDesign("name", "VARCHAR", 80, true, null, null, false)),
                    List.of(), List.of("id"), null, f.schema + "." + table);
            f.metadata.executeTableLifecycle(1L, create, "ci", null);
            var original = f.metadata.detail(1L, f.schema, table, true);
            assertThat(original.primaryKeys()).containsExactly("id");
            var design = new TableDesignRequest(f.schema, table,
                    List.of(new ColumnDesign("id", "BIGINT", null, false, null, "id", false),
                            new ColumnDesign("name", "VARCHAR", 120, true, null, "name", false),
                            new ColumnDesign("note", "VARCHAR", 100, true, null, null, false)),
                    List.of(new IndexDesign(index, List.of("name"), false, null, false)),
                    List.of("id"), original.structureVersion(), f.schema + "." + table);
            f.metadata.executeDesign(1L, design, "ci");
            var changed = f.metadata.detail(1L, f.schema, table, true);
            assertThat(changed.columns()).extracting("name").containsExactly("id", "name", "note");
            assertThat(changed.indexes()).extracting("name").contains(index);
            f.execute("INSERT INTO " + f.q(table) + " VALUES (1, '" + "x".repeat(100) + "', '保留数据')");
            f.metadata.executeTableLifecycle(1L, new TableLifecycleRequest("RENAME", f.schema, table, renamed,
                    null, null, null, changed.structureVersion(), f.schema + "." + table), "ci", null);
            assertThat(f.scalar("SELECT note FROM " + f.q(renamed))).isEqualTo("保留数据");
            var detail = f.metadata.detail(1L, f.schema, renamed, true);
            f.metadata.executeTableLifecycle(1L, new TableLifecycleRequest("DROP", f.schema, renamed, null,
                    null, null, null, detail.structureVersion(), f.schema + "." + renamed), "ci", null);
            assertThatThrownBy(() -> f.metadata.detail(1L, f.schema, renamed, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static final class Fixture implements AutoCloseable {
        final String type;
        final Connection jdbc;
        final String schema;
        final ConnectionService connections = mock(ConnectionService.class);
        final DialectRegistry dialects = new DialectRegistry();
        final DatabaseDialect dialect;
        final AppProperties properties = new AppProperties();
        final AuditRepository audit = mock(AuditRepository.class);
        final ObjectMapper mapper = new ObjectMapper();
        final ExecutionGuard guard = new ExecutionGuard();
        final MetadataService metadata;
        final DataEditService edits;
        final List<String> tables = new ArrayList<>();
        String sessionSql;

        Fixture(String type) throws Exception {
            this.type = type;
            String prefix = type.equals("mysql") ? "MYSQL" : "POSTGRES";
            String url = System.getenv("TEST_" + prefix + "_URL");
            if (Boolean.parseBoolean(System.getenv("TEST_DATABASES_REQUIRED"))) {
                assertThat(url).as("CI 必须配置 TEST_%s_URL，不能跳过实库回归", prefix).isNotBlank();
            }
            assumeTrue(url != null && !url.isBlank(), "未配置独立测试数据库 TEST_" + prefix + "_URL");
            String user = System.getenv("TEST_" + prefix + "_USER"), password = System.getenv("TEST_" + prefix + "_PASSWORD");
            var db = new DbConnection(1, "兼容性测试", type, url, user, "", "dev", false, Instant.now(), Instant.now());
            when(connections.require(1L)).thenReturn(db);
            when(connections.password(1L)).thenReturn(password);
            when(connections.open(anyLong())).thenAnswer(call -> open(url, user, password));
            when(connections.open(anyLong(), nullable(String.class))).thenAnswer(call -> open(url, user, password));
            when(connections.openNativeAccess(1L)).thenAnswer(call -> new RemoteDataSourceRegistry.NativeAccess(url, null));
            dialect = dialects.dialectFor(db);
            metadata = new MetadataService(connections, dialects, audit, new MetadataCacheService(), guard);
            var crypto = new CryptoService("compatibility-test-only-key");
            edits = new DataEditService(metadata, connections, audit, dialects, properties,
                    new TableCursorCodec(mapper, crypto), new RowLocatorCodec(mapper, crypto), guard);
            jdbc = DriverManager.getConnection(url, user, password);
            schema = type.equals("mysql") ? jdbc.getCatalog() : "public";
        }
        private Connection open(String url, String user, String password) throws Exception {
            Connection connection = DriverManager.getConnection(url, user, password);
            try {
                if (sessionSql != null) {
                    try (var statement = connection.createStatement()) { statement.execute(sessionSql); }
                }
                return connection;
            } catch (Exception error) {
                connection.close();
                throw error;
            }
        }
        String reserveTable() {
            String name = "compat_" + UUID.randomUUID().toString().replace("-", ""); tables.add(name); return name;
        }
        String table(String columns) throws Exception {
            String name = reserveTable(); execute("CREATE TABLE " + q(name) + " (" + columns + ")"); return name;
        }
        String q(String table) { return dialect.qualifiedName(schema, table); }
        void execute(String sql) throws Exception {
            try (var statement = jdbc.createStatement()) { statement.execute(sql); }
        }
        Object scalar(String sql) throws Exception {
            try (var statement = jdbc.createStatement(); var rows = statement.executeQuery(sql)) {
                assertThat(rows.next()).isTrue(); return rows.getObject(1);
            }
        }
        DataPreviewRequest change(String table, Object previous, Object next, String token) {
            return new DataPreviewRequest(1L, schema, table, List.of(new RowChange("UPDATE", null,
                    Collections.singletonMap("name", next), Collections.singletonMap("name", previous), token)));
        }
        String export(String table, String format, List<String> target) throws Exception {
            var exports = new ExportService(connections, dialects, properties, mapper, new SqlStatementClassifier(),
                    new SqlScriptSplitter(), audit, mock(SqlHistoryRepository.class), guard);
            var prepared = exports.prepare(1L, "SELECT * FROM " + q(table) + " ORDER BY id", format, "ci", null, schema, target);
            try {
                var output = new ByteArrayOutputStream(); prepared.writeTo(output);
                return output.toString(java.nio.charset.StandardCharsets.UTF_8);
            } finally { prepared.discard(); }
        }
        @Override public void close() throws Exception {
            try {
                for (int i = tables.size() - 1; i >= 0; i--) execute("DROP TABLE IF EXISTS " + q(tables.get(i)));
            } finally { jdbc.close(); }
        }
    }
}
