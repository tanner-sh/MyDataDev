package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 服务端生成脚本这条路径（CSV 导入）与直传 SQL 文件共用同一套闸门。
 *
 * <p>此前 {@code uploadScript} 既不查剩余空间也不取上传信号量，于是 max-concurrent-uploads
 * 对 CSV 导入完全不生效：并发导入能把磁盘写满，也能把 Web 工作线程占光。</p>
 */
class SqlFileExecutionServiceUploadGuardTest {
    @TempDir
    Path directory;

    private AppProperties properties;
    private LargeFileUploadGuard uploadGuard;
    private SqlFileExecutionService service;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AppProperties();
        properties.getSqlFile().setDirectory(directory.toString());
        properties.getBackgroundTasks().setMaxConcurrentUploads(1);
        uploadGuard = new LargeFileUploadGuard(properties);

        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(new DbConnection(
                1L, "h2", "h2", "jdbc:h2:mem:import", "sa", "", "dev", false, Instant.now(), Instant.now()));

        service = new SqlFileExecutionService(
                mock(SqlFileExecutionRepository.class), connections, new ExecutionGuard(),
                new SqlStatementClassifier(), new SqlScriptSplitter(), new DialectRegistry(),
                mock(MetadataService.class), mock(AuditRepository.class), properties,
                mock(SqlFileExecutionCoordinator.class), mock(BackgroundTaskControl.class), uploadGuard
        );
    }

    private Throwable uploadWith(long estimatedBytes, SqlFileExecutionService.ScriptWriter writer) {
        return org.assertj.core.api.Assertions.catchThrowable(() -> service.uploadScript(
                1L, "orders.csv", estimatedBytes, writer, "admin", "DATA_IMPORT_UPLOAD", "table=orders"));
    }

    @Test
    void generatedScriptsShareTheUploadConcurrencyLimit() {
        // 名额被另一次上传占着。
        assertThat(uploadGuard.tryAcquire()).isTrue();

        assertThat(uploadWith(0, out -> "rows=0"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("UPLOAD_BUSY"));
    }

    @Test
    void refusesToStartWhenTheEstimateDoesNotFitOnDisk() {
        assertThat(uploadWith(Long.MAX_VALUE / 4, out -> "rows=0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("剩余空间不足");
        // 拒绝发生在落盘之前，不留半个文件，名额也没被占住。
        assertThat(listFiles()).isEmpty();
        assertThat(uploadGuard.tryAcquire()).isTrue();
    }

    @Test
    void releasesThePermitAndDeletesTheStagingFileWhenConversionFails() {
        assertThat(uploadWith(0, out -> {
            out.write("INSERT INTO orders VALUES (1);\n");
            throw new IllegalArgumentException("CSV 第 2 行的双引号没有闭合");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(listFiles()).isEmpty();
        // 转换失败不能把名额漏掉，否则几次坏文件就把并发上限用光了。
        assertThat(uploadGuard.tryAcquire()).isTrue();
    }

    private java.util.List<Path> listFiles() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
