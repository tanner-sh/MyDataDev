package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaSnapshotTargetRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SchemaSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构快照与漂移。
 *
 * <p>元数据用 mock：这些用例校验的是快照怎么存、什么时候算「变了」、以及漂移的方向对不对 ——
 * 与具体数据库怎么报告列类型无关。仓储层用真的 H2，因为「结构没变时不新增行」这条正是靠
 * 校验和与 last_seen_at 两列实现的。</p>
 */
class SchemaSnapshotServiceTest {
    private MetadataService metadata;
    private SchemaSnapshotRepository repository;
    private AuditRepository audit;
    private SchemaSnapshotService service;
    private List<ObjectDetail> currentSchema;

    private static ColumnInfo column(String name, String type) {
        return new ColumnInfo(name, type, 100, true, null, 1, null);
    }

    private static ObjectDetail table(String name, ColumnInfo... columns) {
        return new ObjectDetail(null, name, "TABLE", List.of(columns), List.of(), List.of("id"), "PK", "v1");
    }

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:snapshot-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("schema-snapshot-schema.sql")).execute(dataSource);
        // 快照按外键挂在连接上：连接被删时它的历史一并清掉，所以这里得先有一条真的连接。
        jdbc.update("INSERT INTO db_connection(id, name, db_type, jdbc_url) VALUES (1, '主库', 'h2', 'jdbc:h2:mem:x')");
        repository = new SchemaSnapshotRepository(jdbc);

        currentSchema = new ArrayList<>(List.of(
                table("orders", column("id", "BIGINT"), column("amount", "DECIMAL")),
                table("customers", column("id", "BIGINT"), column("name", "VARCHAR"))));

        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(new DbConnection(
                1L, "主库", "h2", "jdbc:h2:mem:x", "sa", "", "dev", false, Instant.now(), Instant.now()));

        metadata = mock(MetadataService.class);
        when(metadata.inspect(anyLong(), any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> catalog());
        when(metadata.detail(anyLong(), any(), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(2, String.class);
            return currentSchema.stream().filter(detail -> detail.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("表不存在：" + name));
        });

        audit = mock(AuditRepository.class);
        service = new SchemaSnapshotService(connections, metadata, repository, audit, new ObjectMapper());
    }

    private MetadataResponse catalog() {
        List<DbObject> objects = currentSchema.stream()
                .map(detail -> new DbObject(null, detail.name(), "TABLE", List.of(), List.of()))
                .toList();
        return new MetadataResponse(List.of("PUBLIC"), "PUBLIC", "PUBLIC", "SCHEMA", objects,
                objects.size(), true, 0, 500, false, Instant.now().toString(), false);
    }

    /**
     * 结构没变时不新增快照。
     *
     * <p>时间线上留下的应该是「结构变了」的时刻，而不是「我们检查过」的时刻。每小时采一次、
     * 每次都增一行的话，一周之后要在 168 条一模一样的记录里找那一次真正的变化。</p>
     */
    @Test
    void anUnchangedSchemaAdvancesTheExistingSnapshotInsteadOfAddingOne() throws Exception {
        var first = service.capture(1L, "PUBLIC", "基线", "tanner");
        Thread.sleep(5);
        var second = service.capture(1L, "PUBLIC", "再采一次", "tanner");

        assertThat(first.changed()).isTrue();
        assertThat(second.changed()).isFalse();
        assertThat(second.snapshot().id()).isEqualTo(first.snapshot().id());
        assertThat(service.timeline(1L, "PUBLIC", 50)).hasSize(1);
        // 「这个结构从 capturedAt 一直保持到 lastSeenAt」—— 后者要往前走。
        assertThat(repository.findById(first.snapshot().id()).orElseThrow().lastSeenAt())
                .isAfterOrEqualTo(repository.findById(first.snapshot().id()).orElseThrow().capturedAt());
        verify(audit).onConnection(eq("tanner"), eq("SCHEMA_SNAPSHOT_UNCHANGED"), eq(1L), anyString(), anyString());
    }

    @Test
    void aChangedSchemaAddsANewSnapshotToTheTimeline() throws Exception {
        service.capture(1L, "PUBLIC", "基线", "tanner");
        currentSchema.add(table("refunds", column("id", "BIGINT")));

        assertThat(service.capture(1L, "PUBLIC", null, "tanner").changed()).isTrue();
        assertThat(service.timeline(1L, "PUBLIC", 50)).hasSize(2);
    }

    /**
     * structureVersion 是一次读取的指纹，不是结构的一部分。
     *
     * <p>原样存进快照的话，两份完全相同的结构会算出不同的校验和 —— 时间线上每次采集都多出
     * 一条「变了」，这个功能就只会制造噪音。</p>
     */
    @Test
    void ignoresTheStructureVersionWhenDecidingWhetherAnythingChanged() throws Exception {
        var first = service.capture(1L, "PUBLIC", null, "tanner");
        // 同样的结构，但元数据这次报了一个新的读取指纹。
        currentSchema.replaceAll(detail -> new ObjectDetail(detail.schemaName(), detail.name(), detail.type(),
                detail.columns(), detail.indexes(), detail.primaryKeys(), detail.primaryKeyName(), "v2"));

        assertThat(service.capture(1L, "PUBLIC", null, "tanner").changed()).isFalse();
        assertThat(service.timeline(1L, "PUBLIC", 50)).hasSize(1);
        assertThat(first.snapshot().checksum()).isNotBlank();
    }

    /** 「上周到现在有没有人动过结构」是这个功能最常被问的一句。 */
    @Test
    void comparesASnapshotAgainstTheCurrentSchema() throws Exception {
        var baseline = service.capture(1L, "PUBLIC", "上线前", "tanner");
        currentSchema.add(table("refunds", column("id", "BIGINT")));
        currentSchema.removeIf(detail -> detail.name().equals("customers"));
        currentSchema.replaceAll(detail -> detail.name().equals("orders")
                ? table("orders", column("id", "BIGINT"), column("amount", "DECIMAL"), column("channel", "VARCHAR"))
                : detail);

        var drift = service.drift(baseline.snapshot().id(), null, "tanner");

        assertThat(drift.targetLabel()).isEqualTo("当前结构");
        assertThat(drift.target()).isNull();
        // 方向按时间读：后来才有的表算「新增」。
        assertThat(drift.summary().onlyInTarget()).isEqualTo(1);
        assertThat(drift.summary().onlyInSource()).isEqualTo(1);
        assertThat(drift.summary().different()).isEqualTo(1);
        assertThat(drift.tables()).extracting("tableName", "status").contains(
                org.assertj.core.api.Assertions.tuple("refunds", SchemaComparison.STATUS_ONLY_IN_TARGET),
                org.assertj.core.api.Assertions.tuple("customers", SchemaComparison.STATUS_ONLY_IN_SOURCE));
        var changed = drift.tables().stream().filter(t -> t.tableName().equals("orders")).findFirst().orElseThrow();
        assertThat(changed.items()).extracting("name").contains("channel");
    }

    @Test
    void comparesTwoStoredSnapshots() throws Exception {
        var first = service.capture(1L, "PUBLIC", "第一份", "tanner");
        currentSchema.add(table("refunds", column("id", "BIGINT")));
        var second = service.capture(1L, "PUBLIC", "第二份", "tanner");

        var drift = service.drift(first.snapshot().id(), second.snapshot().id(), "tanner");

        assertThat(drift.target()).isNotNull();
        assertThat(drift.targetLabel()).contains("第二份");
        assertThat(drift.summary().onlyInTarget()).isEqualTo(1);
    }

    /**
     * 单张表读不出来时快照是不完整的，必须说出来。
     *
     * <p>不说的话，下一次拿它去比会把这张表报成「被删了」—— 一个凭空捏造的漂移。</p>
     */
    @Test
    void warnsWhenATableCouldNotBeReadIntoTheSnapshot() throws Exception {
        when(metadata.detail(anyLong(), any(), eq("customers"))).thenThrow(new IllegalStateException("权限不足"));

        var response = service.capture(1L, "PUBLIC", null, "tanner");

        assertThat(response.snapshot().tableCount()).isEqualTo(1);
        assertThat(response.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("customers").contains("权限不足"));
    }

    /** cron 写错要在保存时就报出来，而不是等到某天凌晨发现从来没跑过。 */
    @Test
    void rejectsAnUnparseableCronWhenTheTargetIsSaved() {
        assertThatThrownBy(() -> service.saveTarget(
                new SchemaSnapshotTargetRequest(1L, "PUBLIC", "not a cron", null, true, 10), "tanner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cron");
    }

    /** 同一个（连接, Schema）只允许一个采集目标，重复保存就是改它。 */
    @Test
    void savingTheSameScopeTwiceUpdatesTheTargetInsteadOfDuplicatingIt() throws Exception {
        service.saveTarget(new SchemaSnapshotTargetRequest(1L, "PUBLIC", "0 0 3 * * *", "Asia/Shanghai", true, 10), "tanner");
        var updated = service.saveTarget(
                new SchemaSnapshotTargetRequest(1L, "PUBLIC", "0 0 4 * * *", "Asia/Shanghai", false, 5), "tanner");

        assertThat(service.targets(1L)).hasSize(1);
        assertThat(updated.cron()).isEqualTo("0 0 4 * * *");
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.keepSnapshots()).isEqualTo(5);
    }

    /** 保留份数是硬上限：快照存的是整份结构，不封顶会让元数据库一直长。 */
    @Test
    void prunesOldSnapshotsDownToTheConfiguredCount() throws Exception {
        for (int index = 0; index < 4; index++) {
            currentSchema.add(table("t" + index, column("id", "BIGINT")));
            service.capture(1L, "PUBLIC", null, "scheduler");
            Thread.sleep(5);
        }
        assertThat(service.timeline(1L, "PUBLIC", 50)).hasSize(4);

        repository.prune(1L, "PUBLIC", 2);

        assertThat(service.timeline(1L, "PUBLIC", 50)).hasSize(2);
    }

    /** 删任务不删已经采到的快照：那些是历史记录，删掉等于把追溯能力一起删了。 */
    @Test
    void deletingATargetKeepsTheSnapshotsItAlreadyCaptured() throws Exception {
        service.capture(1L, "PUBLIC", null, "tanner");
        var target = service.saveTarget(
                new SchemaSnapshotTargetRequest(1L, "PUBLIC", "0 0 3 * * *", null, true, 10), "tanner");

        service.deleteTarget(target.id(), "tanner");

        assertThat(service.targets(1L)).isEmpty();
        assertThat(service.timeline(1L, "PUBLIC", 50)).hasSize(1);
    }
}
