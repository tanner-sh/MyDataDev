package com.example.dbadmin.service;

import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.core.MySqlDialect;
import com.example.dbadmin.core.OracleDialect;
import com.example.dbadmin.core.PostgreSqlDialect;
import com.example.dbadmin.dto.ApiDtos.DatabaseSessionPage;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseSessionServiceTest {
    private DatabaseSessionService service(String dbType, String url) throws Exception {
        DbConnection model = new DbConnection(1L, "c", dbType, url, "sa", "", "dev", false, Instant.now(), Instant.now());
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(model);
        when(connections.open(anyLong())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));
        return new DatabaseSessionService(connections, new DialectRegistry(), new ExecutionGuard(), mock(AuditRepository.class));
    }

    @Test
    void reportsUnsupportedDialectsInsteadOfFailingWithASqlError() throws Exception {
        DatabaseSessionPage page = service("h2", "jdbc:h2:mem:sess-" + UUID.randomUUID()).list(1L);

        assertThat(page.supported()).isFalse();
        assertThat(page.sessions()).isEmpty();
        assertThat(page.message()).contains("暂不支持");
    }

    @Test
    void explainsAReadFailureRatherThanLeakingTheDriverError() throws Exception {
        // 用 MySQL 方言但连到 H2：information_schema.PROCESSLIST 不存在，模拟权限/视图缺失。
        DatabaseSessionPage page = service("mysql", "jdbc:h2:mem:sess-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1").list(1L);

        assertThat(page.supported()).isTrue();
        assertThat(page.sessions()).isEmpty();
        assertThat(page.message()).contains("权限");
    }

    /**
     * pg_terminate_backend() 杀不掉时不抛异常，只返回 false。不读这个返回值的话，一次什么都
     * 没做的调用会被记成成功，用户还以为那个会话已经被终止了。
     */
    @Test
    void postgresReportsAFailedTerminationInsteadOfLoggingSuccess() throws Exception {
        String url = "jdbc:h2:mem:sess-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute(
                    "CREATE ALIAS pg_terminate_backend AS 'boolean terminate(int pid) { return pid == 42; }'"
            );
        }
        DatabaseSessionService service = service("postgresql", url);

        assertThatThrownBy(() -> service.kill(1L, "7", "admin", null))
                .isInstanceOf(com.example.dbadmin.api.ApiProblemException.class)
                .hasMessageContaining("未能终止会话");
        // 真的杀掉了就不该报错。
        service.kill(1L, "42", "admin", null);
    }

    @Test
    void mysqlListsAndKillsBySessionId() {
        MySqlDialect dialect = new MySqlDialect();

        assertThat(dialect.activeSessionsSql()).contains("PROCESSLIST").contains("session_id");
        assertThat(dialect.killSessionSql("42")).isEqualTo("KILL 42");
    }

    @Test
    void postgresExcludesItsOwnBackendSoTheToolCannotKillItself() {
        PostgreSqlDialect dialect = new PostgreSqlDialect();

        assertThat(dialect.activeSessionsSql()).contains("pg_stat_activity").contains("pg_backend_pid()");
        assertThat(dialect.killSessionSql("7")).isEqualTo("SELECT pg_terminate_backend(7)");
    }

    @Test
    void oracleNeedsSidAndSerialAndRejectsAnythingElse() {
        OracleDialect dialect = new OracleDialect();

        assertThat(dialect.killSessionSql("12,345")).contains("ALTER SYSTEM KILL SESSION '12,345'");
        assertThatThrownBy(() -> dialect.killSessionSql("12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SID,SERIAL#");
    }

    @Test
    void aSessionIdThatIsNotANumberCannotBeInjectedIntoTheKillStatement() {
        MySqlDialect dialect = new MySqlDialect();

        assertThatThrownBy(() -> dialect.killSessionSql("1; DROP TABLE users"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void killRejectsAMalformedSessionIdWithAReadableMessage() throws Exception {
        DatabaseSessionService service = service("mysql", "jdbc:h2:mem:sess-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");

        assertThatThrownBy(() -> service.kill(1L, "1 OR 1=1", "admin", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话标识无效");
    }
}
