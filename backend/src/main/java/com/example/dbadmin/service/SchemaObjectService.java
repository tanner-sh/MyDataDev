package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.core.SchemaObjectOperation;
import com.example.dbadmin.core.SchemaObjectSql;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectCapability;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDependency;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectLifecycleRequest;
import com.example.dbadmin.dto.ApiDtos.CompilationError;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectLifecycleResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectPage;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectParameter;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectSummary;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectTemplateResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SchemaObjectService {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    private final ConnectionService connections;

    private final DialectRegistry dialectRegistry;

    private final SchemaObjectCatalog catalog;

    private final MetadataCacheService cache;

    private final ExecutionGuard executionGuard;

    private final AuditRepository audit;

    private final SqlHistoryRepository history;

    private final AppProperties properties;

    private final SchemaObjectDefinitions definitions;

    private final Object[] catalogLoadLocks = catalogLoadLocks();

    public SchemaObjectService(
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            SchemaObjectCatalog catalog,
            MetadataCacheService cache,
            ExecutionGuard executionGuard,
            AuditRepository audit,
            SqlHistoryRepository history,
            AppProperties properties,
            SqlScriptSplitter scriptSplitter
    ) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.catalog = catalog;
        this.cache = cache;
        this.executionGuard = executionGuard;
        this.audit = audit;
        this.history = history;
        this.properties = properties;
        this.definitions = new SchemaObjectDefinitions(scriptSplitter);
    }

    public SchemaObjectPage list(long connectionId, String schemaName, String kindValue, String keyword, Integer page, Integer pageSize, boolean refresh) throws Exception {
        SchemaObjectKind kind = SchemaObjectKind.parse(kindValue);
        DbConnection configured = connections.require(connectionId);
        requireCapability(configured, kind, SchemaObjectOperation.LIST);
        String schema = normalizeObjectSchema(configured, kind, schemaName);
        String search = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (search.length() > 200) throw new IllegalArgumentException("搜索关键字最多 200 个字符。");
        int normalizedPage = Math.max(page == null ? 0 : page, 0);
        int normalizedPageSize = Math.min(Math.max(pageSize == null ? 100 : pageSize, 1), 500);
        SchemaObjectPageLoad pageLoad = loadCatalogPage(
                connectionId, configured, schema, kind, search, normalizedPage, normalizedPageSize, refresh
        );
        MetadataCacheService.SchemaObjectPageValue pageValue = pageLoad.value().value();
        return new SchemaObjectPage(
                pageValue.items(), pageValue.total(), pageValue.totalExact(), normalizedPage, normalizedPageSize,
                pageValue.hasMore(), pageLoad.value().cachedAt().toString(), pageLoad.cacheHit()
        );
    }

    public SchemaObjectDetail detail(long connectionId, String objectKey, boolean refresh) throws Exception {
        if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("对象标识不能为空。");
        if (!refresh) {
            var cached = cache.schemaObjectDetail(connectionId, objectKey);
            if (cached.isPresent()) return cached.get().value();
        } else {
            // A plain refresh of one object's detail is a read; it must not
            // invalidate every other schema-object page listing and every
            // classic table/DDL cache entry for the connection the way
            // evictConnection would. DDL execution and routine invocation
            // still call evictConnection in their own finally blocks below,
            // since those really can change database-wide state.
            cache.evictSchemaObjectDetail(connectionId, objectKey);
        }
        DbConnection configured = connections.require(connectionId);
        ObjectRef reference = decodeKey(objectKey);
        requireCapability(configured, reference.kind(), SchemaObjectOperation.DETAIL);
        try (Connection connection = connections.open(connectionId)) {
            ResolvedObject resolved = resolve(connection, configured, reference);
            SchemaObjectDetail detail = loadDetail(connection, configured, resolved);
            cache.putSchemaObjectDetail(connectionId, objectKey, detail);
            return detail;
        }
    }

    /**
     * 一个已存在对象的编译错误（Oracle 系 INVALID 对象）。方言不需要这一步时返回空列表。
     * 给 MCP 用：Agent 部署完过程后最常问的就是「哪些坏了、坏在哪」。
     */
    public List<CompilationError> compilationErrors(long connectionId, SchemaObjectSummary object) throws Exception {
        DbConnection configured = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(configured);
        if (dialect.compilationErrorsSql() == null) return List.of();
        try (Connection connection = connections.open(connectionId)) {
            return CompilationDiagnostics.query(connection, dialect, object.schemaName(), object.name(),
                    object.kind().replace('_', ' '), properties.getSql().getTimeoutSeconds());
        }
    }

    public SchemaObjectTemplateResponse template(long connectionId, String kindValue, String schemaName, String objectName) {
        SchemaObjectKind kind = SchemaObjectKind.parse(kindValue);
        DbConnection configured = connections.require(connectionId);
        requireCapability(configured, kind, SchemaObjectOperation.CREATE);
        validateIdentifier(objectName, "对象名");
        String schema = normalizeObjectSchema(configured, kind, schemaName);
        if (!schema.isBlank()) validateIdentifier(schema, "Schema/数据库名");
        DatabaseDialect dialect = dialectRegistry.dialectFor(configured);
        return new SchemaObjectTemplateResponse(kind.name(), schema, objectName, catalog.template(dialect, kind, schema, objectName));
    }

    public SchemaObjectLifecycleResponse preview(long connectionId, SchemaObjectLifecycleRequest request) throws Exception {
        LifecyclePlan plan = lifecyclePlan(connectionId, request);
        return new SchemaObjectLifecycleResponse(List.of(plan.sql()), "已生成 1 条对象操作 SQL。");
    }

    public SchemaObjectLifecycleResponse execute(
            long connectionId,
            SchemaObjectLifecycleRequest request,
            String actor,
            String productionConfirmation
    ) throws Exception {
        DbConnection configured = connections.require(connectionId);
        executionGuard.requireMutationAllowed(configured, productionConfirmation);
        LifecyclePlan plan = lifecyclePlan(connectionId, request);
        if (!plan.confirmationTarget().equals(request.confirmation())) {
            throw new IllegalArgumentException("确认文本不匹配，请输入完整对象名：" + plan.confirmationTarget());
        }
        long started = System.nanoTime();
        try (Connection connection = connections.open(connectionId)) {
            if (!plan.schemaName().isBlank() && objectSql(configured).definitionsResolveInCurrentNamespace()) {
                connection.setCatalog(plan.schemaName());
            }
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(Math.max(properties.getSql().getTimeoutSeconds(), 1));
                statement.execute(plan.sql());
            }
            // 语句没抛异常不等于对象是好的：Oracle 会「带编译错误创建成功」。
            List<CompilationError> compilationErrors =
                    readCompilationErrors(connection, dialectRegistry.dialectFor(configured), plan);
            audit.onConnection(actor, "OBJECT_" + plan.operation().name(), connectionId, "object:" + plan.confirmationTarget(),
                    compilationErrors.isEmpty() ? plan.sql() : "编译错误 " + compilationErrors.size() + " 条; " + plan.sql());
            history.insert(connectionId, plan.sql(), "OBJECT_" + plan.operation().name(),
                    compilationErrors.isEmpty() ? "SUCCESS" : "FAILED", elapsed(started),
                    compilationErrors.isEmpty() ? null : firstCompilationError(compilationErrors), actor);
            return new SchemaObjectLifecycleResponse(
                    List.of(plan.sql()),
                    compilationErrors.isEmpty()
                            ? operationMessage(plan.operation(), plan.kind())
                            : "对象已创建，但编译未通过，当前处于不可用状态。请按下面的错误位置修改后重新提交。",
                    compilationErrors);
        } catch (Exception exception) {
            String error = error(exception);
            audit.onConnection(actor, "OBJECT_" + plan.operation().name() + "_FAILED", connectionId, "object:" + plan.confirmationTarget(), error);
            history.insert(connectionId, plan.sql(), "OBJECT_" + plan.operation().name(), "FAILED", elapsed(started), error, actor);
            throw exception;
        } finally {
            // Remote DDL may auto-commit before a later error. All metadata is
            // stale after every attempted object operation.
            cache.evictConnection(connectionId);
        }
    }

    private LifecyclePlan lifecyclePlan(long connectionId, SchemaObjectLifecycleRequest request) throws Exception {
        DbConnection configured = connections.require(connectionId);
        SchemaObjectKind kind = SchemaObjectKind.parse(request.kind());
        SchemaObjectOperation operation = SchemaObjectOperation.parse(request.operation());
        if (!Set.of(SchemaObjectOperation.CREATE, SchemaObjectOperation.REPLACE, SchemaObjectOperation.DROP,
                SchemaObjectOperation.REFRESH, SchemaObjectOperation.ENABLE, SchemaObjectOperation.DISABLE).contains(operation)) {
            throw new IllegalArgumentException("该接口不支持操作：" + operation);
        }
        requireCapability(configured, kind, operation);
        validateIdentifier(request.objectName(), "对象名");
        String schema = normalizeObjectSchema(configured, kind, request.schemaName());
        if (!schema.isBlank()) validateIdentifier(schema, "Schema/数据库名");
        DatabaseDialect dialect = dialectRegistry.dialectFor(configured);

        if (operation == SchemaObjectOperation.CREATE) {
            if (request.objectKey() != null && !request.objectKey().isBlank()) throw new IllegalArgumentException("新建对象不能携带已有对象标识。");
            String source = definitions.requireSource(request.source(), operation, kind, schema, request.objectName(), objectSql(configured));
            try (Connection connection = connections.open(connectionId)) {
                boolean exists = catalog.existsByName(
                        connection, dialect, schema, kind, request.objectName(),
                        Math.max(properties.getSql().getTimeoutSeconds(), 1)
                );
                if (exists) throw new ApiProblemException(HttpStatus.CONFLICT, "SCHEMA_OBJECT_ALREADY_EXISTS", "目标对象已存在：" + qualifiedName(schema, request.objectName()));
            }
            return new LifecyclePlan(operation, kind, schema, request.objectName(), source, qualifiedName(schema, request.objectName()));
        }

        if (request.objectKey() == null || request.objectKey().isBlank()) throw new IllegalArgumentException("对象操作缺少对象标识。");
        ObjectRef reference = decodeKey(request.objectKey());
        if (reference.kind() != kind) throw new IllegalArgumentException("对象类型与对象标识不匹配。");
        if (!reference.schemaName().equals(schema)) throw new IllegalArgumentException("命名空间与对象标识不匹配。");
        try (Connection connection = connections.open(connectionId)) {
            ResolvedObject resolved = resolve(connection, configured, reference);
            SchemaObjectDetail live = loadDetail(connection, configured, resolved);
            requireFresh(request.structureVersion(), live.structureVersion());
            if (!resolved.object().name().equals(request.objectName())) throw new IllegalArgumentException("对象名称与对象标识不匹配。");
            String sql = switch (operation) {
                case REPLACE -> definitions.requireSource(request.source(), operation, kind, reference.schemaName(), resolved.object().name(), objectSql(configured));
                case DROP -> catalog.dropSql(dialect, kind, resolved.catalogObject());
                case REFRESH, ENABLE, DISABLE -> catalog.specialOperationSql(dialect, operation, kind, resolved.catalogObject());
                default -> throw new IllegalArgumentException("不支持的对象操作。");
            };
            return new LifecyclePlan(operation, kind, reference.schemaName(), resolved.object().name(), sql, confirmationTarget(live.object()));
        }
    }

    SchemaObjectDetail loadDetail(Connection connection, DbConnection configured, ResolvedObject resolved) throws Exception {
        DatabaseDialect dialect = dialectRegistry.dialectFor(configured);
        SchemaObjectKind kind = resolved.reference().kind();
        String loadedSource = null;
        String sourceError = null;
        try {
            loadedSource = catalog.source(connection, dialect, kind, resolved.catalogObject());
        } catch (Exception error) {
            sourceError = error(error);
        }
        List<SchemaObjectParameter> parameters = List.of();
        String parameterError = null;
        try {
            parameters = catalog.parameters(connection, dialect, kind, resolved.catalogObject());
        } catch (Exception error) {
            parameterError = error(error);
        }
        List<SchemaObjectDependency> dependencies = List.of();
        boolean dependenciesAvailable = hasCapability(configured, kind, SchemaObjectOperation.DEPENDENCIES);
        String dependencyError = dependenciesAvailable ? null : "当前数据库方言没有可靠的依赖元数据。";
        if (dependenciesAvailable) {
            try {
                dependencies = catalog.dependencies(connection, dialect, kind, resolved.catalogObject());
            } catch (Exception error) {
                dependenciesAvailable = false;
                dependencyError = error(error);
            }
        }
        String source = loadedSource == null ? null : atomicReplaceSource(configured, kind, loadedSource);
        String parameterMetadataError = parameterError;
        List<String> operations = capability(configured, kind).operations().stream()
                .filter(operation -> source != null || !operation.equals(SchemaObjectOperation.REPLACE.name()))
                .filter(operation -> parameterMetadataError == null || !operation.equals(SchemaObjectOperation.INVOKE.name()))
                .filter(operation -> !operation.equals(SchemaObjectOperation.ENABLE.name()) || "DISABLED".equalsIgnoreCase(resolved.object().status()))
                .filter(operation -> !operation.equals(SchemaObjectOperation.DISABLE.name()) || !"DISABLED".equalsIgnoreCase(resolved.object().status()))
                .filter(operation -> !operation.equals(SchemaObjectOperation.REFRESH.name())
                        || !objectSql(configured).refreshRequiresRefreshClause()
                        || source != null && source.toUpperCase(Locale.ROOT).contains("REFRESH"))
                .toList();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subtype", value(resolved.object().subtype()));
        properties.put("status", value(resolved.object().status()));
        if (parameterError != null) properties.put("parameterMetadataWarning", parameterError);
        String version = structureVersion(resolved.object(), kind == SchemaObjectKind.SEQUENCE ? null : source, parameters);
        return new SchemaObjectDetail(
                resolved.object(), source, source != null, source == null ? sourceError : null,
                parameters, dependencies, dependenciesAvailable, dependencyError, version, operations, properties
        );
    }

    private SchemaObjectPageLoad loadCatalogPage(
            long connectionId,
            DbConnection configured,
            String schema,
            SchemaObjectKind kind,
            String keyword,
            int page,
            int pageSize,
            boolean refresh
    ) throws Exception {
        if (!refresh) {
            var cached = cache.schemaObjectPage(connectionId, schema, kind.name(), keyword, page, pageSize);
            if (cached.isPresent()) return new SchemaObjectPageLoad(cached.get(), true);
        }

        synchronized (catalogLoadLock(connectionId)) {
            if (refresh) {
                cache.evictSchemaObjectPages(connectionId, schema, kind.name());
            } else {
                var cached = cache.schemaObjectPage(connectionId, schema, kind.name(), keyword, page, pageSize);
                if (cached.isPresent()) return new SchemaObjectPageLoad(cached.get(), true);
            }
            DatabaseDialect dialect = dialectRegistry.dialectFor(configured);
            try (Connection connection = connections.open(connectionId)) {
                SchemaObjectCatalog.CatalogPage loaded = catalog.page(
                        connection, dialect, schema, kind, keyword, page, pageSize,
                        Math.max(properties.getSql().getTimeoutSeconds(), 1)
                );
                MetadataCacheService.SchemaObjectPageValue pageValue = new MetadataCacheService.SchemaObjectPageValue(
                        loaded.items().stream().map(object -> summary(kind, object)).toList(),
                        loaded.total(), loaded.totalExact(), loaded.hasMore()
                );
                return new SchemaObjectPageLoad(
                        cache.putSchemaObjectPage(connectionId, schema, kind.name(), keyword, page, pageSize, pageValue),
                        false
                );
            }
        }
    }

    private Object catalogLoadLock(long connectionId) {
        return catalogLoadLocks[Math.floorMod(Long.hashCode(connectionId), catalogLoadLocks.length)];
    }

    private static Object[] catalogLoadLocks() {
        Object[] locks = new Object[64];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    ResolvedObject resolve(Connection connection, DbConnection configured, ObjectRef reference) throws Exception {
        List<SchemaObjectCatalog.CatalogObject> matches = catalog.find(
                connection, dialectRegistry.dialectFor(configured), reference.schemaName(), reference.kind(),
                reference.name(), reference.specificName(), Math.max(properties.getSql().getTimeoutSeconds(), 1)
        );
        if (matches.size() != 1) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "SCHEMA_OBJECT_NOT_FOUND", "数据库对象不存在或已发生变化，请刷新对象列表。");
        }
        SchemaObjectCatalog.CatalogObject object = matches.get(0);
        return new ResolvedObject(reference, object, summary(reference.kind(), object));
    }

    private SchemaObjectSummary summary(SchemaObjectKind kind, SchemaObjectCatalog.CatalogObject object) {
        String displayName = object.name();
        if ((kind == SchemaObjectKind.PROCEDURE || kind == SchemaObjectKind.FUNCTION) && object.subtype() != null && !object.subtype().isBlank()) {
            displayName += "(" + object.subtype() + ")";
        } else if (kind == SchemaObjectKind.TRIGGER && object.subtype() != null && !object.subtype().isBlank()) {
            displayName += " · " + object.subtype();
        }
        ObjectRef reference = new ObjectRef(kind, value(object.schemaName()), object.name(), value(object.specificName()));
        return new SchemaObjectSummary(encodeKey(reference), object.schemaName(), object.name(), displayName, kind.name(), object.subtype(), object.status());
    }

    /**
     * 建完对象之后去字典表里问一句「它到底编译过了吗」。
     *
     * <p>Oracle 的 {@code CREATE OR REPLACE} 语法有问题时不抛异常，只是把对象以 INVALID 状态
     * 留在库里。少了这一步，界面会给出一句干净的「创建成功」，直到某天有人调用它才发现是坏的
     * —— 那时报错现场离真正的原因已经隔了很远。</p>
     *
     * <p>查不到（没权限读 ALL_ERRORS、驱动不支持）就当没有编译错误：这是一条补充诊断，不该
     * 让一次已经执行完的 DDL 变成失败。</p>
     */
    private List<CompilationError> readCompilationErrors(Connection connection, DatabaseDialect dialect, LifecyclePlan plan) {
        String sql = dialect.compilationErrorsSql();
        if (sql == null) return List.of();
        if (plan.operation() != SchemaObjectOperation.CREATE && plan.operation() != SchemaObjectOperation.REPLACE) {
            return List.of();
        }
        // 字典表里的类型是 PROCEDURE / MATERIALIZED VIEW 这样的字面量，下划线要还原成空格。
        return CompilationDiagnostics.query(connection, dialect, plan.schemaName(), plan.objectName(),
                plan.kind().name().replace('_', ' '), properties.getSql().getTimeoutSeconds());
    }

    /** 写进 SQL 历史的那一句。取第一条：级联出来的后续错误通常都指向同一个原因。 */
    private static String firstCompilationError(List<CompilationError> errors) {
        CompilationError first = errors.get(0);
        String where = first.line() == null ? "" : "第 " + first.line() + " 行"
                + (first.position() == null ? "" : "第 " + first.position() + " 列") + "：";
        return where + (first.text() == null ? "" : first.text().strip());
    }

    private String atomicReplaceSource(DbConnection configured, SchemaObjectKind kind, String source) {
        if (!hasCapability(configured, kind, SchemaObjectOperation.REPLACE) || source == null) return source;
        String normalized = source.stripLeading();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE OR REPLACE") || upper.startsWith("CREATE OR ALTER") || upper.startsWith("ALTER ")) return source;
        if (upper.startsWith("CREATE ")) {
            return source.substring(0, source.indexOf(normalized)) + objectSql(configured).replacePrefix() + normalized.substring("CREATE ".length());
        }
        return source;
    }

    SchemaObjectCapability requireCapability(DbConnection configured, SchemaObjectKind kind, SchemaObjectOperation operation) {
        SchemaObjectCapability capability = capability(configured, kind);
        if (!capability.operations().contains(operation.name())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "SCHEMA_OBJECT_OPERATION_UNSUPPORTED",
                    "当前数据库不支持对" + kind.label() + "执行“" + operation.name() + "”操作。");
        }
        return capability;
    }

    private boolean hasCapability(DbConnection configured, SchemaObjectKind kind, SchemaObjectOperation operation) {
        return capabilityOrNull(configured, kind) != null && capabilityOrNull(configured, kind).operations().contains(operation.name());
    }

    private SchemaObjectCapability capability(DbConnection configured, SchemaObjectKind kind) {
        SchemaObjectCapability capability = capabilityOrNull(configured, kind);
        if (capability == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "SCHEMA_OBJECT_KIND_UNSUPPORTED", "当前数据库不支持管理" + kind.label() + "。");
        }
        return capability;
    }

    private SchemaObjectCapability capabilityOrNull(DbConnection configured, SchemaObjectKind kind) {
        DatabaseCapabilities capabilities = dialectRegistry.dialectFor(configured).capabilities();
        return capabilities.schemaObjects().stream().filter(item -> item.kind().equals(kind.name())).findFirst().orElse(null);
    }

    void requireFresh(String expected, String actual) {
        if (expected == null || expected.isBlank() || !expected.equals(actual)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "STALE_SCHEMA_OBJECT", "数据库对象已发生变化，请刷新后重试。");
        }
    }

    private String structureVersion(SchemaObjectSummary object, String source, List<SchemaObjectParameter> parameters) throws Exception {
        String canonical = object.objectKey() + "\n" + value(object.status()) + "\n" + value(source) + "\n" + parameters;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private String encodeKey(ObjectRef reference) {
        String raw = reference.kind().name() + "\u0000" + reference.schemaName() + "\u0000" + reference.name() + "\u0000" + reference.specificName();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    ObjectRef decodeKey(String key) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(key), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\u0000", -1);
            if (parts.length != 4) throw new IllegalArgumentException();
            return new ObjectRef(SchemaObjectKind.parse(parts[0]), parts[1], parts[2], parts[3]);
        } catch (Exception ignored) {
            throw new IllegalArgumentException("对象标识无效，请刷新对象列表。");
        }
    }

    private String operationMessage(SchemaObjectOperation operation, SchemaObjectKind kind) {
        return switch (operation) {
            case CREATE -> kind.label() + "已创建。";
            case REPLACE -> kind.label() + "已更新。";
            case DROP -> kind.label() + "已删除。";
            case REFRESH -> "物化视图刷新已触发。";
            case ENABLE -> "触发器已启用。";
            case DISABLE -> "触发器已禁用。";
            default -> "对象操作已完成。";
        };
    }

    String confirmationTarget(SchemaObjectSummary object) {
        boolean routine = object.kind().equals(SchemaObjectKind.PROCEDURE.name()) || object.kind().equals(SchemaObjectKind.FUNCTION.name());
        return qualifiedName(object.schemaName(), routine ? object.displayName() : object.name());
    }

    private String qualifiedName(String schema, String name) {
        return schema == null || schema.isBlank() ? name : schema + "." + name;
    }

    private String normalizeSchema(String schema) {
        if (schema == null) return "";
        String value = schema.trim();
        if (value.length() > 240) throw new IllegalArgumentException("Schema/数据库名最多 240 个字符。");
        return value;
    }

    private SchemaObjectSql objectSql(DbConnection configured) {
        return dialectRegistry.dialectFor(configured).schemaObjects();
    }

    private String normalizeObjectSchema(DbConnection configured, SchemaObjectKind kind, String schema) {
        String normalized = normalizeSchema(schema);
        return objectSql(configured).functionsAreGlobal() && kind == SchemaObjectKind.FUNCTION ? "" : normalized;
    }

    private void validateIdentifier(String identifier, String label) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(label + "仅支持字母、数字、下划线、$ 和 #，且不能以数字开头。");
        }
    }

    static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    static String error(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() > 10_000 ? message.substring(0, 10_000) : message;
    }

    static String value(String value) {
        return value == null ? "" : value;
    }

    record ObjectRef(SchemaObjectKind kind, String schemaName, String name, String specificName) {
    }

    record ResolvedObject(ObjectRef reference, SchemaObjectCatalog.CatalogObject catalogObject, SchemaObjectSummary object) {
    }

    /** {@code objectName} 是编译错误那一步要用的：字典表按对象名查，而 confirmationTarget 是带 schema 前缀的显示名。 */
    private record LifecyclePlan(SchemaObjectOperation operation, SchemaObjectKind kind, String schemaName, String objectName, String sql, String confirmationTarget) {
    }

    private record SchemaObjectPageLoad(
            MetadataCacheService.CachedValue<MetadataCacheService.SchemaObjectPageValue> value,
            boolean cacheHit
    ) {
    }
}
