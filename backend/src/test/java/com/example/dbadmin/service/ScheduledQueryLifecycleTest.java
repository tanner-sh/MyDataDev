package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ScheduledQueryRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.ScheduledQuery;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.ScheduledQueryRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledQueryLifecycleTest {
    @TempDir Path directory;
    ScheduledQueryRepository repository;
    ScheduledQueryService service;
    ExportService exports;
    AuditRepository audit;
    AppProperties properties;
    ConnectionService connections;

    @BeforeEach void setUp() throws Exception {
        JdbcDataSource metadata = new JdbcDataSource();
        metadata.setURL("jdbc:h2:mem:export-meta-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(metadata);
        jdbc.execute("CREATE TABLE db_connection(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO db_connection VALUES (1)");
        new ResourceDatabasePopulator(new ClassPathResource("scheduled-query-schema.sql"),
                new ClassPathResource("scheduled-query-run-schema.sql")).execute(metadata);
        repository = spy(new ScheduledQueryRepository(jdbc));
        String url = "jdbc:h2:mem:export-target-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        connections = mock(ConnectionService.class);
        when(connections.require(1)).thenReturn(new DbConnection(1, "测试库", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(connections.open(anyLong(), nullable(String.class))).thenAnswer(call -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong())).thenAnswer(call -> DriverManager.getConnection(url, "sa", ""));
        properties = new AppProperties();
        properties.getScheduledQuery().setDirectory(directory.toString());
        properties.getScheduledQuery().setKeepFiles(1);
        audit = mock(AuditRepository.class);
        exports = spy(new ExportService(connections, new DialectRegistry(), properties, new ObjectMapper(),
                new SqlStatementClassifier(), new SqlScriptSplitter(), mock(AuditRepository.class), mock(SqlHistoryRepository.class), new ExecutionGuard()));
        service = new ScheduledQueryService(repository, connections, exports, audit, properties, new BackgroundTaskControl(properties));
    }

    @AfterEach void tearDown() { service.shutdown(); }

    ScheduledQuery task(String name, String sql) {
        return service.create(new ScheduledQueryRequest(1L, name, sql, "csv", "0 0 8 * * *", "UTC", false, null), "tester");
    }

    @Test void isolatesSameNamesAndRetentionAndRejectsCrossTaskDownloads() throws Exception {
        var first = task("相同名字", "select 42 as answer");
        var second = task("相同名字", "select 99 as answer");
        service.run(first.id(), "tester");
        var old = service.history(first.id(), 50).get(0);
        service.run(second.id(), "tester");
        var other = service.history(second.id(), 50).get(0);
        // 不依赖时间戳排序分辨两次极快的导出。
        Files.setLastModifiedTime(Path.of(old.filePath()), java.nio.file.attribute.FileTime.fromMillis(1000));
        service.run(first.id(), "tester");
        var latest = service.history(first.id(), 50).get(0);
        assertThat(latest.id()).isNotEqualTo(old.id());
        assertThat(Files.readString(service.download(first.id(), latest.id(), "tester"))).contains("42");
        assertThat(Files.readString(service.download(second.id(), other.id(), "tester"))).contains("99");
        assertThat(repository.run(old.id()).orElseThrow().downloadable()).isFalse();
        assertThat(Files.exists(Path.of(old.filePath()))).isFalse();
        assertThatThrownBy(() -> service.download(first.id(), other.id(), "tester")).isInstanceOf(ApiProblemException.class);
        service.delete(first.id(), "tester");
        assertThat(repository.run(latest.id())).isEmpty();
        assertThat(Files.exists(Path.of(other.filePath()))).isTrue();
    }

    @Test void recordsFailureAndReleasesConnectionForTheNextTask() throws Exception {
        var broken = task("错误查询", "select * from nonexistent");
        assertThat(service.run(broken.id(), "tester").lastStatus()).isEqualTo("FAILED");
        assertThat(service.history(broken.id(), 50).get(0).downloadable()).isFalse();
        assertThat(service.active(null)).isEmpty();
        try (var files = Files.list(service.taskDirectory(broken.id()))) { assertThat(files).isEmpty(); }
        assertThat(service.run(task("下一次", "select 1").id(), "tester").lastStatus()).isEqualTo("SUCCESS");
    }

    @Test void cancellingInFlightTaskRejectsDuplicateAndPublishesNoFile() throws Exception {
        var task = task("取消", "select 1");
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            new CountDownLatch(1).await(10, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(exports).prepareCancellable(anyLong(), anyString(), anyString(), anyString(), nullable(String.class), any());
        service.submit(task.id(), "tester");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.submit(task.id(), "tester")).isInstanceOf(ApiProblemException.class).hasMessageContaining("正在运行");
        assertThatThrownBy(() -> service.delete(task.id(), "tester")).isInstanceOf(ApiProblemException.class);
        service.cancel(task.id());
        service.shutdown();
        assertThat(service.require(task.id()).lastStatus()).isEqualTo("CANCELLED");
        assertThat(service.active(null)).isEmpty();
        try (var files = Files.list(service.taskDirectory(task.id()))) { assertThat(files).isEmpty(); }
    }

    @Test void restartRecoversPublishedFilesAndInterruptsUnpublishedRuns() throws Exception {
        var published = task("已发布", "select 1");
        var interrupted = task("未发布", "select 2");
        for (var task : new ScheduledQuery[]{published, interrupted}) {
            repository.beginRun("run-" + task.id(), task, Instant.now());
            Files.createDirectories(service.taskDirectory(task.id()));
        }
        Path complete = service.taskDirectory(published.id()).resolve("complete.csv");
        Files.writeString(complete, "answer\n1\n");
        repository.prepareRunFile("run-" + published.id(), "导出完成。", "complete.csv", complete.toString(), Files.size(complete));
        Path partial = service.taskDirectory(interrupted.id()).resolve("unfinished.part");
        Files.writeString(partial, "incomplete");
        service.recover();
        assertThat(service.require(published.id()).lastStatus()).isEqualTo("SUCCESS");
        assertThat(service.require(interrupted.id()).lastStatus()).isEqualTo("INTERRUPTED");
        assertThat(Files.exists(partial)).isFalse();
        assertThat(service.download(published.id(), "run-" + published.id(), "tester")).isEqualTo(complete);
        assertThat(service.active(null)).isEmpty();
    }

    @Test void auditFailureDoesNotInvalidateACompleteExport() throws Exception {
        var task = task("审计失败", "select 1");
        doThrow(new IllegalStateException("audit unavailable")).when(audit).onConnection(anyString(), eq(ScheduledQueryService.ACTION_RUN), anyLong(), anyString());
        assertThat(service.run(task.id(), "tester").lastStatus()).isEqualTo("SUCCESS");
        assertThat(service.history(task.id(), 50).get(0).downloadable()).isTrue();
    }

    @Test void longChineseNamesFitFilesystemLimits() throws Exception {
        var task = task("中文任务".repeat(25), "select 1");
        assertThat(service.run(task.id(), "tester").lastStatus()).isEqualTo("SUCCESS");
        var run = service.history(task.id(), 50).get(0);
        assertThat(run.fileName().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(255);
        assertThat(Files.size(service.download(task.id(), run.id(), "tester"))).isPositive();
    }

    @Test void completionRecordFailureRetainsPublishedFileForRecovery() throws Exception {
        var task = task("状态失败", "select 1");
        doThrow(new IllegalStateException("metadata unavailable")).when(repository).completeRun(anyString(), anyLong(), any(), eq("SUCCESS"), anyString(), anyString(), anyString(), anyLong());
        service.run(task.id(), "tester");
        var run = service.history(task.id(), 50).get(0);
        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(Files.exists(Path.of(run.filePath()))).isTrue();
        doCallRealMethod().when(repository).completeRun(anyString(), anyLong(), any(), anyString(), anyString(), nullable(String.class), nullable(String.class), anyLong());
        service.recover();
        assertThat(service.require(task.id()).lastStatus()).isEqualTo("SUCCESS");
    }

    @Test void downloadRefusesSymlinkFile() throws Exception {
        var task = task("符号链接", "select 1");
        service.run(task.id(), "tester");
        var run = service.history(task.id(), 50).get(0);
        Path file = Path.of(run.filePath());
        Path outside = directory.resolve("outside.csv");
        Files.writeString(outside, "outside");
        Files.delete(file);
        Files.createSymbolicLink(file, outside);
        assertThatThrownBy(() -> service.download(task.id(), run.id(), "tester")).isInstanceOf(ApiProblemException.class);
    }
}
