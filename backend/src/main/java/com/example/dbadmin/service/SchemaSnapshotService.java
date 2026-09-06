package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffItem;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffSummary;
import com.example.dbadmin.dto.ApiDtos.SchemaDriftAudit;
import com.example.dbadmin.dto.ApiDtos.SchemaDriftResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaDriftTable;
import com.example.dbadmin.dto.ApiDtos.SchemaSnapshotCaptureResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaSnapshotSummary;
import com.example.dbadmin.dto.ApiDtos.SchemaSnapshotTargetRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.SchemaSnapshot;
import com.example.dbadmin.model.SchemaSnapshotTarget;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SchemaSnapshotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 结构快照与漂移追踪。
 *
 * <p>「结构对比」回答的是「这两个库现在一不一样」，回答不了「这个字段是什么时候加的」。生产库
 * 上后一个问题往往更急：出了事要先知道结构是不是刚被人动过。</p>
 *
 * <p>没有新造对比逻辑 —— 快照存的就是一份 {@code ObjectDetail} 列表，漂移把两份反序列化后交给
 * {@link SchemaComparison#compare}，与结构对比同一套判定。</p>
 *
 * <p>刻意不生成「改回去」的 DDL。快照是一份记录，不是一份要被回放的期望状态：一个字段可能是
 * 上周有人有意加的，照着快照把它删掉才是真正的事故。要脚本就去结构对比，拿一个真正的基准
 * 环境作源端 —— 那条路上生产确认与审计一个都不少。</p>
 */
@Service
public class SchemaSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotService.class);

    /** 与结构对比同一个上限：每张表都要读一次结构，表太多会把采集拖成一次长操作。 */
    static final int MAX_TABLES = SchemaDiffService.MAX_TABLES;
    static final int DEFAULT_KEEP_SNAPSHOTS = 30;
    static final int MAX_KEEP_SNAPSHOTS = 365;
    static final int MAX_TIMELINE = 100;
    /** 漂移窗口内最多回捞多少条审计。它只是线索，不是全量证据。 */
    private static final int MAX_AUDIT_EVENTS = 50;
    private static final int LIST_PAGE_SIZE = 500;
    private static final int MAX_LIST_PAGES = 20;
    /** 只有这些动作码算「结构变更」。审计里绝大多数条目与结构无关，混进来只会淹没线索。 */
    private static final Set<String> DDL_ACTIONS = Set.of(
            "OBJECT_CREATE", "OBJECT_REPLACE", "OBJECT_DROP", "OBJECT_ENABLE", "OBJECT_DISABLE",
            "TABLE_DESIGN", "TABLE_CREATE", "TABLE_ALTER", "TABLE_DROP", "TABLE_TRUNCATE",
            "TABLE_RENAME", "SQL_FILE_START", "SCHEMA_DIFF");

    private final ConnectionService connections;
    private final MetadataService metadata;
    private final SchemaSnapshotRepository repository;
    private final AuditRepository audit;
    private final ObjectMapper mapper;

    public SchemaSnapshotService(
            ConnectionService connections,
            MetadataService metadata,
            SchemaSnapshotRepository repository,
            AuditRepository audit,
            ObjectMapper mapper
    ) {
        this.connections = connections;
        this.metadata = metadata;
        this.repository = repository;
        this.audit = audit;
        this.mapper = mapper;
    }

    /**
     * 采一份快照。
     *
     * <p>结构与上一份完全一致时不新增行，只推进 {@code last_seen_at}：时间线上留下的应该是
     * 「结构变了」的时刻，而不是「我们检查过」的时刻。两者混在一起，看时间线的人分不出哪次
     * 才是真的有变化 —— 而那正是这个功能唯一要回答的问题。</p>
     */
    public SchemaSnapshotCaptureResponse capture(long connectionId, String schemaName, String label, String actor)
            throws Exception {
        connections.require(connectionId);
        String schema = resolveSchema(connectionId, schemaName);
        List<String> warnings = new ArrayList<>();
        List<String> tables = listTables(connectionId, schema);
        if (tables.size() > MAX_TABLES) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "SCHEMA_SNAPSHOT_TOO_MANY_TABLES",
                    "这个 Schema 有 " + tables.size() + " 张表，超过单次采集上限 " + MAX_TABLES + " 张。");
        }

        List<ObjectDetail> details = new ArrayList<>();
        for (String table : tables) {
            try {
                details.add(normalize(metadata.detail(connectionId, schema, table)));
            } catch (Exception error) {
                // 单张表读不出来（权限、并发 DDL）不该让整次采集失败；但少了一张表的快照是不
                // 完整的，之后拿它去比会把这张表报成「被删了」，所以必须写进 warnings。
                log.debug("采集表结构失败：连接 {} 的 {}.{}", connectionId, schema, table, error);
                warnings.add("无法读取表 " + table + " 的结构，本次快照不含它：" + rootMessage(error));
            }
        }
        details.sort((left, right) -> fold(left.name()).compareTo(fold(right.name())));

        String content = mapper.writeValueAsString(details);
        String checksum = sha256(content);
        Instant now = Instant.now();
        var latest = repository.findLatest(connectionId, schema);
        if (latest.isPresent() && latest.get().checksumSha256().equals(checksum)) {
            repository.touch(latest.get().id(), now);
            audit.onConnection(actor, "SCHEMA_SNAPSHOT_UNCHANGED", connectionId,
                    "schema:" + schema, "tables=" + details.size());
            return new SchemaSnapshotCaptureResponse(
                    summary(latest.get().withLastSeenAt(now)), false, List.copyOf(warnings));
        }

        long id = repository.insert(new SchemaSnapshot(0, connectionId, schema, trim(label, 200),
                details.size(), checksum, content, actor, now, now));
        audit.onConnection(actor, "SCHEMA_SNAPSHOT_CAPTURE", connectionId, "schema:" + schema,
                "snapshot=" + id + "; tables=" + details.size());
        SchemaSnapshot stored = repository.findById(id).orElseThrow();
        return new SchemaSnapshotCaptureResponse(summary(stored), true, List.copyOf(warnings));
    }

    public List<SchemaSnapshotSummary> timeline(long connectionId, String schemaName, Integer limit) throws Exception {
        String schema = resolveSchema(connectionId, schemaName);
        int cap = Math.min(Math.max(limit == null ? 30 : limit, 1), MAX_TIMELINE);
        return repository.findSummaries(connectionId, schema, cap).stream().map(SchemaSnapshotService::summary).toList();
    }

    /**
     * 两个时间点之间的结构漂移。
     *
     * @param targetSnapshotId 终点快照；传 null 表示与目标库**当前**的结构比 —— 「上周到现在有
     *                         没有人动过结构」是这个功能最常被问的一句
     */
    public SchemaDriftResponse drift(long baselineSnapshotId, Long targetSnapshotId, String actor) throws Exception {
        SchemaSnapshot baseline = repository.findById(baselineSnapshotId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "SCHEMA_SNAPSHOT_NOT_FOUND", "快照不存在或已被清理。"));
        connections.require(baseline.connectionId());

        List<String> warnings = new ArrayList<>();
        List<ObjectDetail> before = read(baseline);
        List<ObjectDetail> after;
        SchemaSnapshotSummary targetSummary = null;
        String targetLabel;
        Instant windowEnd;
        if (targetSnapshotId == null) {
            after = live(baseline.connectionId(), baseline.schemaName(), warnings);
            targetLabel = "当前结构";
            windowEnd = Instant.now();
        } else {
            SchemaSnapshot target = repository.findById(targetSnapshotId)
                    .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "SCHEMA_SNAPSHOT_NOT_FOUND", "快照不存在或已被清理。"));
            if (target.connectionId() != baseline.connectionId() || !target.schemaName().equals(baseline.schemaName())) {
                throw new IllegalArgumentException("两份快照不属于同一个连接的同一个 Schema，无法作为同一条时间线比较。");
            }
            after = read(target);
            targetSummary = summary(target);
            targetLabel = describe(target);
            windowEnd = target.capturedAt();
        }

        Map<String, ObjectDetail> beforeByName = index(before);
        Map<String, ObjectDetail> afterByName = index(after);
        List<SchemaDriftTable> tables = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int changed = 0;
        int identical = 0;
        for (String key : union(beforeByName.keySet(), afterByName.keySet())) {
            ObjectDetail from = beforeByName.get(key);
            ObjectDetail to = afterByName.get(key);
            if (from == null) {
                added++;
                tables.add(new SchemaDriftTable(to.name(), SchemaComparison.STATUS_ONLY_IN_TARGET, List.of()));
            } else if (to == null) {
                removed++;
                tables.add(new SchemaDriftTable(from.name(), SchemaComparison.STATUS_ONLY_IN_SOURCE, List.of()));
            } else {
                List<SchemaDiffItem> items = SchemaComparison.compare(from, to);
                if (items.isEmpty()) {
                    identical++;
                } else {
                    changed++;
                    tables.add(new SchemaDriftTable(to.name(), SchemaComparison.STATUS_DIFFERENT, items));
                }
            }
        }

        audit.onConnection(actor, "SCHEMA_DRIFT", baseline.connectionId(), "schema:" + baseline.schemaName(),
                "baseline=" + baseline.id() + "; target=" + (targetSnapshotId == null ? "live" : targetSnapshotId));
        return new SchemaDriftResponse(
                summary(baseline),
                targetSummary,
                targetLabel,
                // 方向按时间读：新增的表在「后」那一侧，所以它落在 ONLY_IN_TARGET 上。
                new SchemaDiffSummary(removed, added, changed, identical),
                List.copyOf(tables),
                driftAudit(baseline.connectionId(), baseline.capturedAt(), windowEnd),
                List.copyOf(warnings)
        );
    }

    /**
     * 这段时间里本工具在这条连接上做过的结构变更。
     *
     * <p>它是线索不是证据：审计只记得到经由本工具的操作，而结构漂移多半来自别的客户端或发布
     * 流水线。空列表的正确读法是「不是从这里改的」，不是「没人改过」—— 这句话必须由界面说出来。</p>
     */
    private List<SchemaDriftAudit> driftAudit(long connectionId, Instant from, Instant to) {
        if (from == null || to == null) return List.of();
        try {
            var page = audit.findPage(new AuditRepository.AuditQuery(
                    null, null, connectionId, null, from, to, 0, MAX_AUDIT_EVENTS * 4));
            List<SchemaDriftAudit> events = new ArrayList<>();
            for (var event : page.items()) {
                if (!DDL_ACTIONS.contains(event.action())) continue;
                if (events.size() >= MAX_AUDIT_EVENTS) break;
                events.add(new SchemaDriftAudit(
                        event.createdAt(), event.actor(), event.action(), event.target(), event.detail()));
            }
            return List.copyOf(events);
        } catch (Exception error) {
            log.debug("读取漂移窗口内的审计失败", error);
            return List.of();
        }
    }

    /**
     * 一份快照属于哪条连接。
     *
     * <p>单独一条只查归属的入口，是为了让控制器能在真正开始比较之前先鉴权 —— 先干活再检查
     * 权限，等于让没有权限的人照样触发一次全 Schema 的元数据读取和一条审计。</p>
     */
    public long connectionIdOf(long snapshotId) {
        return repository.findById(snapshotId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "SCHEMA_SNAPSHOT_NOT_FOUND", "快照不存在或已被清理。"))
                .connectionId();
    }

    public List<SchemaSnapshotTarget> targets() {
        return repository.findAllTargets();
    }

    public List<SchemaSnapshotTarget> targets(long connectionId) {
        return repository.findAllTargets().stream().filter(target -> target.connectionId() == connectionId).toList();
    }

    public SchemaSnapshotTarget saveTarget(SchemaSnapshotTargetRequest request, String actor) throws Exception {
        connections.require(request.connectionId());
        String schema = resolveSchema(request.connectionId(), request.schemaName());
        // cron 写错就在保存时报出来，而不是等到某天凌晨发现从来没跑过。
        try {
            CronExpression.parse(request.cron().trim());
        } catch (Exception error) {
            throw new IllegalArgumentException("cron 表达式无法解析：" + request.cron());
        }
        int keep = Math.min(Math.max(request.keepSnapshots() == null ? DEFAULT_KEEP_SNAPSHOTS : request.keepSnapshots(), 1),
                MAX_KEEP_SNAPSHOTS);
        repository.upsertTarget(new SchemaSnapshotTarget(0, request.connectionId(), schema, request.cron().trim(),
                trim(request.scheduleZone(), 64), request.enabled(), keep, null, null, null, null, null));
        audit.onConnection(actor, "SCHEMA_SNAPSHOT_TARGET_SAVE", request.connectionId(), "schema:" + schema,
                "cron=" + request.cron() + "; keep=" + keep + "; enabled=" + request.enabled());
        return repository.findTarget(request.connectionId(), schema).orElseThrow();
    }

    /** 采集目标属于哪条连接；同样是为了让控制器先鉴权。 */
    public long targetConnectionId(long id) {
        return repository.findTargetById(id)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "SCHEMA_SNAPSHOT_TARGET_NOT_FOUND", "采集目标不存在。"))
                .connectionId();
    }

    public void deleteTarget(long id, String actor) {
        SchemaSnapshotTarget target = repository.findTargetById(id)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "SCHEMA_SNAPSHOT_TARGET_NOT_FOUND", "采集目标不存在。"));
        repository.deleteTarget(id);
        // 只删任务，不删已经采到的快照：那些是历史记录，删掉等于把追溯能力一起删了。
        audit.onConnection(actor, "SCHEMA_SNAPSHOT_TARGET_DELETE", target.connectionId(),
                "schema:" + target.schemaName(), "target=" + id);
    }

    /** 调度线程的入口：采一份，然后按保留份数清理。 */
    public void runTarget(SchemaSnapshotTarget target) {
        Instant now = Instant.now();
        try {
            SchemaSnapshotCaptureResponse response = capture(target.connectionId(), target.schemaName(), "定时采集", "scheduler");
            repository.prune(target.connectionId(), target.schemaName(), target.keepSnapshots());
            repository.markTargetRun(target.id(), now, "SUCCESS",
                    response.changed() ? "结构有变化，已新建快照。" : "结构与上一份一致，未新建快照。");
        } catch (Exception error) {
            repository.markTargetRun(target.id(), now, "FAILED", rootMessage(error));
            log.warn("定时结构快照失败 target={}", target.id(), error);
        }
    }

    private List<ObjectDetail> live(long connectionId, String schemaName, List<String> warnings) throws Exception {
        List<ObjectDetail> details = new ArrayList<>();
        for (String table : listTables(connectionId, schemaName)) {
            try {
                details.add(normalize(metadata.detail(connectionId, schemaName, table)));
            } catch (Exception error) {
                warnings.add("无法读取表 " + table + " 的当前结构，已跳过：" + rootMessage(error));
            }
        }
        return details;
    }

    private List<ObjectDetail> read(SchemaSnapshot snapshot) throws Exception {
        SchemaSnapshot full = snapshot.content() != null ? snapshot
                : repository.findById(snapshot.id()).orElseThrow();
        return mapper.readValue(full.content(), new TypeReference<List<ObjectDetail>>() { });
    }

    /**
     * 只留结构相关的字段。
     *
     * <p>{@code structureVersion} 是一次读取的指纹，每次读都可能不同 —— 原样存进快照会让两份
     * 完全相同的结构算出不同的校验和，于是时间线上每次采集都多出一条「变了」。</p>
     */
    private static ObjectDetail normalize(ObjectDetail detail) {
        return new ObjectDetail(detail.schemaName(), detail.name(), detail.type(), detail.columns(),
                detail.indexes(), detail.primaryKeys(), detail.primaryKeyName(), null);
    }

    private List<String> listTables(long connectionId, String schemaName) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            var response = metadata.inspect(connectionId, schemaName, null, page, LIST_PAGE_SIZE, false);
            for (var object : response.objects()) {
                if (object.type() != null && object.type().toUpperCase(Locale.ROOT).contains("VIEW")) continue;
                names.add(object.name());
            }
            if (!response.hasMore()) break;
        }
        return List.copyOf(names);
    }

    private String resolveSchema(long connectionId, String requested) throws Exception {
        String resolved = metadata.inspect(connectionId, requested, null, 0, 1, false).selectedSchema();
        if (resolved == null || resolved.isBlank()) throw new IllegalArgumentException("无法确定要采集的 Schema。");
        return resolved;
    }

    private static SchemaSnapshotSummary summary(SchemaSnapshot snapshot) {
        return new SchemaSnapshotSummary(snapshot.id(), snapshot.connectionId(), snapshot.schemaName(),
                snapshot.label(), snapshot.tableCount(), snapshot.checksumSha256(), snapshot.capturedBy(),
                String.valueOf(snapshot.capturedAt()), String.valueOf(snapshot.lastSeenAt()));
    }

    private static String describe(SchemaSnapshot snapshot) {
        return (snapshot.label() == null || snapshot.label().isBlank() ? "快照 " + snapshot.id() : snapshot.label())
                + " · " + snapshot.capturedAt();
    }

    private static Map<String, ObjectDetail> index(List<ObjectDetail> details) {
        Map<String, ObjectDetail> byName = new LinkedHashMap<>();
        for (ObjectDetail detail : details) byName.putIfAbsent(fold(detail.name()), detail);
        return byName;
    }

    private static List<String> union(Set<String> left, Set<String> right) {
        Set<String> all = new LinkedHashSet<>(left);
        all.addAll(right);
        return List.copyOf(all);
    }

    private static String fold(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String single = value.strip();
        return single.length() > max ? single.substring(0, max) : single;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getMessage() == null && current.getCause() != null) current = current.getCause();
        String message = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
        String single = message.replaceAll("\\R", " ");
        return single.length() > 300 ? single.substring(0, 300) + "…" : single;
    }
}
