package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DataPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.RowChange;
import com.example.dbadmin.dto.ApiDtos.TableDataResponse;
import com.example.dbadmin.dto.ApiDtos.TableDataRequest;
import com.example.dbadmin.dto.ApiDtos.TableFilterRule;
import com.example.dbadmin.dto.ApiDtos.TableSortRule;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataEditServiceTest {
    @Test
    void conflictReadUsesSignedLocatorAndReturnsCurrentValueOrMissingRow() throws Exception {
        String url = databaseUrl();
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE people(id INT PRIMARY KEY, name VARCHAR(40))");
            connection.createStatement().execute("INSERT INTO people VALUES (1, 'before')");
        }
        DataEditService service = service(url);
        var row = service.table(1L, null, "people", null, 10);
        var change = new RowChange("UPDATE", null, Map.of("name", "mine"), Map.of("name", "before"), row.rowKeyTokens().get(0));
        var request = new DataPreviewRequest(1L, null, "people", List.of(change));
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("UPDATE people SET name = 'current' WHERE id = 1");
            assertThatThrownBy(() -> service.commit(request, "test"))
                    .isInstanceOf(com.example.dbadmin.api.ApiProblemException.class)
                    .satisfies(error -> assertThat(((com.example.dbadmin.api.ApiProblemException) error).code()).isEqualTo("DATA_EDIT_CONFLICT"));
            assertThat(service.conflictRow(request, "test")).containsEntry("found", true).containsEntry("values", Map.of("name", "current"));
            var forged = new RowChange("UPDATE", null, Map.of("name", "mine"), Map.of("name", "before"), "forged-token");
            assertThatThrownBy(() -> service.conflictRow(new DataPreviewRequest(1L, null, "people", List.of(forged)), "test"))
                    .isInstanceOf(IllegalArgumentException.class);
            connection.createStatement().execute("DELETE FROM people WHERE id = 1");
            assertThat(service.conflictRow(request, "test")).containsEntry("found", false).containsEntry("values", Map.of());
        }
    }

    @Test
    void filtersWithBoundTypedValuesAndSortsOnServer() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE people(id INT PRIMARY KEY, name VARCHAR(40), score INT)");
            connection.createStatement().execute("INSERT INTO people VALUES (1, 'Alice', 20), (2, 'Bob', 30), (3, 'Alicia', 40), (4, 'Mallory', 50)");
        }

        TableDataResponse page = service(url).table(new TableDataRequest(
                1L, null, "people", null, 10,
                List.of(
                        new TableFilterRule("name", "CONTAINS", "Ali", null, null),
                        new TableFilterRule("score", "GTE", "20", null, null)
                ),
                List.of(new TableSortRule("score", "DESC")),
                "AND"
        ));

        assertThat(page.navigationMode()).isEqualTo("OFFSET");
        assertThat(page.rows()).extracting(row -> row.get("id")).containsExactly(3, 1);
    }

    @Test
    void bindsFilterPayloadInsteadOfTreatingItAsSql() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE people(id INT PRIMARY KEY, name VARCHAR(80))");
            connection.createStatement().execute("INSERT INTO people VALUES (1, 'Alice'), (2, 'Bob')");
        }

        TableDataResponse page = service(url).table(new TableDataRequest(
                1L, null, "people", null, 10,
                List.of(new TableFilterRule("name", "EQ", "' OR 1=1 --", null, null)),
                List.of(), "AND"
        ));

        assertThat(page.rows()).isEmpty();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM people")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void rejectsCursorWhenFilterChanges() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE people(id INT PRIMARY KEY, score INT)");
            connection.createStatement().execute("INSERT INTO people VALUES (1, 10), (2, 20), (3, 30)");
        }
        DataEditService service = service(url);
        TableDataRequest firstQuery = new TableDataRequest(1L, null, "people", null, 1,
                List.of(new TableFilterRule("score", "GTE", "10", null, null)), List.of(), "AND");
        String cursor = service.table(firstQuery).nextCursor();

        TableDataResponse second = service.table(new TableDataRequest(1L, null, "people", cursor, 1,
                firstQuery.filters(), firstQuery.sorts(), firstQuery.filterLogic()));
        assertThat(second.rows()).extracting(row -> row.get("id")).containsExactly(2);

        assertThatThrownBy(() -> service.table(new TableDataRequest(1L, null, "people", cursor, 1,
                List.of(new TableFilterRule("score", "GTE", "20", null, null)), List.of(), "AND")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("游标");
    }

    @Test
    void browsesTableWithSignedKeysetCursorAndServerSideHasMore() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE cash_ledger(id INT PRIMARY KEY, amount DECIMAL(12, 2))");
            connection.createStatement().execute("INSERT INTO cash_ledger VALUES (1, 10), (2, 20), (3, 30), (4, 40), (5, 50)");
        }
        DataEditService service = service(url);

        TableDataResponse first = service.table(1L, null, "cash_ledger", null, 2);
        TableDataResponse middle = service.table(1L, null, "cash_ledger", first.nextCursor(), 2);
        TableDataResponse last = service.table(1L, null, "cash_ledger", middle.nextCursor(), 2);

        assertThat(first.navigationMode()).isEqualTo("KEYSET");
        assertThat(first.keyColumns()).containsExactly("id");
        assertThat(first.rows()).extracting(row -> row.get("id")).containsExactly(1, 2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).contains(".");
        assertThat(middle.rows()).extracting(row -> row.get("id")).containsExactly(3, 4);
        assertThat(middle.hasMore()).isTrue();
        assertThat(last.rows()).extracting(row -> row.get("id")).containsExactly(5);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
        assertThat(first.editable()).isTrue();
    }

    @Test
    void rejectsTamperedPaginationCursor() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE cash_ledger(id INT PRIMARY KEY)");
            connection.createStatement().execute("INSERT INTO cash_ledger VALUES (1), (2)");
        }
        DataEditService service = service(url);
        String cursor = service.table(1L, null, "cash_ledger", null, 1).nextCursor();

        assertThatThrownBy(() -> service.table(1L, null, "cash_ledger", cursor + "x", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("签名");
    }

    @Test
    void buildsPreparedEditPreviewWithQuotedIdentifiersAndOptimisticPredicate() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE SCHEMA trading");
            connection.createStatement().execute("CREATE TABLE trading.cash_ledger(id INT PRIMARY KEY, amount INT, note VARCHAR(40))");
            connection.createStatement().execute("INSERT INTO trading.cash_ledger(id, amount, note) VALUES (1, 10, 'before')");
        }
        DataEditService service = service(url);
        String keyToken = service.table(1L, "trading", "cash_ledger", null, 10).rowKeyTokens().get(0);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("amount", 88);

        var response = service.preview(new DataPreviewRequest(
                1L,
                "trading",
                "cash_ledger",
                List.of(new RowChange("UPDATE", null, values, Map.of("amount", 10), keyToken))
        ));

        assertThat(response.sql()).containsExactly(
                "UPDATE `trading`.`cash_ledger` SET `amount` = 88 WHERE `id` = 1 AND `amount` = 10;"
        );
    }

    @Test
    void rollsBackWhenOptimisticPredicateNoLongerMatches() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE cash_ledger(id INT PRIMARY KEY, amount INT)");
            connection.createStatement().execute("INSERT INTO cash_ledger VALUES (1, 10)");
        }
        DataEditService service = service(url);
        String keyToken = service.table(1L, null, "cash_ledger", null, 10).rowKeyTokens().get(0);
        DataPreviewRequest request = new DataPreviewRequest(
                1L,
                null,
                "cash_ledger",
                List.of(new RowChange("UPDATE", null, Map.of("amount", "20"), Map.of("amount", 999), keyToken))
        );

        assertThatThrownBy(() -> service.commit(request, "admin"))
                .isInstanceOf(com.example.dbadmin.api.ApiProblemException.class)
                .satisfies(error -> assertThat(((com.example.dbadmin.api.ApiProblemException) error).code()).isEqualTo("DATA_EDIT_CONFLICT"))
                .hasMessageContaining("回滚");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var resultSet = connection.createStatement().executeQuery("SELECT amount FROM cash_ledger WHERE id = 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(10);
        }
    }

    @Test
    void reportsEverySuccessfulBatchedInsertAsAffected() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE cash_ledger(id INT PRIMARY KEY, amount INT)");
        }

        var response = service(url).commit(new DataPreviewRequest(
                1L,
                null,
                "cash_ledger",
                List.of(
                        new RowChange("INSERT", null, Map.of("id", 1, "amount", 10), null, null),
                        new RowChange("INSERT", null, Map.of("id", 2, "amount", 20), null, null)
                )
        ), "admin");

        assertThat(response.affectedRows()).isEqualTo(2);
    }

    @Test
    void preservesBigIntRowIdentityOutsideJavaScriptSafeIntegerRange() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE ledger(id BIGINT PRIMARY KEY, note VARCHAR(20))");
            connection.createStatement().execute("INSERT INTO ledger VALUES (9007199254740992, 'keep'), (9007199254740993, 'delete')");
        }
        DataEditService service = service(url);
        TableDataResponse page = service.table(1L, null, "ledger", null, 10);

        assertThat(page.rows()).extracting(row -> row.get("id"))
                .containsExactly("9007199254740992", "9007199254740993");
        service.commit(new DataPreviewRequest(
                1L, null, "ledger",
                List.of(new RowChange("DELETE", null, null, null, page.rowKeyTokens().get(1)))
        ), "admin");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var resultSet = connection.createStatement().executeQuery("SELECT id FROM ledger ORDER BY id")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(9007199254740992L);
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void editsCaseSensitiveColumnsWithoutConflatingTheirNames() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE case_values(id INT PRIMARY KEY, \"Foo\" VARCHAR(20), \"foo\" VARCHAR(20))");
            connection.createStatement().execute("INSERT INTO case_values(id, \"Foo\", \"foo\") VALUES (1, 'upper', 'lower')");
        }
        DataEditService service = service(url, "h2");
        TableDataResponse page = service.table(1L, null, "case_values", null, 10);

        service.commit(new DataPreviewRequest(
                1L,
                null,
                "case_values",
                List.of(new RowChange(
                        "UPDATE", null, Map.of("Foo", "changed"), Map.of("Foo", "upper"), page.rowKeyTokens().get(0)
                ))
        ), "admin");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var resultSet = connection.createStatement().executeQuery("SELECT \"Foo\", \"foo\" FROM case_values WHERE id = 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("changed");
            assertThat(resultSet.getString(2)).isEqualTo("lower");
        }
    }

    @Test
    void truncatesVeryLargeVarcharCellsInTableResponses() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE large_text(id INT PRIMARY KEY, note VARCHAR(150000))");
            connection.createStatement().execute("INSERT INTO large_text VALUES (1, REPEAT('x', 120000))");
        }

        TableDataResponse page = service(url).table(1L, null, "large_text", null, 10);
        String note = (String) page.rows().get(0).get("note");

        assertThat(note).hasSizeLessThanOrEqualTo(100_000).contains("文本已截断");
        assertThat(page.columns()).filteredOn(column -> column.name().equals("note")).singleElement().satisfies(column -> {
            assertThat(column.truncated()).isTrue();
            assertThat(column.editable()).isFalse();
        });
    }

    /*
      带时区的时间戳原来直接 OffsetDateTime.parse，抛的是 DateTimeParseException —— 它不是
      IllegalArgumentException 的子类，于是绕过了「非法入参 → 400」那条分支，用户填错一个
      格式得到的是「服务器内部错误，请稍后重试」。这里盯住两件事：常见写法要能接受，接受不了
      时抛出来的必须是能变成 400 的那一类，且消息说得出是哪一列。
    */
    @Test
    void acceptsTheTimestampSpellingsThatActuallyComeOutOfTheGrid() throws Exception {
        String url = timestampDatabaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE events(id INT PRIMARY KEY, happened_at TIMESTAMP WITH TIME ZONE)");
            connection.createStatement().execute("INSERT INTO events VALUES (1, NULL)");
        }
        DataEditService service = service(url);
        for (String spelling : List.of("2025-09-18T14:30:00+08:00", "2025-09-18 14:30:00+08:00", "2025-09-18 14:30:00 +08:00")) {
            TableDataResponse row = service.table(1L, null, "events", null, 10);
            RowChange change = new RowChange("UPDATE", null, new LinkedHashMap<>(Map.of("happened_at", spelling)),
                    new LinkedHashMap<>(), row.rowKeyTokens().get(0));
            assertThat(service.preview(new DataPreviewRequest(1L, null, "events", List.of(change))))
                    .as("应当接受 %s", spelling).isNotNull();
        }
    }

    @Test
    void reportsAnUnparsableTimestampAsBadInputAndNamesTheColumn() throws Exception {
        String url = timestampDatabaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE events(id INT PRIMARY KEY, happened_at TIMESTAMP WITH TIME ZONE)");
            connection.createStatement().execute("INSERT INTO events VALUES (1, NULL)");
        }
        DataEditService service = service(url);
        TableDataResponse row = service.table(1L, null, "events", null, 10);
        RowChange change = new RowChange("UPDATE", null, new LinkedHashMap<>(Map.of("happened_at", "昨天下午")),
                new LinkedHashMap<>(), row.rowKeyTokens().get(0));

        assertThatThrownBy(() -> service.preview(new DataPreviewRequest(1L, null, "events", List.of(change))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("happened_at")
                .hasMessageContaining("昨天下午");
    }

    /*
      空字符串落到一个时间戳列上，原来是 Timestamp.valueOf("") 抛一句英文；而用户到这一步
      往往是想「清空」，所以报错要直接把他指向那个真正能清空的动作。
    */
    @Test
    void pointsAtSetNullWhenAnEmptyStringReachesATypedColumn() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE people(id INT PRIMARY KEY, score INT)");
            connection.createStatement().execute("INSERT INTO people VALUES (1, 5)");
        }
        DataEditService service = service(url);
        TableDataResponse row = service.table(1L, null, "people", null, 10);
        RowChange change = new RowChange("UPDATE", null, new LinkedHashMap<>(Map.of("score", "")),
                new LinkedHashMap<>(), row.rowKeyTokens().get(0));

        assertThatThrownBy(() -> service.preview(new DataPreviewRequest(1L, null, "people", List.of(change))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score")
                .hasMessageContaining("设为 NULL");
    }

    @Test
    void stillAcceptsAnEmptyStringOnACharacterColumn() throws Exception {
        String url = databaseUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE people(id INT PRIMARY KEY, name VARCHAR(40))");
            connection.createStatement().execute("INSERT INTO people VALUES (1, 'x')");
        }
        DataEditService service = service(url);
        TableDataResponse row = service.table(1L, null, "people", null, 10);
        RowChange change = new RowChange("UPDATE", null, new LinkedHashMap<>(Map.of("name", "")),
                new LinkedHashMap<>(), row.rowKeyTokens().get(0));

        assertThat(service.preview(new DataPreviewRequest(1L, null, "people", List.of(change)))).isNotNull();
    }

    /** H2 的 MySQL 模式不认 TIMESTAMP WITH TIME ZONE，这几条用例单独用一条不带 MODE 的 URL。 */
    private String timestampDatabaseUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private DataEditService service(String url) throws Exception {
        return service(url, "mysql");
    }

    private DataEditService service(String url, String dbType) throws Exception {
        MetadataService metadata = mock(MetadataService.class);
        when(metadata.rowIdentity(any(Connection.class), any(DbConnection.class), any(), anyString()))
                .thenReturn(new MetadataService.RowIdentity(List.of("id"), "PRIMARY_KEY", true));
        ConnectionService connections = mock(ConnectionService.class);
        DbConnection dbConnection = new DbConnection(
                1L, dbType, dbType, url, "sa", "", "dev", false, Instant.now(), Instant.now()
        );
        when(connections.require(anyLong())).thenReturn(dbConnection);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        AppProperties properties = new AppProperties();
        properties.getSql().setTimeoutSeconds(10);
        TableCursorCodec cursorCodec = new TableCursorCodec(new ObjectMapper(), new CryptoService("test-table-cursor-key"));
        RowLocatorCodec rowLocatorCodec = new RowLocatorCodec(new ObjectMapper(), new CryptoService("test-table-cursor-key"));
        return new DataEditService(
                metadata,
                connections,
                mock(AuditRepository.class),
                new DialectRegistry(),
                properties,
                cursorCodec,
                rowLocatorCodec,
                new ExecutionGuard()
        );
    }

    private String databaseUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }
}
