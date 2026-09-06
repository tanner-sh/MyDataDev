package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.CellSerializer;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.core.SchemaObjectOperation;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.RoutineArgumentInput;
import com.example.dbadmin.dto.ApiDtos.RoutineInvokeRequest;
import com.example.dbadmin.dto.ApiDtos.RoutineInvokeResponse;
import com.example.dbadmin.dto.ApiDtos.RoutineOutParameter;
import com.example.dbadmin.dto.ApiDtos.RoutineResultItem;
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
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SchemaObjectService {
    /** 编译错误条数上限：一条语法错误往往会级联出几十行，前几条才指得出真正的位置。 */
    private static final int MAX_COMPILATION_ERRORS = 100;
    /** 例程消息条数上限：一个循环里的 PUT_LINE 能刷出几十万行。 */
    private static final int MAX_ROUTINE_MESSAGES = 500;
    private static final int MAX_RESULT_ROWS = 500;
    private static final int MAX_RESULT_CELLS = 200_000;
    private static final int MAX_CELL_TEXT = 100_000;
    private static final long MAX_RESULT_TEXT = 20_000_000;
    /** CLOB 先读回多少字符用于判断截断。与 SQL 工作台那条路同值：例程结果就显示在同一张网格里。 */
    private static final int MAX_CLOB_WINDOW_CHARS = 10_000;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
    private static final String DEFINITION_IDENTIFIER = "(?:\"(?:\"\"|[^\"])+\"|`(?:``|[^`])+`|\\[(?:]]|[^]])+]|[A-Za-z_][A-Za-z0-9_$#]*)";
    private static final Pattern DEFINITION_ACTION = Pattern.compile("(?is)^(CREATE(?:\\s+OR\\s+(?:REPLACE|ALTER))?|ALTER)\\s+");
    private static final Pattern DEFINITION_MODIFIER = Pattern.compile("(?is)^(?:(?:FORCE|EDITIONABLE|NONEDITIONABLE)\\s+|ALGORITHM\\s*=\\s*[A-Z_]+\\s+|SQL\\s+SECURITY\\s+(?:DEFINER|INVOKER)\\s+|DEFINER\\s*=\\s*(?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[(?:]]|[^]])+]|[^\\s]+)(?:\\s*@\\s*(?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[(?:]]|[^]])+]|[^\\s]+))?\\s+)");
    private static final Pattern DEFINITION_NAME = Pattern.compile("(?is)^\\s*(" + DEFINITION_IDENTIFIER + ")(?:\\s*\\.\\s*(" + DEFINITION_IDENTIFIER + "))?");
    private static final Pattern CLIENT_BATCH_SEPARATOR = Pattern.compile("(?im)^\\s*(?:DELIMITER\\b.*|GO\\s*|/\\s*)$");

    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final SchemaObjectCatalog catalog;
    private final MetadataCacheService cache;
    private final ExecutionGuard executionGuard;
    private final AuditRepository audit;
    private final SqlHistoryRepository history;
    private final AppProperties properties;
    private final SqlScriptSplitter scriptSplitter;
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
        this.scriptSplitter = scriptSplitter;
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

    public SchemaObjectTemplateResponse template(long connectionId, String kindValue, String schemaName, String objectName) {
        SchemaObjectKind kind = SchemaObjectKind.parse(kindValue);
        DbConnection configured = connections.require(connectionId);
        requireCapability(configured, kind, SchemaObjectOperation.CREATE);
        validateIdentifier(objectName, "对象名");
        String schema = normalizeObjectSchema(configured, kind, schemaName);
        if (!schema.isBlank()) validateIdentifier(schema, "Schema/数据库名");
        DatabaseDialect dialect = dialectRegistry.dialectFor(configured);
        return new SchemaObjectTemplateResponse(kind.name(), schema, objectName, catalog.template(configured, dialect, kind, schema, objectName));
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
            if (!plan.schemaName().isBlank() && Set.of("mysql", "mariadb").contains(catalog.family(configured))) {
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

    public RoutineInvokeResponse invoke(
            long connectionId,
            RoutineInvokeRequest request,
            String actor,
            String productionConfirmation
    ) throws Exception {
        DbConnection configured = connections.require(connectionId);
        executionGuard.requireMutationAllowed(configured, productionConfirmation);
        ObjectRef reference = decodeKey(request.objectKey());
        if (reference.kind() != SchemaObjectKind.PROCEDURE && reference.kind() != SchemaObjectKind.FUNCTION) {
            throw new IllegalArgumentException("仅存储过程和函数支持调用。");
        }
        requireCapability(configured, reference.kind(), SchemaObjectOperation.INVOKE);
        long started = System.nanoTime();
        String historySql = null;
        try (Connection connection = connections.open(connectionId)) {
            ResolvedObject resolved = resolve(connection, configured, reference);
            SchemaObjectDetail live = loadDetail(connection, configured, resolved);
            requireFresh(request.structureVersion(), live.structureVersion());
            DatabaseDialect dialect = dialectRegistry.dialectFor(configured);
            List<SchemaObjectParameter> parameters = live.parameters();
            Map<Integer, RoutineArgumentInput> inputs = new HashMap<>();
            Set<Integer> inputPositions = parameters.stream()
                    .filter(parameter -> parameter.mode().equals("IN") || parameter.mode().equals("INOUT"))
                    .map(SchemaObjectParameter::position)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (RoutineArgumentInput input : request.arguments() == null ? List.<RoutineArgumentInput>of() : request.arguments()) {
                if (!inputPositions.contains(input.position())) throw new IllegalArgumentException("例程参数位置无效：" + input.position());
                if (inputs.put(input.position(), input) != null) throw new IllegalArgumentException("例程参数位置不能重复：" + input.position());
            }
            for (SchemaObjectParameter parameter : parameters) {
                if ((parameter.mode().equals("IN") || parameter.mode().equals("INOUT")) && !inputs.containsKey(parameter.position())) {
                    throw new IllegalArgumentException("请填写参数：" + parameterLabel(parameter));
                }
            }
            String family = catalog.family(configured);
            historySql = routineSql(dialect, family, reference.kind(), resolved.object(), parameters);
            // 旧警告会被当成这次调用的输出报出来，池化连接上尤其容易串味。
            connection.clearWarnings();
            enableRoutineOutput(connection, dialect);
            RoutineInvokeResponse response = family.equals("postgresql") || family.equals("clickhouse")
                    ? invokePrepared(connection, dialect, reference.kind(), resolved.object(), parameters, inputs, started)
                    : invokeCallable(connection, dialect, reference.kind(), resolved.object(), parameters, inputs, started);
            String auditDetail = parameters.stream()
                    .map(parameter -> parameterLabel(parameter) + ":" + value(parameter.typeName()) + ":" + parameter.mode())
                    .reduce((left, right) -> left + ", " + right).orElse("无参数");
            audit.onConnection(actor, "OBJECT_INVOKE", connectionId, "object:" + confirmationTarget(live.object()), auditDetail);
            history.insert(connectionId, historySql, "ROUTINE_INVOKE", "SUCCESS", response.elapsedMs(), null, actor);
            return response;
        } catch (Exception exception) {
            history.insert(connectionId, historySql == null ? "CALL <schema-object>" : historySql, "ROUTINE_INVOKE", "FAILED", elapsed(started), error(exception), actor);
            audit.onConnection(actor, "OBJECT_INVOKE_FAILED", connectionId, error(exception));
            throw exception;
        } finally {
            // Routines may execute DDL internally, so metadata cannot be kept.
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
            String source = requireDefinitionSource(request.source(), operation, kind, schema, request.objectName(), catalog.family(configured));
            try (Connection connection = connections.open(connectionId)) {
                boolean exists = catalog.existsByName(
                        connection, configured, dialect, schema, kind, request.objectName(),
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
                case REPLACE -> requireDefinitionSource(request.source(), operation, kind, reference.schemaName(), resolved.object().name(), catalog.family(configured));
                case DROP -> catalog.dropSql(configured, dialect, kind, resolved.catalogObject());
                case REFRESH, ENABLE, DISABLE -> catalog.specialOperationSql(configured, dialect, operation, kind, resolved.catalogObject());
                default -> throw new IllegalArgumentException("不支持的对象操作。");
            };
            return new LifecyclePlan(operation, kind, reference.schemaName(), resolved.object().name(), sql, confirmationTarget(live.object()));
        }
    }

    private SchemaObjectDetail loadDetail(Connection connection, DbConnection configured, ResolvedObject resolved) throws Exception {
        DatabaseDialect dialect = dialectRegistry.dialectFor(configured);
        SchemaObjectKind kind = resolved.reference().kind();
        String loadedSource = null;
        String sourceError = null;
        try {
            loadedSource = catalog.source(connection, configured, dialect, kind, resolved.catalogObject());
        } catch (Exception error) {
            sourceError = error(error);
        }
        List<SchemaObjectParameter> parameters = List.of();
        String parameterError = null;
        try {
            parameters = catalog.parameters(connection, configured, dialect, kind, resolved.catalogObject());
        } catch (Exception error) {
            parameterError = error(error);
        }
        List<SchemaObjectDependency> dependencies = List.of();
        boolean dependenciesAvailable = hasCapability(configured, kind, SchemaObjectOperation.DEPENDENCIES);
        String dependencyError = dependenciesAvailable ? null : "当前数据库方言没有可靠的依赖元数据。";
        if (dependenciesAvailable) {
            try {
                dependencies = catalog.dependencies(connection, configured, kind, resolved.catalogObject());
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
                        || !catalog.family(configured).equals("clickhouse")
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
                        connection, configured, dialect, schema, kind, keyword, page, pageSize,
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

    private ResolvedObject resolve(Connection connection, DbConnection configured, ObjectRef reference) throws Exception {
        List<SchemaObjectCatalog.CatalogObject> matches = catalog.find(
                connection, configured, dialectRegistry.dialectFor(configured), reference.schemaName(), reference.kind(),
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
        List<CompilationError> errors = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(Math.max(properties.getSql().getTimeoutSeconds(), 1));
            statement.setString(1, plan.schemaName().isBlank() ? null : plan.schemaName());
            statement.setString(2, plan.objectName());
            // 字典表里的类型是 PROCEDURE / MATERIALIZED VIEW 这样的字面量，下划线要还原成空格。
            statement.setString(3, plan.kind().name().replace('_', ' '));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next() && errors.size() < MAX_COMPILATION_ERRORS) {
                    errors.add(new CompilationError(
                            intOrNull(rs.getObject("line")), intOrNull(rs.getObject("position")), rs.getString("text")));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(errors);
    }

    /** 写进 SQL 历史的那一句。取第一条：级联出来的后续错误通常都指向同一个原因。 */
    private static String firstCompilationError(List<CompilationError> errors) {
        CompilationError first = errors.get(0);
        String where = first.line() == null ? "" : "第 " + first.line() + " 行"
                + (first.position() == null ? "" : "第 " + first.position() + " 列") + "：";
        return where + (first.text() == null ? "" : first.text().strip());
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * 例程执行期间产生的消息。
     *
     * <p>两个来源：JDBC 的 SQLWarning 链（PostgreSQL 的 {@code RAISE NOTICE}、MySQL 的警告都
     * 走这里），以及 Oracle 的 {@code DBMS_OUTPUT} 缓冲 —— 后者不显式读回就永远看不到，而
     * PL/SQL 里排查问题基本全靠它。</p>
     *
     * <p>整段吞异常：这是调用之外的诊断信息，读不回来不该把一次已经成功的调用改判成失败。</p>
     */
    private List<String> routineMessages(Connection connection, DatabaseDialect dialect, Statement statement) {
        List<String> messages = new ArrayList<>();
        try {
            collectWarnings(statement.getWarnings(), messages);
            collectWarnings(connection.getWarnings(), messages);
        } catch (Exception ignored) {
            // 读警告本身失败就算了。
        }
        String fetch = dialect.routineOutputFetchCall();
        if (fetch == null) return List.copyOf(messages);
        try (CallableStatement output = connection.prepareCall(fetch)) {
            output.registerOutParameter(1, Types.VARCHAR);
            output.registerOutParameter(2, Types.INTEGER);
            while (messages.size() < MAX_ROUTINE_MESSAGES) {
                output.execute();
                // 状态码非 0 表示缓冲读完了。这是 DBMS_OUTPUT.GET_LINE 的约定。
                if (output.getInt(2) != 0) break;
                String line = output.getString(1);
                if (line != null) messages.add(line);
            }
        } catch (Exception ignored) {
            // 缓冲没打开、没权限、或这个连接根本不是 Oracle：都不是调用本身的问题。
        }
        return List.copyOf(messages);
    }

    private static void collectWarnings(SQLWarning warning, List<String> messages) {
        SQLWarning current = warning;
        while (current != null && messages.size() < MAX_ROUTINE_MESSAGES) {
            String text = current.getMessage();
            if (text != null && !text.isBlank()) messages.add(text.strip());
            current = current.getNextWarning();
        }
    }

    /** 打开服务端的输出缓冲。失败无所谓：拿不到 DBMS_OUTPUT 只是少一份诊断信息。 */
    private void enableRoutineOutput(Connection connection, DatabaseDialect dialect) {
        String sql = dialect.routineOutputEnableSql();
        if (sql == null) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception ignored) {
            // 见方法注释。
        }
    }

    private RoutineInvokeResponse invokePrepared(
            Connection connection,
            DatabaseDialect dialect,
            SchemaObjectKind kind,
            SchemaObjectSummary object,
            List<SchemaObjectParameter> parameters,
            Map<Integer, RoutineArgumentInput> inputs,
            long started
    ) throws Exception {
        List<SchemaObjectParameter> inputParameters = parameters.stream()
                .filter(parameter -> parameter.mode().equals("IN") || parameter.mode().equals("INOUT"))
                .toList();
        String qualified = dialect.qualifiedName(object.schemaName(), object.name());
        String placeholders = String.join(", ", inputParameters.stream().map(ignored -> "?").toList());
        String sql = kind == SchemaObjectKind.PROCEDURE ? "CALL " + qualified + "(" + placeholders + ")"
                : "SELECT * FROM " + qualified + "(" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(Math.max(properties.getSql().getTimeoutSeconds(), 1));
            for (int index = 0; index < inputParameters.size(); index++) {
                bind(statement, index + 1, inputParameters.get(index), inputs.get(inputParameters.get(index).position()));
            }
            List<RoutineResultItem> results = readResults(statement, statement.execute(), started);
            Object returnValue = kind == SchemaObjectKind.FUNCTION && !results.isEmpty() && results.get(0).result() != null
                    && !results.get(0).result().rows().isEmpty() && !results.get(0).result().rows().get(0).isEmpty()
                    ? results.get(0).result().rows().get(0).get(0) : null;
            return new RoutineInvokeResponse("SUCCESS", elapsed(started), returnValue, List.of(), results,
                    results.stream().anyMatch(item -> item.result() != null && item.result().truncated()),
                    routineMessages(connection, dialect, statement));
        }
    }

    private RoutineInvokeResponse invokeCallable(
            Connection connection,
            DatabaseDialect dialect,
            SchemaObjectKind kind,
            SchemaObjectSummary object,
            List<SchemaObjectParameter> parameters,
            Map<Integer, RoutineArgumentInput> inputs,
            long started
    ) throws Exception {
        List<SchemaObjectParameter> callParameters = parameters.stream().filter(parameter -> !parameter.mode().equals("RETURN")).toList();
        boolean hasReturn = kind == SchemaObjectKind.FUNCTION;
        String placeholders = String.join(", ", callParameters.stream().map(ignored -> "?").toList());
        String sql = "{" + (hasReturn ? "? = " : "") + "call " + dialect.qualifiedName(object.schemaName(), object.name()) + "(" + placeholders + ")}";
        try (CallableStatement statement = connection.prepareCall(sql)) {
            statement.setQueryTimeout(Math.max(properties.getSql().getTimeoutSeconds(), 1));
            int jdbcIndex = 1;
            if (hasReturn) {
                SchemaObjectParameter returned = parameters.stream().filter(parameter -> parameter.mode().equals("RETURN")).findFirst().orElse(null);
                statement.registerOutParameter(jdbcIndex++, safeJdbcType(returned));
            }
            Map<SchemaObjectParameter, Integer> positions = new LinkedHashMap<>();
            for (SchemaObjectParameter parameter : callParameters) {
                positions.put(parameter, jdbcIndex);
                if (parameter.mode().equals("OUT") || parameter.mode().equals("INOUT")) {
                    statement.registerOutParameter(jdbcIndex, safeJdbcType(parameter));
                }
                if (parameter.mode().equals("IN") || parameter.mode().equals("INOUT")) {
                    bind(statement, jdbcIndex, parameter, inputs.get(parameter.position()));
                }
                jdbcIndex++;
            }
            boolean hasResult = statement.execute();
            // Some drivers (notably H2) expose a function return value through the
            // current result set and make it unavailable after getMoreResults().
            Object returnValue = hasReturn ? serializable(statement.getObject(1), MAX_CELL_TEXT) : null;
            List<RoutineResultItem> results = readResults(statement, hasResult, started);
            List<RoutineOutParameter> out = new ArrayList<>();
            for (Map.Entry<SchemaObjectParameter, Integer> entry : positions.entrySet()) {
                SchemaObjectParameter parameter = entry.getKey();
                if (parameter.mode().equals("OUT") || parameter.mode().equals("INOUT")) {
                    out.add(new RoutineOutParameter(parameter.name(), parameter.typeName(), serializable(statement.getObject(entry.getValue()), MAX_CELL_TEXT)));
                }
            }
            return new RoutineInvokeResponse("SUCCESS", elapsed(started), returnValue, out, results,
                    results.stream().anyMatch(item -> item.result() != null && item.result().truncated()),
                    routineMessages(connection, dialect, statement));
        }
    }

    private List<RoutineResultItem> readResults(Statement statement, boolean hasResult, long started) throws Exception {
        List<RoutineResultItem> results = new ArrayList<>();
        int resultCount = 0;
        while (resultCount++ < 20) {
            if (hasResult) {
                try (ResultSet rs = statement.getResultSet()) {
                    results.add(new RoutineResultItem("RESULT_SET", readResult(rs, started), null));
                }
            } else {
                int updateCount = statement.getUpdateCount();
                if (updateCount == -1) break;
                results.add(new RoutineResultItem("UPDATE_COUNT", null, updateCount));
            }
            hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        }
        return results;
    }

    private SqlResult readResult(ResultSet rs, long started) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<ResultColumn> columns = new ArrayList<>();
        for (int index = 1; index <= columnCount; index++) {
            columns.add(new ResultColumn("c" + index, metadata.getColumnLabel(index), metadata.getColumnTypeName(index)));
        }
        int effectiveRows = Math.min(MAX_RESULT_ROWS, MAX_RESULT_CELLS / Math.max(columnCount, 1));
        List<List<Object>> rows = new ArrayList<>();
        long textChars = 0;
        boolean payloadLimit = false;
        while (rows.size() < effectiveRows && rs.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                int remaining = (int) Math.min(MAX_CELL_TEXT, Math.max(0, MAX_RESULT_TEXT - textChars));
                Object value = serializable(rs.getObject(index), remaining);
                row.add(value);
                if (value instanceof CharSequence text) textChars += text.length();
            }
            rows.add(row);
            if (textChars >= MAX_RESULT_TEXT) {
                payloadLimit = true;
                break;
            }
        }
        boolean truncated = payloadLimit || rs.next();
        return new SqlResult(columns, rows, -1, elapsed(started), true, effectiveRows, truncated);
    }

    private void bind(PreparedStatement statement, int index, SchemaObjectParameter parameter, RoutineArgumentInput input) throws Exception {
        if (input == null) throw new IllegalArgumentException("缺少参数：" + parameterLabel(parameter));
        int jdbcType = safeJdbcType(parameter);
        if (input.nullValue()) {
            statement.setNull(index, jdbcType);
            return;
        }
        String value = input.value() == null ? "" : input.value();
        Object converted = switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> new BigDecimal(value.trim());
            case Types.BOOLEAN, Types.BIT -> parseBoolean(value);
            case Types.DATE -> java.sql.Date.valueOf(LocalDate.parse(value.trim()));
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> java.sql.Time.valueOf(LocalTime.parse(value.trim()));
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> java.sql.Timestamp.valueOf(LocalDateTime.parse(value.trim().replace(' ', 'T')));
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY,
                    Types.ARRAY, Types.STRUCT, Types.SQLXML, Types.REF, Types.REF_CURSOR, Types.JAVA_OBJECT ->
                    throw new IllegalArgumentException("暂不支持输入复杂参数类型：" + value(parameter.typeName()));
            default -> value;
        };
        statement.setObject(index, converted, jdbcType);
    }

    private boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "是" -> true;
            case "false", "0", "no", "n", "否" -> false;
            default -> throw new IllegalArgumentException("布尔参数必须是 true/false 或 1/0。");
        };
    }

    /**
     * 例程执行结果的单元格取值。
     *
     * <p>走的是与 SQL 工作台、表数据浏览、执行计划、导出完全同一套规则（{@link CellSerializer}）——
     * 这里产出的也是一个 {@code SqlResult}，渲染在同一张结果网格里。自己再抄一份的下场是漂移：
     * 这条路此前用 {@code value.toString()} 收尾，整点时间戳因此比别处多一个 {@code .0}；
     * {@code BigInteger} 也漏在了「转成字符串」那一档外面，被当成 JSON 数字发出去，
     * 而浏览器按双精度解析 —— MySQL 的 {@code BIGINT UNSIGNED} 正是 {@code BigInteger}，
     * 19 位的 ID 会被静默改写。</p>
     */
    private Object serializable(Object value, int maxText) throws Exception {
        return CellSerializer.serialize(value, maxText, MAX_CLOB_WINDOW_CHARS);
    }

    private String routineSql(DatabaseDialect dialect, String family, SchemaObjectKind kind, SchemaObjectSummary object, List<SchemaObjectParameter> parameters) {
        int inputCount = (int) parameters.stream().filter(parameter -> parameter.mode().equals("IN") || parameter.mode().equals("INOUT")).count();
        String placeholders = String.join(", ", java.util.Collections.nCopies(inputCount, "?"));
        String qualified = dialect.qualifiedName(object.schemaName(), object.name());
        if (family.equals("postgresql") || family.equals("clickhouse")) {
            return kind == SchemaObjectKind.PROCEDURE ? "CALL " + qualified + "(" + placeholders + ")" : "SELECT * FROM " + qualified + "(" + placeholders + ")";
        }
        return "{" + (kind == SchemaObjectKind.FUNCTION ? "? = " : "") + "call " + qualified + "(" + placeholders + ")}";
    }

    private int safeJdbcType(SchemaObjectParameter parameter) {
        return parameter == null || parameter.jdbcType() == null || parameter.jdbcType() == Types.NULL ? Types.VARCHAR : parameter.jdbcType();
    }

    private String atomicReplaceSource(DbConnection configured, SchemaObjectKind kind, String source) {
        if (!hasCapability(configured, kind, SchemaObjectOperation.REPLACE) || source == null) return source;
        String normalized = source.stripLeading();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE OR REPLACE") || upper.startsWith("CREATE OR ALTER") || upper.startsWith("ALTER ")) return source;
        String family = catalog.family(configured);
        if (upper.startsWith("CREATE ")) {
            String replacement = family.equals("sqlserver") ? "CREATE OR ALTER " : "CREATE OR REPLACE ";
            return source.substring(0, source.indexOf(normalized)) + replacement + normalized.substring("CREATE ".length());
        }
        return source;
    }

    private String requireDefinitionSource(String source, SchemaObjectOperation operation, SchemaObjectKind kind, String schemaName, String objectName, String family) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("对象源码不能为空。");
        if (source.length() > 2_000_000) throw new IllegalArgumentException("对象源码不能超过 200 万字符。");
        if (CLIENT_BATCH_SEPARATOR.matcher(source).find()) {
            throw new IllegalArgumentException("源码不能包含 DELIMITER、GO 或单独的 / 等客户端批处理分隔符。");
        }
        requireSingleDefinition(source, kind, family);
        validateDefinitionTarget(source, operation, kind, schemaName, objectName, family);
        return source.trim();
    }

    private void validateDefinitionTarget(String source, SchemaObjectOperation operation, SchemaObjectKind kind, String schemaName, String objectName, String family) {
        String remainder = stripLeadingComments(source);
        var actionMatcher = DEFINITION_ACTION.matcher(remainder);
        if (!actionMatcher.find()) {
            throw new IllegalArgumentException(operation == SchemaObjectOperation.CREATE
                    ? "新建源码必须以 CREATE 开头。"
                    : "修改源码必须使用 CREATE OR REPLACE、CREATE OR ALTER 或安全 ALTER。");
        }
        String action = actionMatcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (operation == SchemaObjectOperation.CREATE && !action.equals("CREATE")) {
            throw new IllegalArgumentException("新建源码不能使用 OR REPLACE、OR ALTER 或 ALTER，以免覆盖已有对象。");
        }
        if (operation == SchemaObjectOperation.REPLACE && action.equals("CREATE")) {
            throw new IllegalArgumentException("修改源码必须使用 CREATE OR REPLACE、CREATE OR ALTER 或安全 ALTER。");
        }
        remainder = remainder.substring(actionMatcher.end()).stripLeading();
        while (true) {
            var modifier = DEFINITION_MODIFIER.matcher(remainder);
            if (!modifier.find()) break;
            remainder = remainder.substring(modifier.end()).stripLeading();
        }

        String expectedKind = kind == SchemaObjectKind.MATERIALIZED_VIEW ? "MATERIALIZED VIEW" : kind.name();
        String declarationKind = kind == SchemaObjectKind.FUNCTION && family.equals("h2") && startsWithWord(remainder, "ALIAS")
                ? "ALIAS" : expectedKind;
        if (!startsWithWord(remainder, declarationKind)) {
            throw new IllegalArgumentException("源码定义的对象类型与当前目标不匹配。");
        }
        remainder = remainder.substring(declarationKind.length()).stripLeading();
        if (remainder.toUpperCase(Locale.ROOT).startsWith("IF NOT EXISTS ")) {
            remainder = remainder.substring("IF NOT EXISTS ".length()).stripLeading();
        }
        var nameMatcher = DEFINITION_NAME.matcher(remainder);
        if (!nameMatcher.find()) throw new IllegalArgumentException("无法识别源码定义的对象名。");
        String declaredName = nameMatcher.group(2) == null ? nameMatcher.group(1) : nameMatcher.group(2);
        if (!sameDefinitionIdentifier(declaredName, objectName)) {
            throw new IllegalArgumentException("源码定义的对象名与当前目标不匹配。");
        }
        String declaredSchema = nameMatcher.group(2) == null ? null : nameMatcher.group(1);
        if (declaredSchema != null && (schemaName == null || schemaName.isBlank() || !sameDefinitionIdentifier(declaredSchema, schemaName))) {
            throw new IllegalArgumentException("源码定义的命名空间与当前目标不匹配。");
        }
        boolean globalObject = family.equals("clickhouse") && kind == SchemaObjectKind.FUNCTION;
        if (declaredSchema == null && schemaName != null && !schemaName.isBlank()
                && !Set.of("mysql", "mariadb").contains(family) && !globalObject) {
            throw new IllegalArgumentException("对象源码必须使用当前命名空间限定对象名：" + schemaName + "." + objectName);
        }
    }

    private boolean startsWithWord(String source, String expected) {
        if (!source.regionMatches(true, 0, expected, 0, expected.length())) return false;
        return source.length() == expected.length() || Character.isWhitespace(source.charAt(expected.length()));
    }

    private boolean sameDefinitionIdentifier(String declared, String expected) {
        boolean quoted = declared.startsWith("\"") || declared.startsWith("`") || declared.startsWith("[");
        String value = declared;
        if (declared.startsWith("\"") && declared.endsWith("\"")) value = declared.substring(1, declared.length() - 1).replace("\"\"", "\"");
        else if (declared.startsWith("`") && declared.endsWith("`")) value = declared.substring(1, declared.length() - 1).replace("``", "`");
        else if (declared.startsWith("[") && declared.endsWith("]")) value = declared.substring(1, declared.length() - 1).replace("]]", "]");
        return quoted ? value.equals(expected) : value.equalsIgnoreCase(expected);
    }

    private void requireSingleDefinition(String source, SchemaObjectKind kind, String family) {
        List<SqlScriptSplitter.StatementSegment> statements = scriptSplitter.split(source);
        if (statements.size() <= 1) return;
        boolean procedural = kind == SchemaObjectKind.TRIGGER || kind == SchemaObjectKind.PROCEDURE || kind == SchemaObjectKind.FUNCTION;
        if (!procedural) throw new IllegalArgumentException("对象源码只能包含一个顶层定义语句。");

        String effective = stripTrailingComments(source).stripTrailing().toUpperCase(Locale.ROOT);
        // Procedural definitions contain internal semicolons. For dialects
        // that use BEGIN/END bodies, accept them only when the complete input
        // ends at the native END marker. Appended statements remain outside
        // that marker and are rejected. PostgreSQL/H2 dollar-quoted bodies are
        // already treated as one statement by SqlScriptSplitter.
        if (!Set.of("mysql", "mariadb", "oracle", "sqlserver").contains(family)
                || !effective.matches("(?s).*\\bEND\\s*;?\\s*$")) {
            throw new IllegalArgumentException("对象源码只能包含一个顶层定义语句。");
        }
    }

    private String stripTrailingComments(String source) {
        String value = source;
        while (true) {
            String trimmed = value.stripTrailing();
            if (trimmed.endsWith("*/")) {
                int start = trimmed.lastIndexOf("/*");
                if (start >= 0) {
                    value = trimmed.substring(0, start);
                    continue;
                }
            }
            int lineStart = Math.max(trimmed.lastIndexOf('\n'), trimmed.lastIndexOf('\r')) + 1;
            if (trimmed.substring(lineStart).stripLeading().startsWith("--")) {
                value = trimmed.substring(0, lineStart);
                continue;
            }
            return trimmed;
        }
    }

    private String stripLeadingComments(String source) {
        String value = source.stripLeading();
        while (true) {
            if (value.startsWith("--")) {
                int newline = value.indexOf('\n');
                value = newline < 0 ? "" : value.substring(newline + 1).stripLeading();
                continue;
            }
            if (value.startsWith("/*")) {
                int end = value.indexOf("*/", 2);
                value = end < 0 ? "" : value.substring(end + 2).stripLeading();
                continue;
            }
            return value;
        }
    }

    private SchemaObjectCapability requireCapability(DbConnection configured, SchemaObjectKind kind, SchemaObjectOperation operation) {
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

    private void requireFresh(String expected, String actual) {
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

    private ObjectRef decodeKey(String key) {
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

    private String confirmationTarget(SchemaObjectSummary object) {
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

    private String normalizeObjectSchema(DbConnection configured, SchemaObjectKind kind, String schema) {
        String normalized = normalizeSchema(schema);
        return catalog.family(configured).equals("clickhouse") && kind == SchemaObjectKind.FUNCTION ? "" : normalized;
    }

    private void validateIdentifier(String identifier, String label) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(label + "仅支持字母、数字、下划线、$ 和 #，且不能以数字开头。");
        }
    }

    private String parameterLabel(SchemaObjectParameter parameter) {
        return parameter.name() == null || parameter.name().isBlank() ? "参数 " + parameter.position() : parameter.name();
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private String error(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() > 10_000 ? message.substring(0, 10_000) : message;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record ObjectRef(SchemaObjectKind kind, String schemaName, String name, String specificName) {
    }

    private record ResolvedObject(ObjectRef reference, SchemaObjectCatalog.CatalogObject catalogObject, SchemaObjectSummary object) {
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
