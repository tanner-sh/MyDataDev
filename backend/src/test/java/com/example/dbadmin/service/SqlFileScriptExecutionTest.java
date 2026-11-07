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
import java.util.List;
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
        commands = new com.example.dbadmin.repo.SqlCommandRepository(new JdbcTemplate(source));
        service.setCommandRepository(commands);
    }
    private com.example.dbadmin.repo.SqlCommandRepository commands;
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

    @Test void dbeaverDelimiterFilesAreDetectedAnalysedAndExecutedWithTheSameBoundaries() throws Exception {
        String script = "-- DBeaver 分隔符为 !\nSET DEFINE OFF\nINSERT INTO t VALUES(1)\n!\nINSERT INTO t VALUES(2);\n!\nINSERT INTO t VALUES(3)\n!\n";
        long id = upload(script);
        var ready = service.get(id);
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.statementTotal()).isEqualTo(3);
        assertThat(ready.statementDelimiter()).isEqualTo("独占行 !");
        assertThat(service.start(id, null, "tester").status()).isEqualTo("SUCCESS");
        assertThat(target.queryForObject("SELECT COUNT(*) FROM t", Integer.class)).isEqualTo(3);
    }

    @Test void explicitBlankLineModeIsStoredForTheWholeJob() throws Exception {
        byte[] bytes = "INSERT INTO t VALUES(7)\n\nINSERT INTO t VALUES(8)\n".getBytes(StandardCharsets.UTF_8);
        long id = service.upload(1, "blank.sql", bytes.length, new ByteArrayInputStream(bytes), "tester", SqlScriptDelimiter.BLANK_LINE).id();
        assertThat(service.get(id).statementTotal()).isEqualTo(2);
        assertThat(service.get(id).statementDelimiter()).isEqualTo("空行分隔");
        assertThat(service.start(id, null, "tester").status()).isEqualTo("SUCCESS");
        assertThat(target.queryForList("SELECT id FROM t ORDER BY id", Integer.class)).containsExactly(7, 8);
    }

    @Test void editorScriptsBecomeOrdinaryBackgroundJobs() throws Exception {
        var job = service.submitEditorScript(1, "INSERT INTO t VALUES(41);\nINSERT INTO t VALUES(42);", "tester", SqlScriptDelimiter.AUTO);
        assertThat(service.get(job.id()).statementTotal()).isEqualTo(2);
        assertThat(service.get(job.id()).statementDelimiter()).isNull();
        assertThat(service.start(job.id(), null, "tester").status()).isEqualTo("SUCCESS");
        assertThat(target.queryForObject("SELECT COUNT(*) FROM t WHERE id IN (41, 42)", Integer.class)).isEqualTo(2);
    }

    @Test void editorScriptKeepsAutoWhenTheUserAlreadyDeclinedTheDetectedDelimiter() throws Exception {
        // 工作台里用户看到「识别到 !」后选了保持自动，再因为脚本大转到后台：后台不能再自己识别一次改成按 ! 切。
        String sql = "INSERT INTO t VALUES(51);\nINSERT INTO t VALUES(52);\n!\n";
        var declined = service.submitEditorScript(1, sql, "tester", SqlScriptDelimiter.AUTO, true);
        assertThat(service.get(declined.id()).statementDelimiter()).isNull();

        var undecided = service.submitEditorScript(1, sql, "tester", SqlScriptDelimiter.AUTO, false);
        assertThat(service.get(undecided.id()).statementDelimiter()).isEqualTo("独占行 !");
    }

    @Test void commandWindowAutocommitReallyCommitsWhenTheDialectStreamsWithoutAutocommit() throws Exception {
        // PostgreSQL 方言为流式游标关掉 autocommit。命令窗口「逐条自动提交」以前就这样悬在一个从不提交的事务里：
        // 日志报「成功 2 条」，连接一关两行都没了（真实 PostgreSQL 上出错之后还会每条都报 transaction is aborted）。
        var pgSource = new DriverManagerDataSource("jdbc:h2:mem:pg-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        var pg = new JdbcTemplate(pgSource);
        pg.execute("CREATE TABLE t(id INT PRIMARY KEY)");
        when(connections.require(2)).thenReturn(new DbConnection(2L, "pg", "postgresql", "jdbc:postgresql://h2-stand-in/x", "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(connections.open(2)).thenAnswer(i -> pgSource.getConnection());

        var job = service.submitCommand(2, "INSERT INTO t VALUES(1);\nINSERT INTO missing VALUES(2);\nINSERT INTO t VALUES(3);", "tester",
                SqlScriptDelimiter.AUTO, "CONTINUE", "AUTOCOMMIT", null, null);

        assertThat(service.get(job.id()).successCount()).isEqualTo(2);
        assertThat(pg.queryForList("SELECT id FROM t ORDER BY id", Integer.class)).containsExactly(1, 3);
    }

    private List<String> commandLog(long id) {
        return service.commandView(id, 0, 1000).entries().stream().map(entry -> entry.kind() + ":" + entry.text()).toList();
    }

    @Test void commandWindowStopsAtTheFirstErrorAndKeepsWhatAutocommitAlreadyWrote() throws Exception {
        var job = service.submitCommand(1, "INSERT INTO t VALUES(1);\nINSERT INTO missing VALUES(2);\nINSERT INTO t VALUES(3);", "tester",
                SqlScriptDelimiter.AUTO, "STOP", "AUTOCOMMIT", null, null);
        var finished = service.get(job.id());
        assertThat(finished.status()).isEqualTo("FAILED");
        assertThat(finished.failedStatementIndex()).isEqualTo(2);
        assertThat(target.queryForList("SELECT id FROM t", Integer.class)).containsExactly(1);
        List<String> log = commandLog(job.id());
        assertThat(log).first().asString().startsWith("INFO:开始执行：共 3 条；逐条自动提交；遇错停止");
        assertThat(log).contains("STATEMENT:INSERT INTO t VALUES(1)", "RESULT:已插入 1 行。", "STATEMENT:INSERT INTO missing VALUES(2)");
        assertThat(log).anySatisfy(line -> assertThat(line).startsWith("ERROR:").containsIgnoringCase("MISSING"));
        assertThat(log).noneSatisfy(line -> assertThat(line).contains("VALUES(3)"));
        assertThat(log).last().asString().startsWith("INFO:已停止：第 2 条失败（第 2–2 行）");
    }

    @Test void commandWindowCanContinuePastErrorsAndCountsThem() throws Exception {
        var job = service.submitCommand(1, "INSERT INTO t VALUES(1);\nINSERT INTO missing VALUES(2);\nINSERT INTO t VALUES(3);\nSELECT id FROM t ORDER BY id;",
                "tester", SqlScriptDelimiter.AUTO, "CONTINUE", "AUTOCOMMIT", null, null);
        var finished = service.get(job.id());
        assertThat(finished.status()).isEqualTo("SUCCESS");
        assertThat(finished.phase()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(finished.failedStatementIndex()).isEqualTo(2);
        assertThat(target.queryForList("SELECT id FROM t ORDER BY id", Integer.class)).containsExactly(1, 3);
        var view = service.commandView(job.id(), 0, 1000);
        assertThat(view.command().failedCount()).isEqualTo(1);
        assertThat(view.command().firstFailedIndex()).isEqualTo(2);
        // 查询结果打成文本表，带行数。
        assertThat(commandLog(job.id())).anySatisfy(line -> assertThat(line).startsWith("RESULT:ID").contains("\n1\n3").endsWith("已选择 共 2 行。"));
        assertThat(commandLog(job.id())).last().asString().isEqualTo("INFO:执行完成：成功 3 条，失败 1 条。");
    }

    @Test void sqlPlusDirectivesShowUpInOrderAndWheneverSwitchesThePolicy() throws Exception {
        String script = "PROMPT 第一步\nINSERT INTO t VALUES(1);\nWHENEVER SQLERROR CONTINUE\nINSERT INTO missing VALUES(2);\n"
                + "SET DEFINE OFF\nPROMPT 第二步\nINSERT INTO t VALUES(3);\n";
        var job = service.submitCommand(1, script, "tester", SqlScriptDelimiter.AUTO, "STOP", "AUTOCOMMIT", null, null);
        assertThat(service.get(job.id()).status()).isEqualTo("SUCCESS");
        List<String> log = commandLog(job.id());
        assertThat(log.indexOf("PROMPT:第一步")).isLessThan(log.indexOf("STATEMENT:INSERT INTO t VALUES(1)"));
        assertThat(log).contains("INFO:WHENEVER SQLERROR CONTINUE：之后遇错继续。", "INFO:已忽略 SQL*Plus 设置：SET DEFINE OFF");
        assertThat(log.indexOf("PROMPT:第二步")).isGreaterThan(log.indexOf("INFO:已忽略 SQL*Plus 设置：SET DEFINE OFF"))
                .isLessThan(log.indexOf("STATEMENT:INSERT INTO t VALUES(3)"));
        assertThat(target.queryForList("SELECT id FROM t ORDER BY id", Integer.class)).containsExactly(1, 3);
    }

    @Test void scriptControlledTransactionsFollowTheScriptAndTheTailPolicy() throws Exception {
        var job = service.submitCommand(1, "INSERT INTO t VALUES(1);\nCOMMIT;\nINSERT INTO t VALUES(2);\n", "tester",
                SqlScriptDelimiter.AUTO, "STOP", "SCRIPT", "ROLLBACK", null);
        assertThat(service.get(job.id()).status()).isEqualTo("SUCCESS");
        assertThat(target.queryForList("SELECT id FROM t", Integer.class)).containsExactly(1);
        assertThat(commandLog(job.id())).contains("RESULT:已提交。").last().asString().contains("剩余未提交事务已回滚");
    }

    @Test void plainFileTasksStillRejectWheneverContinue() throws Exception {
        long id = upload("WHENEVER SQLERROR CONTINUE\nINSERT INTO t VALUES(1);\n");
        assertThat(service.get(id).status()).isEqualTo("FAILED");
        assertThat(service.get(id).message()).contains("命令窗口");
    }

    @Test void routineCommandsRunTheirOwnStatementOnceUnderTheBackgroundTimeout() throws Exception {
        int[] calls = {0};
        int[] timeout = {0};
        boolean[] autocommit = {false};
        var job = service.submitRoutineCommand(1, "{call APP.LOAD_DAY(?, ?)}", "tester", null, (connection, context) -> {
            calls[0]++;
            timeout[0] = context.timeoutSeconds();
            autocommit[0] = connection.getAutoCommit();
            try (var statement = connection.createStatement()) {
                context.register(statement);
                statement.execute("INSERT INTO t VALUES(9)");
            }
            return new SqlFileExecutionService.CommandUnitResult("调用完成，耗时 1 ms。\n返回值：OK", List.of("第一步完成"), 0);
        });
        assertThat(service.get(job.id()).status()).isEqualTo("SUCCESS");
        assertThat(calls[0]).isEqualTo(1);
        assertThat(timeout[0]).isEqualTo(7200);
        assertThat(autocommit[0]).isTrue();
        assertThat(target.queryForList("SELECT id FROM t", Integer.class)).containsExactly(9);
        assertThat(commandLog(job.id())).contains("STATEMENT:{call APP.LOAD_DAY(?, ?)}", "RESULT:调用完成，耗时 1 ms。\n返回值：OK", "OUTPUT:第一步完成");
    }

    /** 驱动把取消报成和超时一样的错误：任务要记成「已取消」，runner 也要能分辨出是用户停的。 */
    @Test void cancellingARoutineCommandCancelsTheRegisteredStatement() throws Exception {
        java.sql.Statement running = mock(java.sql.Statement.class);
        boolean[] sawCancel = {false};
        long[] jobId = {0};
        doAnswer(i -> { jobId[0] = i.getArgument(0); ((Runnable)i.getArgument(1)).run(); ((Runnable)i.getArgument(2)).run(); return null; })
                .when(coordinator).submit(anyLong(), any(Runnable.class), any(Runnable.class));
        var job = service.submitRoutineCommand(1, "{call APP.LONG_JOB}", "tester", null, (connection, context) -> {
            context.register(running);
            service.cancel(jobId[0], "tester");
            sawCancel[0] = context.cancelRequested();
            throw new java.sql.SQLException("ORA-01013: user requested cancel of current operation", "72000", 1013);
        });
        verify(coordinator).cancelStatement(running);
        assertThat(sawCancel[0]).isTrue();
        assertThat(service.get(job.id()).status()).isEqualTo("CANCELLED");
    }

    private long upload(String sql) throws Exception {
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        return service.upload(1, "test.sql", bytes.length, new ByteArrayInputStream(bytes), "tester").id();
    }
}
