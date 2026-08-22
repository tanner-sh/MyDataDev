package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlTransactionScriptResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlTransactionServiceTest {
    private String url;
    private JdbcTemplate target;
    private SqlTransactionService service;
    private SqlTransactionRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        setUp(Duration.ofMinutes(10), false);
    }

    private void setUp(Duration idleTimeout, boolean readonly) throws Exception {
        url = "jdbc:h2:mem:tx-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        target = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        target.execute("CREATE TABLE accounts(id INT PRIMARY KEY, balance INT)");
        target.update("INSERT INTO accounts VALUES (1, 100)");

        DbConnection model = new DbConnection(1L, "h2", "h2", url, "sa", "", "dev", readonly, Instant.now(), Instant.now());
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(model);
        when(connections.open(anyLong())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), any())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));

        AppProperties properties = new AppProperties();
        DialectRegistry dialects = new DialectRegistry();
        AuditRepository audit = mock(AuditRepository.class);
        MetadataService metadata = mock(MetadataService.class);
        SqlService sqlService = new SqlService(
                connections, properties, audit, dialects, mock(SqlHistoryRepository.class), metadata,
                new SqlScriptSplitter(), new SqlStatementClassifier(), new ExecutionGuard(),
                new SqlExecutionRegistry(), mock(DataEditService.class)
        );
        registry = new SqlTransactionRegistry(idleTimeout);
        service = new SqlTransactionService(
                connections, dialects, new SqlScriptSplitter(), new SqlStatementClassifier(),
                new ExecutionGuard(), registry, sqlService, audit, mock(SqlHistoryRepository.class), metadata
        );
    }

    private int balance() {
        Integer value = target.queryForObject("SELECT balance FROM accounts WHERE id = 1", Integer.class);
        return value == null ? -1 : value;
    }

    @Test
    void changesAreInvisibleUntilCommit() throws Exception {
        var transaction = service.begin(1L, null, "admin", null);

        service.execute(transaction.id(), "UPDATE accounts SET balance = 42 WHERE id = 1", null, "admin", false);
        // 另一条连接读到的还是旧值：这正是手动事务的意义。
        assertThat(balance()).isEqualTo(100);

        service.finish(transaction.id(), true, "admin");
        assertThat(balance()).isEqualTo(42);
    }

    @Test
    void rollbackDiscardsEverythingInTheTransaction() throws Exception {
        var transaction = service.begin(1L, null, "admin", null);
        service.execute(transaction.id(), "UPDATE accounts SET balance = 7 WHERE id = 1", null, "admin", false);
        service.execute(transaction.id(), "UPDATE accounts SET balance = 8 WHERE id = 1", null, "admin", false);

        service.finish(transaction.id(), false, "admin");

        assertThat(balance()).isEqualTo(100);
    }

    @Test
    void severalStatementsShareOneTransactionAndTheCountIsReported() throws Exception {
        var transaction = service.begin(1L, null, "admin", null);

        SqlTransactionScriptResponse first = service.execute(
                transaction.id(), "UPDATE accounts SET balance = 1 WHERE id = 1; UPDATE accounts SET balance = 2 WHERE id = 1",
                null, "admin", false
        );
        assertThat(first.executedCount()).isEqualTo(2);
        assertThat(first.transaction().statementCount()).isEqualTo(2);

        SqlTransactionScriptResponse second = service.execute(transaction.id(), "SELECT balance FROM accounts", null, "admin", false);
        // 同一个事务里读得到自己刚写的值。
        assertThat(second.results().get(0).result().rows().get(0).get(0)).isEqualTo(2);
        assertThat(second.transaction().statementCount()).isEqualTo(3);

        service.finish(transaction.id(), false, "admin");
    }

    @Test
    void aFailedStatementStopsTheBatchButLeavesTheTransactionOpenToDecide() throws Exception {
        var transaction = service.begin(1L, null, "admin", null);

        SqlTransactionScriptResponse response = service.execute(
                transaction.id(), "UPDATE accounts SET balance = 5 WHERE id = 1; SELECT * FROM missing_table",
                null, "admin", false
        );

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.results()).hasSize(2);
        // 事务还开着，用户可以选择回滚掉前面那条成功的语句 —— 自动提交模式做不到这一点。
        assertThat(registry.activeFor(1L)).isNotNull();
        service.finish(transaction.id(), false, "admin");
        assertThat(balance()).isEqualTo(100);
    }

    @Test
    void onlyOneTransactionPerConnectionBecauseThePoolIsSmall() throws Exception {
        var first = service.begin(1L, null, "admin", null);

        assertThatThrownBy(() -> service.begin(1L, null, "admin", null))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("TRANSACTION_ALREADY_OPEN"));

        service.finish(first.id(), false, "admin");
        assertThatCode(() -> service.finish(service.begin(1L, null, "admin", null).id(), false, "admin"))
                .doesNotThrowAnyException();
    }

    @Test
    void anEndedTransactionCannotBeUsedAgain() throws Exception {
        var transaction = service.begin(1L, null, "admin", null);
        service.finish(transaction.id(), true, "admin");

        assertThatThrownBy(() -> service.execute(transaction.id(), "SELECT 1", null, "admin", false))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void refusesSessionChangingStatementsBecauseTheyLeakOntoThePooledConnection() throws Exception {
        var transaction = service.begin(1L, null, "admin", null);

        assertThatThrownBy(() -> service.execute(transaction.id(), "SET SCHEMA PUBLIC", null, "admin", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话状态");

        service.finish(transaction.id(), false, "admin");
    }

    @Test
    void stillDemandsConfirmationForAnUnscopedUpdate() throws Exception {
        var transaction = service.begin(1L, null, "admin", null);

        assertThatThrownBy(() -> service.execute(transaction.id(), "UPDATE accounts SET balance = 0", null, "admin", false))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("UNSCOPED_MUTATION_CONFIRMATION_REQUIRED"));

        assertThatCode(() -> service.execute(transaction.id(), "UPDATE accounts SET balance = 0", null, "admin", true))
                .doesNotThrowAnyException();
        service.finish(transaction.id(), false, "admin");
    }

    @Test
    void refusesToOpenOnAReadonlyConnection() throws Exception {
        setUp(Duration.ofMinutes(10), true);

        assertThatThrownBy(() -> service.begin(1L, null, "admin", null))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("READONLY_CONNECTION"));
    }

    @Test
    void anIdleTransactionIsRolledBackAndItsConnectionReturned() throws Exception {
        setUp(Duration.ZERO, false);
        var transaction = service.begin(1L, null, "admin", null);
        service.execute(transaction.id(), "UPDATE accounts SET balance = 99 WHERE id = 1", null, "admin", false);

        service.reclaimIdleTransactions();

        // 忘了提交不能把连接占死：自动回滚并归还。
        assertThat(registry.activeFor(1L)).isNull();
        assertThat(registry.size()).isZero();
        assertThat(balance()).isEqualTo(100);
    }
}
