package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.ScheduledQueryRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.ScheduledQuery;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.ScheduledQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledQueryServiceTest {
    @Test
    void refusesAnUnparseableCronBeforeSavingIt() {
        Fixture fixture = new Fixture(Path.of("."), "dev");

        assertThatThrownBy(() -> fixture.service.create(request("每分钟", "not a cron", null), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cron");
    }

    @Test
    void refusesAnUnknownExportFormatAndTimezone() {
        Fixture fixture = new Fixture(Path.of("."), "dev");

        assertThatThrownBy(() -> fixture.service.create(
                new ScheduledQueryRequest(1L, "导出", "select 1", "parquet", "0 0 * * * *", null, true, null), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("导出格式");
        assertThatThrownBy(() -> fixture.service.create(
                new ScheduledQueryRequest(1L, "导出", "select 1", "csv", "0 0 * * * *", "Mars/Olympus", true, null), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时区");
    }

    /** 写操作不该等到半夜才被调度线程发现 —— 保存时就判掉。 */
    @Test
    void refusesAnythingThatIsNotASingleQuery() {
        Fixture fixture = new Fixture(Path.of("."), "dev");

        assertThatThrownBy(() -> fixture.service.create(
                new ScheduledQueryRequest(1L, "清库", "delete from orders", "csv", "0 0 * * * *", null, true, null), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单条查询");
        assertThatThrownBy(() -> fixture.service.create(
                new ScheduledQueryRequest(1L, "两条", "select 1; select 2", "csv", "0 0 * * * *", null, true, null), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单条查询");
    }

    /**
     * 生产连接必须在创建时确认一次：定时任务没有交互确认的机会，跳过确认等于给生产库开了
     * 一个无人值守的出口。
     */
    @Test
    void requiresTheConnectionNameWhenSchedulingAgainstProduction() {
        Fixture fixture = new Fixture(Path.of("."), "prod");

        assertThatThrownBy(() -> fixture.service.create(request("导出", "0 0 * * * *", null), "admin"))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code())
                        .isEqualTo("PRODUCTION_CONFIRMATION_REQUIRED"));

        fixture.service.create(request("导出", "0 0 * * * *", "生产库"), "admin");
        verify(fixture.repository).insert(any());
    }

    @Test
    void writesTheExportToAFileAndRecordsTheRun(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory, "dev");
        fixture.stubTask(new ScheduledQuery(7, 1, "每日订单", "select * from orders", "csv", "0 0 8 * * *",
                "Asia/Shanghai", true, false, null, null, null, null, null, null));

        ScheduledQuery result = fixture.service.run(7, "admin");

        assertThat(Files.list(directory)).hasSize(1);
        assertThat(Files.list(directory).findFirst().orElseThrow().getFileName().toString())
                .startsWith("每日订单-").endsWith(".csv");
        verify(fixture.repository).recordRun(org.mockito.ArgumentMatchers.eq(7L), any(),
                org.mockito.ArgumentMatchers.eq("SUCCESS"), anyString(), anyString());
        assertThat(result).isNotNull();
    }

    /** 一条任务写不出去，不该让这一轮的其他任务跟着停 —— 失败记在任务上，界面看得见。 */
    @Test
    void recordsFailuresInsteadOfThrowing(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory, "dev");
        // 查一张不存在的表：导出必然失败，而失败不该抛给调度线程。
        fixture.stubTask(new ScheduledQuery(7, 1, "会失败的", "select * from no_such_table", "csv", "0 0 8 * * *",
                null, true, false, null, null, null, null, null, null));

        fixture.service.run(7, "admin");

        verify(fixture.repository).recordRun(org.mockito.ArgumentMatchers.eq(7L), any(),
                org.mockito.ArgumentMatchers.eq("FAILED"), anyString(), nullable(String.class));
    }

    /** 任务名里的路径分隔符必须去掉，否则产物会写到别的目录去。 */
    @Test
    void keepsTheFileNameInsideItsDirectory() {
        ScheduledQuery task = new ScheduledQuery(1, 1, "../../etc/passwd", "select 1", "csv", "0 0 * * * *",
                "UTC", true, false, null, null, null, null, null, null);

        String name = ScheduledQueryService.fileName(task, Instant.parse("2026-09-05T01:02:03Z"));

        assertThat(name).doesNotContain("/").doesNotContain("\\\\");
        assertThat(name).startsWith(".._.._etc_passwd-20260905-010203").endsWith(".csv");
    }

    @Test
    void computesTheNextRunInTheTaskTimezone() {
        Fixture fixture = new Fixture(Path.of("."), "dev");
        ScheduledQuery task = new ScheduledQuery(1, 1, "每天八点", "select 1", "csv", "0 0 8 * * *",
                "Asia/Shanghai", true, false, null, null, null, null, null, null);

        Instant next = fixture.service.nextRunAt(task);

        assertThat(next).isNotNull();
        assertThat(next.atZone(ZoneId.of("Asia/Shanghai")).getHour()).isEqualTo(8);
        // 停用的任务不该显示下次执行时间 —— 那会让人以为它还在跑。
        assertThat(fixture.service.nextRunAt(new ScheduledQuery(1, 1, "停用", "select 1", "csv",
                "0 0 8 * * *", null, false, false, null, null, null, null, null, null))).isNull();
    }

    private static ScheduledQueryRequest request(String name, String cron, String confirmation) {
        return new ScheduledQueryRequest(1L, name, "select 1", "csv", cron, "Asia/Shanghai", true, confirmation);
    }

    private static final class Fixture {
        private final ScheduledQueryRepository repository = mock(ScheduledQueryRepository.class);
        private final ScheduledQueryService service;

        /**
         * 用真的 ExportService 跑一个真的 H2：PreparedExport 是 final 的（它靠 Cleaner 保证临时
         * 文件被删掉），mock 不了 —— 而用真的反而把「导出确实写出了内容」也一起测到了。
         */
        private Fixture(Path directory, String environment) {
            String url = "jdbc:h2:mem:scheduled-query-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
            try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE orders(id INT PRIMARY KEY, amount INT)");
                statement.execute("INSERT INTO orders VALUES (1, 100)");
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
            ConnectionService connections = mock(ConnectionService.class);
            when(connections.require(anyLong())).thenReturn(new DbConnection(
                    1L, "生产库", "h2", url, "sa", "", environment, false, Instant.now(), Instant.now()));
            try {
                when(connections.open(anyLong())).thenAnswer(ignored -> java.sql.DriverManager.getConnection(url, "sa", ""));
                when(connections.open(anyLong(), nullable(String.class)))
                        .thenAnswer(ignored -> java.sql.DriverManager.getConnection(url, "sa", ""));
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
            AppProperties properties = new AppProperties();
            properties.getScheduledQuery().setDirectory(directory.toString());
            properties.getSql().setTimeoutSeconds(10);
            ExportService exports = new ExportService(
                    connections,
                    new com.example.dbadmin.core.DialectRegistry(),
                    properties,
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    new SqlStatementClassifier(),
                    new SqlScriptSplitter(),
                    mock(AuditRepository.class),
                    mock(com.example.dbadmin.repo.SqlHistoryRepository.class),
                    new ExecutionGuard());
            when(repository.insert(any())).thenReturn(1L);
            service = new ScheduledQueryService(repository, connections, exports, mock(AuditRepository.class), properties);
            stubTask(new ScheduledQuery(1, 1, "任务", "select * from orders", "csv", "0 0 * * * *",
                    null, true, false, null, null, null, null, null, null));
        }

        private void stubTask(ScheduledQuery task) {
            when(repository.findById(task.id())).thenReturn(java.util.Optional.of(task));
            when(repository.findAll()).thenReturn(List.of(task));
        }
    }
}
