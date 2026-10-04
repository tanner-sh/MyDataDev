package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
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
