package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import com.example.dbadmin.dto.ApiDtos.TableDesignResponse;
import com.example.dbadmin.dto.ApiDtos.TableLifecycleRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表结构设计与表生命周期（新建、重命名、删除）：校验设计稿、生成 DDL、执行并清缓存、写审计。
 *
 * <p>从 MetadataService 拆出来 —— 那边只读结构，这边改结构。改结构的这一半要过生产确认与审计，
 * 而读的那一半一个都不用，两者原来挤在同一个类里，读的路径也被迫带着 ExecutionGuard 和审计。
 * 现结构一律从 {@link MetadataService#detail} 现读（不走缓存），DDL 由方言生成。</p>
 */
@Service
public class TableDesignService {
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final AuditRepository audit;
    private final MetadataCacheService cache;
    private final ExecutionGuard executionGuard;
    private final MetadataService metadata;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    public TableDesignService(ConnectionService connections, DialectRegistry dialectRegistry, AuditRepository audit,
                              MetadataCacheService cache, ExecutionGuard executionGuard, MetadataService metadata) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.audit = audit;
        this.cache = cache;
        this.executionGuard = executionGuard;
        this.metadata = metadata;
    }

    public TableDesignResponse previewDesign(long connectionId, TableDesignRequest request) throws Exception {
        List<String> sql = designSql(connectionId, request);
        return new TableDesignResponse(sql, sql.isEmpty() ? "没有检测到结构变更。" : "已生成 " + sql.size() + " 条 DDL。");
    }

    public TableDesignResponse executeDesign(long connectionId, TableDesignRequest request, String actor, String productionConfirmation) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireMutationAllowed(dbConnection, productionConfirmation);
        String expected = qualifiedName(request.schemaName(), request.tableName());
        if (!expected.equals(request.confirmation())) {
            throw new IllegalArgumentException("确认文本不匹配，请输入完整表名：" + expected);
        }
        List<String> sql = designSql(connectionId, request);
        if (sql.isEmpty()) {
            return new TableDesignResponse(List.of(), "没有检测到结构变更。");
        }
        executeDdl(connectionId, sql, () -> cache.evictObject(connectionId, request.schemaName(), request.tableName()));
        audit.onConnection(actor, "TABLE_DESIGN_EXECUTE", connectionId, "table:" + expected, String.join("\n", sql));
        return new TableDesignResponse(sql, "已执行 " + sql.size() + " 条 DDL。");
    }

    public TableDesignResponse executeDesign(long connectionId, TableDesignRequest request, String actor) throws Exception {
        return executeDesign(connectionId, request, actor, null);
    }

    public TableDesignResponse previewTableLifecycle(long connectionId, TableLifecycleRequest request) throws Exception {
        TableLifecyclePlan plan = tableLifecyclePlan(connectionId, request);
        return new TableDesignResponse(plan.sql(), "已生成 " + plan.sql().size() + " 条 DDL。");
    }

    public TableDesignResponse executeTableLifecycle(
            long connectionId,
            TableLifecycleRequest request,
            String actor,
            String productionConfirmation
    ) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireMutationAllowed(dbConnection, productionConfirmation);
        TableLifecyclePlan plan = tableLifecyclePlan(connectionId, request);
        if (!plan.confirmationTarget().equals(request.confirmation())) {
            throw new IllegalArgumentException("确认文本不匹配，请输入完整表名：" + plan.confirmationTarget());
        }
        executeDdl(connectionId, plan.sql(), () -> cache.evictConnection(connectionId));
        audit.onConnection(
                actor,
                "TABLE_" + plan.operation().name(),
                connectionId,
                "table:" + plan.confirmationTarget(),
                String.join("\n", plan.sql())
        );
        return new TableDesignResponse(plan.sql(), switch (plan.operation()) {
            case CREATE -> "表已创建。";
            case RENAME -> "表已重命名。";
            case DROP -> "表已删除。";
        });
    }

    private TableLifecyclePlan tableLifecyclePlan(long connectionId, TableLifecycleRequest request) throws Exception {
        TableLifecycleOperation operation = TableLifecycleOperation.parse(request.operation());
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().tableDesign()) {
            throw new IllegalStateException("当前数据库类型暂不支持表对象管理。请使用数据库原生工具执行 DDL。");
        }
        validateIdentifier(request.tableName(), "表名");
        if (request.schemaName() != null && !request.schemaName().isBlank()) {
            validateIdentifier(request.schemaName(), "Schema");
        }

        if (operation == TableLifecycleOperation.CREATE) {
            TableDesignRequest design = new TableDesignRequest(
                    request.schemaName(), request.tableName(), request.columns(), request.indexes(), request.primaryKeys(), null, null
            );
            validateCreateDesign(design);
            try (Connection connection = connections.open(connectionId)) {
                DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, request.schemaName());
                DbObject existing = metadata.findObjectIfExists(connection.getMetaData(), scope, dialect, request.tableName());
                if (existing != null) {
                    throw new ApiProblemException(
                            org.springframework.http.HttpStatus.CONFLICT,
                            "TABLE_ALREADY_EXISTS",
                            "目标表已存在：" + qualifiedName(existing.schemaName(), existing.name())
                    );
                }
            }
            String target = qualifiedName(request.schemaName(), request.tableName());
            return new TableLifecyclePlan(operation, request.schemaName(), request.tableName(), null, target,
                    dialect.createTableSql(request.schemaName(), request.tableName(), design));
        }

        ObjectDetail original = metadata.detail(connectionId, request.schemaName(), request.tableName(), true);
        if (!MetadataService.isPhysicalTable(original.type())) {
            throw new IllegalArgumentException("仅支持管理物理表：" + qualifiedName(original.schemaName(), original.name()));
        }
        if (request.structureVersion() == null || request.structureVersion().isBlank()
                || !request.structureVersion().equals(original.structureVersion())) {
            throw new ApiProblemException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "STALE_TABLE_OBJECT",
                    "表结构已发生变化，请刷新对象后重试。"
            );
        }
        String source = qualifiedName(original.schemaName(), original.name());
        if (operation == TableLifecycleOperation.DROP) {
            return new TableLifecyclePlan(operation, original.schemaName(), original.name(), null, source,
                    List.of(dialect.dropTableSql(original.schemaName(), original.name())));
        }

        validateIdentifier(request.newTableName(), "新表名");
        if (original.name().equalsIgnoreCase(request.newTableName())) {
            throw new IllegalArgumentException("新表名必须与当前表名不同，首版不支持仅修改大小写。");
        }
        try (Connection connection = connections.open(connectionId)) {
            DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, original.schemaName());
            DbObject existing = metadata.findObjectIfExists(connection.getMetaData(), scope, dialect, request.newTableName());
            if (existing != null) {
                throw new ApiProblemException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "TABLE_ALREADY_EXISTS",
                        "目标表已存在：" + qualifiedName(existing.schemaName(), existing.name())
                );
            }
        }
        return new TableLifecyclePlan(operation, original.schemaName(), original.name(), request.newTableName(), source,
                List.of(dialect.renameTableSql(original.schemaName(), original.name(), request.newTableName())));
    }

    private void validateCreateDesign(TableDesignRequest design) {
        validateDesign(design);
        for (var column : design.columns()) {
            if (column.deleted() || column.originalName() != null && !column.originalName().isBlank()) {
                throw new IllegalArgumentException("新建表字段不能包含删除标记或原字段名。");
            }
        }
        if (design.indexes() != null) {
            for (var index : design.indexes()) {
                if (index.deleted() || index.originalName() != null && !index.originalName().isBlank()) {
                    throw new IllegalArgumentException("新建表索引不能包含删除标记或原索引名。");
                }
            }
        }
    }

    private void executeDdl(long connectionId, List<String> sql, Runnable evictCache) throws Exception {
        int executed = 0;
        try (Connection connection = connections.open(connectionId); Statement statement = connection.createStatement()) {
            for (String line : sql) {
                statement.execute(line);
                executed++;
            }
        } catch (Exception error) {
            String failedStatement = executed < sql.size() ? sql.get(executed) : "";
            String message = executed == 0
                    ? "DDL 执行失败，数据库未确认任何语句成功。"
                    : "DDL 部分执行失败：已有 " + executed + " 条语句成功，请刷新对象并核对实际结构。";
            ApiProblemException problem = new ApiProblemException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    executed == 0 ? "DDL_EXECUTION_FAILED" : "DDL_PARTIALLY_APPLIED",
                    message,
                    Map.of(
                            "executedStatements", List.copyOf(sql.subList(0, executed)),
                            "failedStatement", failedStatement
                    )
            );
            problem.initCause(error);
            throw problem;
        } finally {
            // DDL may auto-commit before a later statement fails, so all
            // dependent metadata must be treated as stale on every outcome.
            evictCache.run();
        }
    }

    private List<String> designSql(long connectionId, TableDesignRequest request) throws Exception {
        validateDesign(request);
        // Always compare the submitted design with live metadata. The browser
        // may have kept the designer open while another session changed the
        // table, and cached metadata must never turn a new column into a drop.
        ObjectDetail original = metadata.detail(connectionId, request.schemaName(), request.tableName(), true);
        if (request.structureVersion() == null || request.structureVersion().isBlank()) {
            throw new IllegalArgumentException("表结构设计缺少版本信息，请刷新对象后重新设计。");
        }
        if (!request.structureVersion().equals(original.structureVersion())) {
            throw new ApiProblemException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "STALE_TABLE_DESIGN",
                    "表结构已被其他会话修改，请刷新对象后重新设计。"
            );
        }
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().tableDesign()) {
            throw new IllegalStateException("当前数据库类型暂不支持表结构设计。请使用数据库原生工具执行 DDL。");
        }
        // 不支持注释的方言上，提交了注释就当场说清楚 —— 静默丢掉会让用户以为改成功了。
        if (!dialect.supportsColumnComments() && request.columns() != null
                && request.columns().stream().anyMatch(column -> column.remarks() != null && !column.deleted())) {
            throw new IllegalStateException("当前数据库类型不支持修改列注释。");
        }
        try (Connection connection = connections.open(connectionId)) {
            dialect.validateTableDesign(connection, original, request);
        }
        return dialect.alterTableSql(original.schemaName(), original.name(), original, request);
    }

    private void validateDesign(TableDesignRequest request) {
        validateIdentifier(request.tableName(), "表名");
        if (request.schemaName() != null && !request.schemaName().isBlank()) {
            validateIdentifier(request.schemaName(), "Schema");
        }
        if (request.columns() == null || request.columns().stream().noneMatch(column -> !column.deleted())) {
            throw new IllegalArgumentException("表设计至少需要保留一个字段。");
        }
        Set<String> columnNames = new LinkedHashSet<>();
        for (var column : request.columns()) {
            if (column.deleted()) continue;
            validateIdentifier(column.name(), "字段名");
            if (column.originalName() != null && !column.originalName().isBlank()) {
                validateIdentifier(column.originalName(), "原字段名");
            }
            if (column.type() == null || column.type().isBlank()) {
                throw new IllegalArgumentException("字段类型不能为空。");
            }
            validateSqlFragment(column.type(), "字段类型");
            if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
                validateSqlFragment(column.defaultValue(), "默认值");
            }
            if (!columnNames.add(column.name())) {
                throw new IllegalArgumentException("字段名不能重复：" + column.name());
            }
        }
        if (request.primaryKeys() != null) {
            Set<String> primaryKeyNames = new HashSet<>();
            for (String primaryKey : request.primaryKeys()) {
                validateIdentifier(primaryKey, "主键字段");
                if (!columnNames.contains(primaryKey)) {
                    throw new IllegalArgumentException("主键字段不存在：" + primaryKey);
                }
                if (!primaryKeyNames.add(primaryKey)) {
                    throw new IllegalArgumentException("主键字段不能重复：" + primaryKey);
                }
            }
        }
        if (request.indexes() != null) {
            Set<String> indexNames = new HashSet<>();
            Set<String> originalIndexNames = new HashSet<>();
            for (var index : request.indexes()) {
                if (index.originalName() != null && !index.originalName().isBlank()) {
                    validateIdentifier(index.originalName(), "原索引名");
                    if (!originalIndexNames.add(index.originalName())) {
                        throw new IllegalArgumentException("同一个原索引不能重复编辑：" + index.originalName());
                    }
                }
                if (index.deleted()) {
                    continue;
                }
                validateIdentifier(index.name(), "索引名");
                if (!indexNames.add(index.name())) {
                    throw new IllegalArgumentException("索引名不能重复：" + index.name());
                }
                if (index.columns() == null || index.columns().isEmpty()) {
                    throw new IllegalArgumentException("索引至少需要一个字段。");
                }
                Set<String> indexColumns = new HashSet<>();
                for (String column : index.columns()) {
                    validateIdentifier(column, "索引字段");
                    if (!columnNames.contains(column)) {
                        throw new IllegalArgumentException("索引字段不存在：" + column);
                    }
                    if (!indexColumns.add(column)) {
                        throw new IllegalArgumentException("索引字段不能重复：" + column);
                    }
                }
            }
        }
    }

    private void validateIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "只能包含字母、数字、下划线、$、#，且不能以数字开头：" + value);
        }
    }

    private void validateSqlFragment(String value, String label) {
        if (value.contains(";") || value.contains("--") || value.contains("/*") || value.contains("*/")) {
            throw new IllegalArgumentException(label + "包含不允许的 SQL 控制字符。");
        }
    }

    private String qualifiedName(String schemaName, String tableName) {
        return schemaName == null || schemaName.isBlank() ? tableName : schemaName + "." + tableName;
    }

    private record TableLifecyclePlan(
            TableLifecycleOperation operation,
            String schemaName,
            String tableName,
            String newTableName,
            String confirmationTarget,
            List<String> sql
    ) {
        private TableLifecyclePlan {
            sql = List.copyOf(sql);
        }
    }

    private enum TableLifecycleOperation {
        CREATE,
        RENAME,
        DROP;

        private static TableLifecycleOperation parse(String value) {
            try {
                return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException("不支持的表对象操作：" + value);
            }
        }
    }
}
