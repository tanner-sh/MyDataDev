package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.dto.ApiDtos.ObjectRelation;
import com.example.dbadmin.dto.ApiDtos.SchemaExportCandidate;
import com.example.dbadmin.dto.ApiDtos.SchemaExportCandidates;
import com.example.dbadmin.dto.ApiDtos.SchemaExportItem;
import com.example.dbadmin.dto.ApiDtos.SchemaExportObject;
import com.example.dbadmin.dto.ApiDtos.SchemaExportRequest;
import com.example.dbadmin.dto.ApiDtos.SchemaExportResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectCapability;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDependency;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectSummary;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对象定义批量导出：表、视图、序列、存储过程、函数、触发器的定义合成一份脚本，被依赖的对象在前。
 *
 * <p>没有新的取定义路径：表走 {@link MetadataService#ddl}（原生 DDL 优先），其余对象走
 * {@link SchemaObjectService#detail}（与对象编辑器显示的是同一份源码），两边都带缓存。依赖来自
 * 三处并集 —— 表的外键、数据库的依赖字典（Oracle/SQL Server/PostgreSQL）、源码里出现的本次
 * 导出范围内的对象名 —— 排序交给 {@link SchemaExportOrdering}。</p>
 *
 * <p>只读，只生成文本：脚本交给用户下载或在 SQL 工作台打开执行，生产确认与审计照旧走那条路。
 * 单个对象读失败不让整份导出失败，而是跳过并写进 warnings —— 但调用方必须看得到「少了哪些」，
 * 否则拿这份脚本去建库会在某一步报「对象不存在」，离真正的原因很远。</p>
 */
@Service
public class SchemaExportService {
    static final int MAX_OBJECTS = 500;
    static final int MAX_CANDIDATES_PER_KIND = 2_000;
    private static final int LIST_PAGE_SIZE = 500;
    private static final long TIME_BUDGET_NANOS = 120_000_000_000L;
    private static final long MAX_SCRIPT_CHARS = 30_000_000L;
    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConnectionService connections;
    private final DialectRegistry dialects;
    private final MetadataService metadata;
    private final SchemaObjectService schemaObjects;
    private final SchemaObjectCatalog catalog;
    private final AuditRepository audit;

    public SchemaExportService(ConnectionService connections, DialectRegistry dialects, MetadataService metadata,
                               SchemaObjectService schemaObjects, SchemaObjectCatalog catalog, AuditRepository audit) {
        this.connections = connections;
        this.dialects = dialects;
        this.metadata = metadata;
        this.schemaObjects = schemaObjects;
        this.catalog = catalog;
        this.audit = audit;
    }

    public SchemaExportCandidates candidates(long connectionId, String schemaName) throws Exception {
        return candidates(connectionId, schemaName, null);
    }

    /**
     * 可导出的对象清单。每类最多列 {@value #MAX_CANDIDATES_PER_KIND} 个；带 keyword 时按名称在数据库里搜
     * （与资源树的搜索同一条路），这样超出上限的对象也找得到 —— 只在已列出的那一批里筛等于看不见其余的。
     *
     * <p>kinds 是这次实际查过的类型（含查出 0 个的）：界面要据此说清「这个 Schema 没有存储过程」，
     * 而不是让一个空类型从分组里消失，看起来像不支持导出。truncatedKinds 是触顶的类型，数量显示成「2000+」。</p>
     */
    public SchemaExportCandidates candidates(long connectionId, String schemaName, String keyword) throws Exception {
        DbConnection configured = connections.require(connectionId);
        String schema = resolveSchema(connectionId, schemaName);
        String search = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Set<String> kinds = exportableKinds(configured);
        List<SchemaExportCandidate> objects = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> checked = new LinkedHashSet<>();
        Set<String> truncatedKinds = new LinkedHashSet<>();
        checked.add("TABLE");
        if (!kinds.contains(SchemaObjectKind.VIEW.name())) checked.add("VIEW");

        // 视图由方言按对象管理时从那边列，这里只列表；否则表元数据里的视图也在这里列，各自计数。
        boolean viewsFromCatalog = kinds.contains(SchemaObjectKind.VIEW.name());
        int tables = 0;
        int views = 0;
        for (int page = 0; ; page++) {
            var response = metadata.inspect(connectionId, schema, search, page, LIST_PAGE_SIZE, false);
            for (var object : response.objects()) {
                boolean view = object.type() != null && object.type().toUpperCase(Locale.ROOT).contains("VIEW");
                if (view && viewsFromCatalog) continue;
                String kind = view ? "VIEW" : "TABLE";
                if ((view ? views : tables) >= MAX_CANDIDATES_PER_KIND) {
                    truncatedKinds.add(kind);
                    continue;
                }
                objects.add(new SchemaExportCandidate(kind, object.name(), object.name(), null));
                if (view) views++; else tables++;
            }
            if (!response.hasMore()) break;
            boolean tablesFull = tables >= MAX_CANDIDATES_PER_KIND;
            boolean viewsFull = viewsFromCatalog || views >= MAX_CANDIDATES_PER_KIND;
            if (tablesFull && viewsFull) {
                // 后面还有，但已经分不清是表还是视图：满了的都按「还有更多」报。
                truncatedKinds.add("TABLE");
                if (!viewsFromCatalog) truncatedKinds.add("VIEW");
                break;
            }
        }
        for (String kind : kinds) {
            checked.add(kind);
            int count = 0;
            try {
                for (int page = 0; ; page++) {
                    var response = schemaObjects.list(connectionId, schema, kind, search, page, LIST_PAGE_SIZE, false);
                    for (SchemaObjectSummary item : response.items()) {
                        if (count >= MAX_CANDIDATES_PER_KIND) { truncatedKinds.add(kind); break; }
                        objects.add(new SchemaExportCandidate(kind, item.name(), item.displayName(), item.objectKey()));
                        count++;
                    }
                    if (!response.hasMore()) break;
                    if (count >= MAX_CANDIDATES_PER_KIND) { truncatedKinds.add(kind); break; }
                }
            } catch (Exception error) {
                warnings.add(SchemaObjectKind.parse(kind).label() + "列表读取失败：" + message(error));
            }
        }
        if (!truncatedKinds.isEmpty()) {
            warnings.add("对象较多，每类最多列出 " + MAX_CANDIDATES_PER_KIND + " 个；其余对象请在「按名称筛选」里输入名称后回车，到数据库中搜索并加入列表。");
        }
        return new SchemaExportCandidates(schema, objects, !truncatedKinds.isEmpty(), warnings, List.copyOf(checked), List.copyOf(truncatedKinds));
    }

    public SchemaExportResponse export(long connectionId, SchemaExportRequest request, String actor) throws Exception {
        DbConnection configured = connections.require(connectionId);
        DatabaseDialect dialect = dialects.dialectFor(configured);
        String family = catalog.family(configured);
        String schema = resolveSchema(connectionId, request.schemaName());
        List<SchemaExportObject> requested = distinct(request.objects());
        if (requested.isEmpty()) throw new IllegalArgumentException("请至少选择一个要导出的对象。");
        if (requested.size() > MAX_OBJECTS) throw new IllegalArgumentException("一次最多导出 " + MAX_OBJECTS + " 个对象，请分批导出。");
        boolean stripDefiner = !Boolean.FALSE.equals(request.stripDefiner()) && Set.of("mysql", "mariadb").contains(family);

        long started = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        Map<String, Loaded> loaded = new LinkedHashMap<>();
        int failed = 0;
        int skippedForTime = 0;
        long scriptChars = 0;
        for (SchemaExportObject object : requested) {
            if (System.nanoTime() - started > TIME_BUDGET_NANOS || scriptChars > MAX_SCRIPT_CHARS) {
                skippedForTime++;
                continue;
            }
            try {
                Loaded definition = load(connectionId, schema, object);
                String sql = stripDefiner ? SchemaExportScript.stripDefiner(definition.sql()) : definition.sql();
                loaded.put(key(object), new Loaded(definition.kind(), definition.name(), sql, definition.dependencies()));
                scriptChars += sql.length();
            } catch (Exception error) {
                failed++;
                warnings.add(label(object.kind()) + " " + object.name() + " 的定义读取失败，已跳过：" + message(error));
            }
        }
        if (skippedForTime > 0) {
            failed += skippedForTime;
            warnings.add("超出单次导出的时间或体积上限，还有 " + skippedForTime + " 个对象未导出，请分批导出。");
        }

        // 依赖：本次范围内的对象按名字（不分大小写）对上；同名的表/视图与例程都算。
        Map<String, List<String>> keysByName = new HashMap<>();
        for (var entry : loaded.entrySet()) {
            keysByName.computeIfAbsent(entry.getValue().name().toUpperCase(Locale.ROOT), ignored -> new ArrayList<>()).add(entry.getKey());
        }
        List<SchemaExportOrdering.Node> nodes = new ArrayList<>();
        List<SchemaExportOrdering.Edge> edges = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> selectedNames = keysByName.keySet();
        for (var entry : loaded.entrySet()) {
            Loaded definition = entry.getValue();
            nodes.add(new SchemaExportOrdering.Node(entry.getKey(), definition.kind(), definition.name()));
            Set<String> referenced = new LinkedHashSet<>(definition.dependencies());
            if (!"TABLE".equals(definition.kind())) {
                // 表之间只认外键：表定义里的名字多半是列名、约束名，拿去比对只会造出假的边。
                referenced.addAll(SchemaExportScript.referencedIdentifiers(definition.sql()));
            }
            for (String name : referenced) {
                List<String> targets = keysByName.get(name);
                if (targets == null) continue;
                for (String target : targets) {
                    if (!target.equals(entry.getKey())) edges.add(new SchemaExportOrdering.Edge(entry.getKey(), target));
                }
            }
            for (String name : definition.dependencies()) {
                if (!selectedNames.contains(name) && !name.equalsIgnoreCase(definition.name())) {
                    missing.add(definition.name() + " → " + name);
                }
            }
        }
        if (!missing.isEmpty()) {
            warnings.add("以下依赖不在本次导出范围内，目标库里需要已经存在："
                    + String.join("、", missing.stream().limit(20).toList()) + (missing.size() > 20 ? " 等 " + missing.size() + " 处" : ""));
        }

        SchemaExportOrdering.Result ordering = SchemaExportOrdering.order(nodes, edges);
        if (!ordering.cycles().isEmpty()) {
            warnings.add("存在循环依赖，环上的对象按默认类型顺序输出，执行时可能需要手工调整（例如先建表、再补外键）："
                    + String.join("；", ordering.cycles().stream().map(cycle -> String.join(" ⇄ ", cycle)).toList()));
        }

        StringBuilder script = new StringBuilder();
        for (String line : SchemaExportScript.header(configured.name(), configured.dbType(), schema, ordering.ordered().size(),
                LocalDateTime.now().format(GENERATED_AT))) {
            script.append(line).append('\n');
        }
        List<SchemaExportItem> items = new ArrayList<>();
        Map<String, String> nameByKey = new HashMap<>();
        for (var node : ordering.ordered()) nameByKey.put(node.key(), node.name());
        int order = 1;
        for (var node : ordering.ordered()) {
            Loaded definition = loaded.get(node.key());
            List<String> dependsOn = ordering.dependsOn().getOrDefault(node.key(), List.of()).stream().map(nameByKey::get).toList();
            items.add(new SchemaExportItem(order, definition.kind(), definition.name(), dependsOn));
            script.append("-- ").append(order).append(". ").append(label(definition.kind())).append(' ').append(definition.name());
            if (!dependsOn.isEmpty()) script.append("（依赖：").append(String.join(", ", dependsOn)).append('）');
            script.append('\n').append(SchemaExportScript.statement(family, definition.kind(), definition.sql())).append("\n\n");
            order++;
        }

        audit.onConnection(actor, "OBJECT_DDL_EXPORT", connectionId, "schema:" + schema,
                "objects=" + items.size() + " failed=" + failed + " cycles=" + ordering.cycles().size()
                        + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
        return new SchemaExportResponse(schema, script.toString(), items, ordering.cycles(), warnings, failed);
    }

    private Loaded load(long connectionId, String schema, SchemaExportObject object) throws Exception {
        String kind = object.kind().trim().toUpperCase(Locale.ROOT);
        if (object.objectKey() == null || object.objectKey().isBlank()) {
            if (!kind.equals("TABLE") && !kind.equals("VIEW")) {
                throw new IllegalArgumentException("缺少对象标识，请刷新对象列表后重试。");
            }
            String ddl = metadata.ddl(connectionId, schema, object.name(), false).ddl();
            List<String> dependencies = new ArrayList<>();
            if (kind.equals("TABLE")) {
                for (ObjectRelation relation : metadata.relations(connectionId, schema, object.name()).importedKeys()) {
                    // 外键指向别的 Schema 的表时不算本次范围内的依赖，也不报缺失：那张表不归这份脚本管。
                    if (relation.pkSchemaName() == null || schema == null || relation.pkSchemaName().equalsIgnoreCase(schema)) {
                        dependencies.add(relation.pkTableName().toUpperCase(Locale.ROOT));
                    }
                }
            }
            return new Loaded(kind, object.name(), requireText(ddl), List.copyOf(new LinkedHashSet<>(dependencies)));
        }
        SchemaObjectDetail detail = schemaObjects.detail(connectionId, object.objectKey(), false);
        if (!detail.sourceAvailable() || detail.source() == null || detail.source().isBlank()) {
            throw new IllegalStateException(detail.sourceUnavailableReason() == null ? "数据库未返回定义。" : detail.sourceUnavailableReason());
        }
        List<String> dependencies = new ArrayList<>();
        for (SchemaObjectDependency dependency : detail.dependencies()) {
            if (dependency.name() == null || dependency.name().isBlank()) continue;
            boolean sameSchema = dependency.schemaName() == null || dependency.schemaName().isBlank()
                    || schema == null || dependency.schemaName().equalsIgnoreCase(schema);
            if (sameSchema) dependencies.add(dependency.name().toUpperCase(Locale.ROOT));
        }
        return new Loaded(detail.object().kind(), detail.object().name(), requireText(detail.source()),
                List.copyOf(new LinkedHashSet<>(dependencies)));
    }

    private Set<String> exportableKinds(DbConnection configured) {
        Set<String> kinds = new LinkedHashSet<>();
        for (SchemaObjectCapability capability : dialects.dialectFor(configured).capabilities().schemaObjects()) {
            if (capability.operations().contains("DETAIL") && capability.operations().contains("LIST")) kinds.add(capability.kind());
        }
        return kinds;
    }

    private String resolveSchema(long connectionId, String requested) throws Exception {
        String resolved = metadata.inspect(connectionId, requested == null || requested.isBlank() ? null : requested.trim(), null, 0, 1, false).selectedSchema();
        return resolved == null ? (requested == null ? "" : requested.trim()) : resolved;
    }

    private static List<SchemaExportObject> distinct(List<SchemaExportObject> objects) {
        Map<String, SchemaExportObject> unique = new LinkedHashMap<>();
        for (SchemaExportObject object : objects == null ? List.<SchemaExportObject>of() : objects) {
            if (object == null || object.name() == null || object.name().isBlank() || object.kind() == null) continue;
            unique.putIfAbsent(key(object), object);
        }
        return List.copyOf(unique.values());
    }

    private static String key(SchemaExportObject object) {
        return object.kind().trim().toUpperCase(Locale.ROOT) + "\u0000" + object.name() + "\u0000"
                + (object.objectKey() == null ? "" : object.objectKey());
    }

    private static String requireText(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalStateException("数据库未返回定义。");
        return sql;
    }

    static String label(String kind) {
        if ("TABLE".equalsIgnoreCase(kind)) return "表";
        try {
            return SchemaObjectKind.parse(kind).label();
        } catch (IllegalArgumentException ignored) {
            return kind;
        }
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) + "…" : message;
    }

    private record Loaded(String kind, String name, String sql, List<String> dependencies) {
    }
}
