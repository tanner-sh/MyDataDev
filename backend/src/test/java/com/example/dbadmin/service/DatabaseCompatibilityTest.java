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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 独立实库测试；本地未配置时跳过，CI 点名的那几家必须提供配置。每例只操作随机命名的测试表。
 *
 * <p>每个用例三分钟上限，且必须是 SEPARATE_THREAD：实库测试挂住的方式是等锁，而等锁不会
 * 自己超时。{@code @Timeout} 默认在原线程上跑，只在方法返回之后判定耗时 —— 真挂住时它一句话
 * 也说不出，用例会一直等到作业上限被外部取消，surefire 报告里连它跑到哪一步都没有（SQL Server
 * 上就这么白烧过 20 分钟）。换成独立线程后 JUnit 才会真的打断它，挂住变成一条带栈的失败。</p>
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseCompatibilityTest {
    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "postgresql", "sqlserver", "oracle"})
    void metadataPaginationConflictAndCsvExport(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.table(f.pk("id") + ", " + f.varchar("name", 80) + ", " + f.decimal("amount", 20, 2));
            f.insert(table, "1, " + f.text("原值") + ", 123.45", "2, " + f.text("第二行") + ", 99.01");
            var first = f.edits.table(1L, f.schema, table, null, 1);
            assertThat(first.editable()).isTrue();
            assertThat(first.hasMore()).isTrue();
            var second = f.edits.table(1L, f.schema, table, first.nextCursor(), 1);
            assertThat(second.rows().get(0).get(f.col("name"))).isEqualTo("第二行");
            assertThat(second.hasMore()).isFalse();
            String token = first.rowKeyTokens().get(0);
            var request = f.change(table, "原值", "我的修改", token);
            f.execute("UPDATE " + f.q(table) + " SET name=" + f.text("其他会话") + " WHERE id=1");
            assertThatThrownBy(() -> f.edits.commit(request, "ci")).isInstanceOf(ApiProblemException.class);
            assertThat(f.edits.conflictRow(request, "ci")).containsEntry("values", Map.of(f.col("name"), "其他会话"));
            assertThat(f.edits.commit(f.change(table, "其他会话", "我的修改", token), "ci").affectedRows()).isEqualTo(1);
            assertThat(f.export(table, "csv", null)).contains("我的修改", "123.45", "第二行");
        }
    }

    /**
     * Oracle 不在此列：它的 VARCHAR2 把空串直接存成 NULL，「NULL 与空串必须分开」这条在
     * Oracle 上不成立，也不是产品能修的 —— 与其把断言放宽成两者都接受（那就等于不测），
     * 不如明确排除并写清原因。
     */
    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "postgresql", "sqlserver"})
    void nullEmptyStringAndEscapedTextRemainDistinct(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.table(f.pk("id") + ", " + f.varchar("name", 200));
            f.execute("INSERT INTO " + f.q(table) + " VALUES (1, NULL)");
            Object previous = null;
            for (Object next : new Object[]{"", "中文🙂 O'Reilly\\path\n第二行", null}) {
                var page = f.edits.table(1L, f.schema, table, null, 10);
                assertThat(page.rows().get(0).get(f.col("name"))).isEqualTo(previous);
                assertThat(f.edits.commit(f.change(table, previous, next, page.rowKeyTokens().get(0)), "ci").affectedRows()).isEqualTo(1);
                assertThat(f.scalar("SELECT name FROM " + f.q(table))).isEqualTo(next);
                previous = next;
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "postgresql", "sqlserver", "oracle"})
    void sqlExportRoundTripsPrecisionTimestampBinaryAndText(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String columns = f.pk("id") + ", " + f.decimal("amount", 30, 8) + ", " + f.timestamp("happened", 6)
                    + ", " + f.binary("payload", 30) + ", " + f.varchar("note", 200);
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
            assertThat(page.rows().get(0).get(f.col("id"))).isEqualTo(Long.toString(id));
            assertThat(page.rows().get(0).get(f.col("amount"))).isEqualTo(amount.toPlainString());
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

    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "postgresql", "sqlserver", "oracle"})
    void failedGridBatchRollsBackEarlierChanges(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.table(f.pk("id") + ", " + f.varchar("name", 80));
            f.insert(table, "1, " + f.text("原值"), "2, " + f.text("第二行"));
            var page = f.edits.table(1L, f.schema, table, null, 10);
            f.execute("UPDATE " + f.q(table) + " SET name=" + f.text("外部修改") + " WHERE id=2");
            var changes = List.of(
                    new RowChange("UPDATE", null, Map.of(f.col("name"), "不应保存"), Map.of(f.col("name"), "原值"), page.rowKeyTokens().get(0)),
                    new RowChange("UPDATE", null, Map.of(f.col("name"), "冲突修改"), Map.of(f.col("name"), "第二行"), page.rowKeyTokens().get(1)));
            assertThatThrownBy(() -> f.edits.commit(new DataPreviewRequest(1L, f.schema, table, changes), "ci"))
                    .isInstanceOf(ApiProblemException.class);
            assertThat(f.scalar("SELECT name FROM " + f.q(table) + " WHERE id=1")).isEqualTo("原值");
            assertThat(f.scalar("SELECT name FROM " + f.q(table) + " WHERE id=2")).isEqualTo("外部修改");
        }
    }

    /**
     * SQL Server 不在此列：这条用例验证「提交前别的连接看不到」的办法是从第二条连接读同一行，
     * 而那需要 MVCC。SQL Server 默认的 READ COMMITTED 用锁实现，那个 SELECT 会一直阻塞到事务
     * 结束 —— 用例就此挂住（第一次跑时白等到作业上限被取消）。阻塞在 SQL Server 上是正确行为，
     * 不是产品问题；要在那边验证同一件事得改成断言「读被阻塞」，那是另一条用例。
     */
    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "postgresql", "oracle"})
    void manualTransactionsCommitAndRollbackAfterFailure(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.table(f.pk("id") + ", " + f.id("amount"));
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
                assertThat(f.number("SELECT amount FROM " + f.q(table))).isEqualTo(100);
                transactions.finish(tx.id(), true, "ci");
                assertThat(f.number("SELECT amount FROM " + f.q(table))).isEqualTo(42);
            } finally { registry.close(tx.id()); }
            tx = transactions.begin(1L, f.schema, "ci", null);
            try {
                var result = transactions.execute(tx.id(), "UPDATE " + f.q(table) + " SET amount=7 WHERE id=1; INSERT INTO "
                        + f.q(table) + " VALUES (1, 99)", null, "ci", false);
                assertThat(result.status()).isEqualTo("FAILED");
                transactions.finish(tx.id(), false, "ci");
                assertThat(f.number("SELECT amount FROM " + f.q(table))).isEqualTo(42);
            } finally { registry.close(tx.id()); }
        }
    }

    /**
     * SQL Server 不在此列：它的方言把 tableDesign 声明为 false（能力矩阵里明确不支持表设计），
     * 服务端会直接拒绝，这里跑它等于断言一个产品不提供的功能。
     */
    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "postgresql", "oracle"})
    void tableLifecycleAndDesignExecuteAgainstRealMetadata(String type) throws Exception {
        try (Fixture f = new Fixture(type)) {
            String table = f.reserveTable(), renamed = f.reserveTable(), index = "idx_" + table;
            var create = new TableLifecycleRequest("CREATE", f.schema, table, null,
                    List.of(new ColumnDesign(f.col("id"), f.flavor.bigint(), null, false, null, null, false),
                            new ColumnDesign(f.col("name"), f.flavor.varchar(), 80, true, null, null, false)),
                    List.of(), List.of(f.col("id")), null, f.schema + "." + table);
            f.metadata.executeTableLifecycle(1L, create, "ci", null);
            var original = f.metadata.detail(1L, f.schema, table, true);
            assertThat(original.primaryKeys()).containsExactly(f.col("id"));
            var design = new TableDesignRequest(f.schema, table,
                    List.of(new ColumnDesign(f.col("id"), f.flavor.bigint(), null, false, null, f.col("id"), false),
                            new ColumnDesign(f.col("name"), f.flavor.varchar(), 120, true, null, f.col("name"), false),
                            new ColumnDesign(f.col("note"), f.flavor.varchar(), 100, true, null, null, false)),
                    List.of(new IndexDesign(index, List.of(f.col("name")), false, null, false)),
                    List.of(f.col("id")), original.structureVersion(), f.schema + "." + table);
            f.metadata.executeDesign(1L, design, "ci");
            var changed = f.metadata.detail(1L, f.schema, table, true);
            assertThat(changed.columns()).extracting("name").containsExactly(f.col("id"), f.col("name"), f.col("note"));
            assertThat(changed.indexes()).extracting("name").contains(index);
            f.execute("INSERT INTO " + f.q(table) + " VALUES (1, " + f.text("x".repeat(100)) + ", " + f.text("保留数据") + ")");
            f.metadata.executeTableLifecycle(1L, new TableLifecycleRequest("RENAME", f.schema, table, renamed,
                    null, null, null, changed.structureVersion(), f.schema + "." + table), "ci", null);
            assertThat(f.scalar("SELECT note FROM " + f.q(renamed))).isEqualTo("保留数据");
            var detail = f.metadata.detail(1L, f.schema, renamed, true);
            f.metadata.executeTableLifecycle(1L, new TableLifecycleRequest("DROP", f.schema, renamed, null,
                    null, null, null, detail.structureVersion(), f.schema + "." + renamed), "ci", null);
            assertThatThrownBy(() -> f.metadata.detail(1L, f.schema, renamed, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 每家数据库的取值差异，集中在这一张表里，用例正文不再出现 if (type.equals(...))。
     *
     * <p>{@code distinguishesEmptyString} 不是取值而是语义：Oracle 的 VARCHAR2 把空串就存成
     * NULL，产品层面无从修正，所以那条「NULL 与空串必须分开」的用例在 Oracle 上压根不成立
     * —— 排除掉，而不是把断言放宽成两者都接受。</p>
     */
    record Flavor(String envPrefix, String bigint, String varchar, String decimal,
                  String timestamp, String binary, boolean distinguishesEmptyString) {}

    /**
     * 这一轮必须真的跑起来的数据库，逗号分隔。
     *
     * <p>为什么不是一个布尔开关：MariaDB 的客户端与 MySQL 的在 apt 层面互斥（都提供
     * virtual-mysql-client），同一个 runner 上装不了两个，所以 CI 拆成两个作业跑不同的子集。
     * 一个「所有库都必须配」的布尔量在这种情况下只能被关掉，那就等于把防静默跳过这件事
     * 一起关了。改成点名清单之后，每个作业各自声明它负责哪几家，漏配还是照样失败。</p>
     */
    static Set<String> required() {
        String value = System.getenv("TEST_REQUIRED_DATABASES");
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim)
                .filter(item -> !item.isEmpty()).collect(java.util.stream.Collectors.toSet());
    }

    static final Map<String, Flavor> FLAVORS = Map.of(
            "mysql", new Flavor("MYSQL", "BIGINT", "VARCHAR", "DECIMAL", "TIMESTAMP", "VARBINARY", true),
            "mariadb", new Flavor("MARIADB", "BIGINT", "VARCHAR", "DECIMAL", "TIMESTAMP", "VARBINARY", true),
            "postgresql", new Flavor("POSTGRES", "BIGINT", "VARCHAR", "DECIMAL", "TIMESTAMP", "BYTEA", true),
            // SQL Server 两处特殊：TIMESTAMP 是行版本戳而不是时间类型（要微秒精度只能用
            // DATETIME2）；VARCHAR 是非 Unicode 类型，中文写进去会按排序规则的代码页替换成
            // 问号 —— 那是 SQL Server 的真实行为，真实用户存中文用的就是 NVARCHAR。
            "sqlserver", new Flavor("SQLSERVER", "BIGINT", "NVARCHAR", "DECIMAL", "DATETIME2", "VARBINARY", true),
            "oracle", new Flavor("ORACLE", "NUMBER(19)", "VARCHAR2", "NUMBER", "TIMESTAMP", "RAW", false));

    static final class Fixture implements AutoCloseable {
        final String type;
        final Flavor flavor;
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
            this.flavor = FLAVORS.get(type);
            assertThat(flavor).as("未登记的数据库类型 %s", type).isNotNull();
            String prefix = flavor.envPrefix();
            String url = System.getenv("TEST_" + prefix + "_URL");
            if (required().contains(type)) {
                assertThat(url).as("TEST_REQUIRED_DATABASES 里点名了 %s，必须配置 TEST_%s_URL，不能跳过", type, prefix).isNotBlank();
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
            // schema/catalog 语义各家不同：MySQL 系用当前 catalog，PostgreSQL 是 public，
            // SQL Server 是 dbo，Oracle 则以登录用户为 schema（字典里存的是大写）。
            schema = switch (type) {
                case "mysql", "mariadb" -> jdbc.getCatalog();
                case "postgresql" -> "public";
                case "sqlserver" -> "dbo";
                case "oracle" -> jdbc.getMetaData().getUserName().toUpperCase(java.util.Locale.ROOT);
                default -> throw new IllegalStateException("未登记的 schema 语义：" + type);
            };
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
        /** 按方言写出列定义，免得每个用例都自己拼一遍类型名。 */
        String pk(String name) { return name + " " + flavor.bigint() + " PRIMARY KEY"; }
        String id(String name) { return name + " " + flavor.bigint(); }
        String varchar(String name, int size) { return name + " " + flavor.varchar() + "(" + size + ")"; }
        String decimal(String name, int precision, int scale) {
            return name + " " + flavor.decimal() + "(" + precision + "," + scale + ")";
        }
        String timestamp(String name, int precision) { return name + " " + flavor.timestamp() + "(" + precision + ")"; }
        String binary(String name, int size) {
            // PostgreSQL 的 BYTEA 不带长度。
            return name + " " + flavor.binary() + (flavor.binary().equals("BYTEA") ? "" : "(" + size + ")");
        }
        String q(String table) { return dialect.qualifiedName(schema, table); }
        /**
         * 列名按库里真正存的形态写。
         *
         * <p>建表时列定义没有加引号，Oracle 就把它们折成大写存进字典，于是结果行的键是
         * {@code ID} / {@code NAME} 而不是小写。按小写去取只会拿到 null —— 那不是产品的
         * 问题，真实的 Oracle 库里标识符本来就是大写的，服务端如实返回库里报的名字才对。
         * 用例里凡是按列名取值或构造改动的地方都过这一层。</p>
         */
        String col(String name) { return type.equals("oracle") ? name.toUpperCase(java.util.Locale.ROOT) : name; }
        /**
         * 用例自己写的 SQL 里的字符串字面量。
         *
         * <p>T-SQL 里不带前缀的 {@code '中文'} 是 VARCHAR 字面量：字符会先按数据库的代码页
         * 转换、再赋给 NVARCHAR 列，于是落库就成了问号。正确写法是 {@code N'中文'}。产品自己
         * 的写入端本来就带这个前缀（备份脚本里就是 {@code N'…'}），是用例的裸 SQL 漏了它。</p>
         */
        String text(String value) {
            String quoted = "'" + value.replace("'", "''") + "'";
            return type.equals("sqlserver") ? "N" + quoted : quoted;
        }
        void execute(String sql) throws Exception {
            try (var statement = jdbc.createStatement()) { statement.execute(sql); }
        }
        /**
         * 逐行插入用例的准备数据。
         *
         * <p>多行 {@code VALUES (…), (…)} 要到 Oracle 23 才支持，更早的版本报 ORA-00933。这些用例
         * 要测的不是多行插入语法，逐行写五家都认 —— 否则夜间回归跑 Oracle 21 时，用例在准备数据
         * 这一步就失败了，根本走不到被测的产品路径。</p>
         */
        void insert(String table, String... rows) throws Exception {
            for (String row : rows) execute("INSERT INTO " + q(table) + " VALUES (" + row + ")");
        }
        Object scalar(String sql) throws Exception {
            try (var statement = jdbc.createStatement(); var rows = statement.executeQuery(sql)) {
                assertThat(rows.next()).isTrue(); return rows.getObject(1);
            }
        }
        /**
         * 整数值按数值比较，不按包装类型。
         *
         * <p>同一个整数列各家驱动返回的类型并不一样：MySQL 的 BIGINT 给 Long，MariaDB 也给
         * Long，而 Oracle 的 NUMBER 给 BigDecimal。用 isEqualTo(100) 去比会在「100 与 100L」
         * 上失败 —— 那不是兼容性问题，只是断言写错了地方。</p>
         */
        long number(String sql) throws Exception {
            Object value = scalar(sql);
            assertThat(value).isInstanceOf(Number.class);
            return ((Number) value).longValue();
        }
        DataPreviewRequest change(String table, Object previous, Object next, String token) {
            return new DataPreviewRequest(1L, schema, table, List.of(new RowChange("UPDATE", null,
                    Collections.singletonMap(col("name"), next), Collections.singletonMap(col("name"), previous), token)));
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
                for (int i = tables.size() - 1; i >= 0; i--) dropQuietly(tables.get(i));
            } finally { jdbc.close(); }
        }
        /**
         * 清理失败不许盖掉用例本身的结论。
         *
         * <p>Oracle 没有 {@code DROP TABLE IF EXISTS} 这个语法（ORA-00933），原来那条清理在
         * Oracle 上第一张表就抛异常，于是剩下的表全没清掉，而抛出来的又是清理的错 ——
         * 用例真正的失败原因被埋掉了。Oracle 上还要加 CASCADE CONSTRAINTS，否则被引用的表
         * 删不掉。吞掉异常在这里是对的：这是收尾，不是被测对象。</p>
         */
        private void dropQuietly(String name) {
            try {
                execute("DROP TABLE " + q(name) + (type.equals("oracle") ? " CASCADE CONSTRAINTS" : ""));
            } catch (Exception ignored) {
                // 表本来就不在（用例自己删过），或者库不让删 —— 两种都不该影响断言结果。
            }
        }
    }
}
