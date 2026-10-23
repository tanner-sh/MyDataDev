package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlFileScriptExecutionTest {
    @TempDir Path directory;
    private SqlFileExecutionService service;
    private SqlFileExecutionRepository jobs;
    private JdbcTemplate target;
    private SqlFileExecutionCoordinator coordinator;
    private ConnectionService connections;
    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:file-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jobs = new SqlFileExecutionRepository(new JdbcTemplate(source));
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:target-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=Oracle", "sa", "");
        target = new JdbcTemplate(dataSource);
        target.execute("CREATE TABLE t(id INT PRIMARY KEY)");
        connections = mock(ConnectionService.class);
        when(connections.require(1)).thenReturn(new DbConnection(1L, "test", "oracle", dataSource.getUrl(), "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(connections.open(1)).thenAnswer(i -> dataSource.getConnection());
        coordinator = mock(SqlFileExecutionCoordinator.class);
        doAnswer(i -> { ((Runnable)i.getArgument(1)).run(); return null; }).when(coordinator).submit(anyLong(), any(Runnable.class));
        doAnswer(i -> { try { ((Runnable)i.getArgument(1)).run(); } finally { ((Runnable)i.getArgument(2)).run(); } return null; }).when(coordinator).submit(anyLong(), any(Runnable.class), any(Runnable.class));
        var properties = new AppProperties(); properties.getSqlFile().setDirectory(directory.toString());
        var control = new BackgroundTaskControl(properties);
        service = new SqlFileExecutionService(jobs, connections, new ExecutionGuard(), new SqlStatementClassifier(), new SqlScriptSplitter(), new DialectRegistry(),
                mock(MetadataService.class), mock(AuditRepository.class), properties, coordinator, control, new LargeFileUploadGuard(properties));
    }
    @Test void analysisKeepsCommitAndRejectsWrongModeWithoutDeletingReadyFile() throws Exception {
        long id = upload("INSERT INTO t VALUES(1); COMMIT;");
        assertThat(service.get(id).transaction().controlCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.start(id, null, "tester")).hasMessageContaining("脚本控制事务");
        assertThat(service.get(id).status()).isEqualTo("READY");
        assertThat(Path.of(jobs.findById(id).orElseThrow().filePath())).exists();
        assertThat(service.start(id, null, "tester", "SCRIPT", "ROLLBACK").status()).isEqualTo("SUCCESS");
        assertThat(target.queryForObject("SELECT COUNT(*) FROM t", Integer.class)).isEqualTo(1);
    }
    @Test void commitsThenRollsBackFailureWithLineDiagnostics() throws Exception {
        long id = upload("INSERT INTO t VALUES(1);\nCOMMIT;\nINSERT INTO t VALUES(2);\nINSERT INTO missing VALUES(3);\n");
        var result = service.start(id, null, "tester", "SCRIPT", "COMMIT");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.transaction().lastCommitIndex()).isEqualTo(2);
        assertThat(result.transaction().failedStartLine()).isEqualTo(4);
        assertThat(target.queryForList("SELECT id FROM t", Integer.class)).containsExactly(1);
    }
    @Test void bothTailPoliciesAndBatchCompatibility() throws Exception {
        assertThat(service.start(upload("INSERT INTO t VALUES(1);"), null, "tester", "SCRIPT", "ROLLBACK").status()).isEqualTo("SUCCESS");
        assertThat(target.queryForObject("SELECT COUNT(*) FROM t", Integer.class)).isZero();
        service.start(upload("INSERT INTO t VALUES(2);"), null, "tester", "SCRIPT", "COMMIT");
        service.start(upload("INSERT INTO t VALUES(3);"), null, "tester");
        assertThat(target.queryForList("SELECT id FROM t ORDER BY id", Integer.class)).containsExactly(2, 3);
    }

    @ParameterizedTest
    @CsvSource({"SCRIPT,COMMIT,1,true", "SCRIPT,ROLLBACK,0,true", "BATCH,COMMIT,1,true",
            "SCRIPT,COMMIT,1,false", "SCRIPT,ROLLBACK,0,false", "BATCH,COMMIT,1,false"})
    void cleanupFailureDoesNotReplaceConfirmedExecution(String mode, String ending, int persistedRows, boolean statementClose) throws Exception {
        failResourceClose(statementClose);
        var result = service.start(upload("INSERT INTO t VALUES(1);"), null, "tester", mode, ending);
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.phase()).isEqualTo("COMPLETED");
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedStatementIndex()).isNull();
        assertThat(result.message()).contains("收尾警告", "connection close failed");
        if (statementClose) assertThat(result.message()).contains("statement close failed");
        if (mode.equals("SCRIPT")) {
            assertThat(result.transaction().outcome()).isEqualTo(ending.equals("COMMIT") ? "COMMITTED" : "TAIL_ROLLED_BACK");
            assertThat(result.transaction().commitCount()).isEqualTo(ending.equals("COMMIT") ? 1 : 0);
            assertThat(result.transaction().rollbackCount()).isEqualTo(ending.equals("ROLLBACK") ? 1 : 0);
        }
        assertThat(target.queryForObject("SELECT COUNT(*) FROM t", Integer.class)).isEqualTo(persistedRows);
    }

    @Test void sqlFailureKeepsOriginalErrorAndEarlierCommitWhenCleanupAlsoFails() throws Exception {
        failResourceClose(true);
        var result = service.start(upload("INSERT INTO t VALUES(1); COMMIT; INSERT INTO missing VALUES(2);"),
                null, "tester", "SCRIPT", "ROLLBACK");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failedStatementIndex()).isEqualTo(3);
        assertThat(result.message()).contains("MISSING", "收尾错误", "statement close failed", "connection close failed");
        assertThat(result.transaction().lastCommitIndex()).isEqualTo(2);
        assertThat(target.queryForList("SELECT id FROM t", Integer.class)).containsExactly(1);
    }

    @Test void ddlAndExplicitCommitKeepTheirResultsWhenConnectionCloseFails() throws Exception {
        failResourceClose(false);
        var result = service.start(upload("CREATE TABLE deployed(id INT); INSERT INTO deployed VALUES(1); COMMIT;"),
                null, "tester", "SCRIPT", "ROLLBACK");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.transaction().outcome()).isEqualTo("TAIL_ROLLED_BACK");
        assertThat(result.transaction().commitCount()).isEqualTo(1);
        assertThat(result.transaction().rollbackCount()).isEqualTo(1);
        assertThat(result.transaction().lastCommitIndex()).isEqualTo(3);
        assertThat(result.message()).contains("收尾警告");
        assertThat(target.queryForList("SELECT id FROM deployed", Integer.class)).containsExactly(1);
    }

    @Test void emptyAnonymousBlockCompletesWithTailRollback() throws Exception {
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        when(connections.open(1)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.getUpdateCount()).thenReturn(-1);
        var result = service.start(upload("BEGIN\n NULL;\nEND;\n/"), null, "tester", "SCRIPT", "ROLLBACK");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.transaction().outcome()).isEqualTo("TAIL_ROLLED_BACK");
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connections).discardOnClose(connection);
    }

    @Test void lostExecutionReplyStaysUnknownAndIsNeverRetried() throws Exception {
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        when(connections.open(1)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new java.sql.SQLRecoverableException("unexpected end of stream", "08006"));
        doThrow(new java.sql.SQLException("Connection is closed")).when(connection).rollback();
        doThrow(new java.sql.SQLException("connection close failed")).when(connection).close();
        var result = service.start(upload("BEGIN\n NULL;\nEND;\n/"), null, "tester", "SCRIPT", "COMMIT");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.phase()).isEqualTo("TRANSACTION_UNKNOWN");
        assertThat(result.successCount()).isZero();
        assertThat(result.transaction().outcome()).isEqualTo("UNKNOWN");
        assertThat(result.message()).contains("unexpected end of stream", "事务结果未知", "不会自动重跑", "connection close failed");
        verify(statement).execute(anyString());
        verify(connection, never()).commit();
    }

    @Test void cancellationKeepsItsStatusWhenConnectionCloseFails() throws Exception {
        long id = upload("INSERT INTO t VALUES(1); INSERT INTO t VALUES(2);");
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        when(connections.open(1)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenAnswer(i -> {
            service.cancel(id, "tester");
            throw new java.sql.SQLException("query cancelled", "HY008");
        });
        doThrow(new java.sql.SQLException("connection close failed")).when(connection).close();
        var result = service.start(id, null, "tester", "SCRIPT", "COMMIT");
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.transaction().outcome()).isEqualTo("ROLLED_BACK");
        assertThat(result.message()).contains("query cancelled", "connection close failed");
        verify(statement).execute(anyString());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void finalCommitFailureCannotBecomeSuccessfulCleanupWarning() throws Exception {
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        when(connections.open(1)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.getUpdateCount()).thenReturn(-1);
        doThrow(new java.sql.SQLException("commit reply lost", "08006")).when(connection).commit();
        doThrow(new java.sql.SQLException("connection close failed")).when(connection).close();
        var result = service.start(upload("INSERT INTO t VALUES(1);"), null, "tester", "SCRIPT", "COMMIT");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.phase()).isEqualTo("TRANSACTION_UNKNOWN");
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.transaction().outcome()).isEqualTo("UNKNOWN");
        assertThat(result.transaction().commitCount()).isZero();
        assertThat(result.message()).contains("commit reply lost", "事务结果未知", "connection close failed");
        verify(connection).commit();
        verify(connection).rollback();
    }

    private void failResourceClose(boolean statementClose) throws Exception {
        when(connections.open(1)).thenAnswer(i -> {
            var real = target.getDataSource().getConnection();
            var connection = mock(java.sql.Connection.class, org.mockito.AdditionalAnswers.delegatesTo(real));
            if (statementClose) {
                doAnswer(ignored -> {
                    var realStatement = real.createStatement();
                    var statement = mock(java.sql.Statement.class, org.mockito.AdditionalAnswers.delegatesTo(realStatement));
                    doAnswer(close -> { realStatement.close(); throw new java.sql.SQLException("statement close failed"); }).when(statement).close();
                    return statement;
                }).when(connection).createStatement();
            }
            doAnswer(close -> { real.close(); throw new java.sql.SQLException("connection close failed"); }).when(connection).close();
            return connection;
        });
    }
    @Test void storedProcedureCommitIsNotATopLevelControl() throws Exception {
        long id = upload("-- log\nCREATE OR REPLACE PROCEDURE p AS\nBEGIN\n COMMIT;\nEND;\n/\n");
        assertThat(service.get(id).statementTotal()).isEqualTo(1);
        assertThat(service.get(id).transaction().controlCount()).isZero();
        assertThat(service.get(id).ddlCount()).isEqualTo(1);
    }
    @Test void queuedCancellationWaitsForWorkerCleanup() throws Exception {
        long id = upload("INSERT INTO t VALUES(1); COMMIT;");
        Runnable[] queued = {null};
        doAnswer(i -> { queued[0] = i.getArgument(1); return null; }).when(coordinator).submit(anyLong(), any(Runnable.class), any(Runnable.class));
        assertThat(service.start(id, null, "tester", "SCRIPT", "ROLLBACK").status()).isEqualTo("QUEUED");
        assertThat(service.cancel(id, "tester").status()).isEqualTo("QUEUED");
        Path file = Path.of(jobs.findById(id).orElseThrow().filePath());
        assertThat(file).exists();
        queued[0].run();
        assertThat(service.get(id).status()).isEqualTo("CANCELLED");
        assertThat(file).doesNotExist();
        assertThat(target.queryForObject("SELECT COUNT(*) FROM t", Integer.class)).isZero();
    }

    @Test void sqlServerProcedureIsOneCompleteGoUnitWithoutOracleSlash() throws Exception {
        when(connections.require(1)).thenReturn(new DbConnection(1L, "target", "sqlserver", "jdbc:test", "sa", "", "dev", false, Instant.now(), Instant.now()));
        long id = upload("CREATE PROCEDURE dbo.qa_proc AS BEGIN SELECT 1; SELECT 2; END\nGO\n");
        assertThat(service.get(id).status()).isEqualTo("READY");
        assertThat(service.get(id).statementTotal()).isEqualTo(1);
        assertThat(service.get(id).ddlCount()).isEqualTo(1);
        assertThat(service.get(id).transaction().controlCount()).isZero();
    }

    @Test void jdbcCancellationExceptionFinishesAsCancelledAndStopsFollowingUnits() throws Exception {
        when(connections.require(1)).thenReturn(new DbConnection(1L, "target", "sqlserver", "jdbc:test", "sa", "", "dev", false, Instant.now(), Instant.now()));
        long id = upload("SELECT 1\nGO\nINSERT INTO t VALUES(7)\nGO\n");
        when(connections.open(1)).thenAnswer(i -> {
            var real = target.getDataSource().getConnection();
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{java.sql.Connection.class}, (proxy, method, args) -> {
                try {
                    if (method.getName().equals("createStatement")) {
                        var statement = real.createStatement();
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{java.sql.Statement.class}, (p,m,a) -> {
                            if (m.getName().equals("execute")) { service.cancel(id, "tester"); throw new java.sql.SQLException("查询被取消", "HY008"); }
                            try { return m.invoke(statement,a); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                        });
                    }
                    return method.invoke(real,args);
                } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
        });
        assertThat(service.start(id, null, "tester").status()).isEqualTo("CANCELLED");
        assertThat(target.queryForObject("SELECT COUNT(*) FROM t", Integer.class)).isZero();
    }

    @Test void opaqueStatementInBatchCannotBeDowngradedToOrdinaryWrite() throws Exception {
        when(connections.require(1)).thenReturn(new DbConnection(1L, "test", "sqlserver", "jdbc:test", "sa", "", "dev", false, Instant.now(), Instant.now()));
        long id = upload("INSERT INTO t VALUES(1); EXEC dangerous_procedure;\nGO\n");
        assertThat(service.get(id).unknownCount()).isEqualTo(1);
        assertThat(service.get(id).mutationCount()).isZero();
    }

    private long upload(String sql) throws Exception {
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        return service.upload(1, "test.sql", bytes.length, new ByteArrayInputStream(bytes), "tester").id();
    }
}
