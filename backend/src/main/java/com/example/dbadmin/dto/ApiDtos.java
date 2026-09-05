package com.example.dbadmin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.time.Instant;

public final class ApiDtos {
    private ApiDtos() {
    }

    public record ConnectionRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 40) String dbType,
            @NotBlank @Size(max = 1000) String jdbcUrl,
            @Size(max = 240) String username,
            @Size(max = 10_000) String password,
            @Size(max = 40) String environment,
            boolean readonly,
            @Size(max = 120) String groupName,
            @Size(max = 500) String tags,
            @Size(max = 240) String defaultSchema,
            @Size(max = 4_000) String initSql,
            @Size(max = 1_000) String description,
            @Valid SshTunnelRequest ssh
    ) {
        /** 兼容不带连接档案字段的请求体（旧版前端、脚本调用）。 */
        public ConnectionRequest(String name, String dbType, String jdbcUrl, String username, String password,
                                 String environment, boolean readonly) {
            this(name, dbType, jdbcUrl, username, password, environment, readonly, null, null, null, null, null, null);
        }

        /** 兼容不带 SSH 隧道字段的请求体。 */
        public ConnectionRequest(String name, String dbType, String jdbcUrl, String username, String password,
                                 String environment, boolean readonly, String groupName, String tags,
                                 String defaultSchema, String initSql, String description) {
            this(name, dbType, jdbcUrl, username, password, environment, readonly, groupName, tags,
                    defaultSchema, initSql, description, null);
        }
    }

    /**
     * 连接编辑时提交的 SSH 隧道配置。
     *
     * <p>口令、私钥、私钥口令沿用数据库密码那套约定：{@code ******} 表示沿用已保存的值，
     * 空串表示清除。</p>
     */
    public record SshTunnelRequest(
            boolean enabled,
            @Size(max = 500) String host,
            Integer port,
            @Size(max = 240) String username,
            @Size(max = 20) String authMode,
            @Size(max = 10_000) String password,
            @Size(max = 100_000) String privateKey,
            @Size(max = 10_000) String passphrase,
            @Size(max = 200) String serverFingerprint,
            boolean skipHostKeyCheck
    ) {
    }

    /** 回显给界面的隧道配置：只说「有没有配」，不回传任何密文或明文密钥。 */
    public record SshTunnelSummary(
            boolean enabled,
            String host,
            int port,
            String username,
            String authMode,
            boolean hasPassword,
            boolean hasPrivateKey,
            boolean hasPassphrase,
            String serverFingerprint,
            boolean skipHostKeyCheck
    ) {
    }

    public record ConnectionResponse(
            long id,
            String name,
            String dbType,
            String jdbcUrl,
            String username,
            String environment,
            boolean readonly,
            String groupName,
            List<String> tags,
            String defaultSchema,
            String initSql,
            String description,
            SshTunnelSummary ssh,
            DatabaseCapabilities capabilities
    ) {
        /** 兼容不关心连接档案字段的构造点。 */
        public ConnectionResponse(long id, String name, String dbType, String jdbcUrl, String username,
                                  String environment, boolean readonly, DatabaseCapabilities capabilities) {
            this(id, name, dbType, jdbcUrl, username, environment, readonly, null, List.of(), null, null, null, null, capabilities);
        }
    }

    /**
     * @param columnComments 能不能改列注释。绝大多数关系库都能，所以七参构造按 true 处理；
     *                       SQLite（根本没有注释）和 SQL Server（要走扩展属性）显式关掉
     */
    public record DatabaseCapabilities(
            boolean tableBrowse,
            boolean tableEdit,
            boolean tableDesign,
            boolean explain,
            List<String> nativeBackupMethods,
            List<String> nativeRestoreMethods,
            List<SchemaObjectCapability> schemaObjects,
            boolean columnComments
    ) {
        public DatabaseCapabilities(boolean tableBrowse, boolean tableEdit, boolean tableDesign, boolean explain,
                                    List<String> nativeBackupMethods, List<String> nativeRestoreMethods,
                                    List<SchemaObjectCapability> schemaObjects) {
            this(tableBrowse, tableEdit, tableDesign, explain, nativeBackupMethods, nativeRestoreMethods, schemaObjects, true);
        }

        public DatabaseCapabilities(boolean tableBrowse, boolean tableEdit, boolean tableDesign, boolean explain, List<String> nativeBackupMethods, List<String> nativeRestoreMethods) {
            this(tableBrowse, tableEdit, tableDesign, explain, nativeBackupMethods, nativeRestoreMethods, List.of());
        }

        public DatabaseCapabilities(boolean tableBrowse, boolean tableEdit, boolean tableDesign, boolean explain, List<String> nativeBackupMethods) {
            this(tableBrowse, tableEdit, tableDesign, explain, nativeBackupMethods,
                    nativeBackupMethods == null ? List.of() : nativeBackupMethods.stream().map(method -> switch (method) {
                        case "MYSQLDUMP" -> "MYSQL";
                        case "ORACLE_EXP" -> "ORACLE_IMP";
                        default -> method;
                    }).toList(), List.of());
        }
    }

    /**
     * 全局搜索的一条命中。
     *
     * <p>{@code objectKey} 只有 schema 对象（视图/序列/触发器/存储过程/函数）才有，
     * 表和视图用 schema + name 定位，所以前端要按 kind 分派到不同的打开方式。</p>
     */
    public record ObjectSearchHit(
            String kind,
            String schemaName,
            String name,
            String displayName,
            String subtype,
            String objectKey
    ) {
    }

    public record ObjectSearchResponse(
            String namespaceKind,
            String schemaName,
            List<ObjectSearchHit> hits,
            boolean truncated
    ) {
    }

    public record SchemaObjectCapability(String kind, List<String> operations) {
        public SchemaObjectCapability {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    public record TestConnectionRequest(
            @NotBlank @Size(max = 1000) String jdbcUrl,
            @Size(max = 240) String username,
            @Size(max = 10_000) String password,
            @Valid SshTunnelRequest ssh
    ) {
        public TestConnectionRequest(String jdbcUrl, String username, String password) {
            this(jdbcUrl, username, password, null);
        }
    }

    public record MessageResponse(boolean ok, String message) {
    }

    public record MetadataResponse(
            List<String> schemas,
            String currentSchema,
            String selectedSchema,
            String namespaceKind,
            List<DbObject> objects,
            int totalObjects,
            boolean totalObjectsExact,
            int page,
            int pageSize,
            boolean hasMore,
            String cachedAt,
            boolean cacheHit
    ) {
    }

    public record CompletionCatalogResponse(
            String namespaceKind,
            String selectedSchema,
            List<DbObject> objects,
            String cachedAt,
            boolean cacheHit,
            boolean hasMore
    ) {
    }

    public record DbObject(String schemaName, String name, String type, List<ColumnInfo> columns, List<IndexInfo> indexes) {
    }

    public record ObjectStructure(String schemaName, String name, String type, List<ColumnInfo> columns, List<IndexInfo> indexes) {
    }

    public record ObjectDetail(String schemaName, String name, String type, List<ColumnInfo> columns, List<IndexInfo> indexes, List<String> primaryKeys, String primaryKeyName, String structureVersion) {
        public ObjectDetail(String schemaName, String name, String type, List<ColumnInfo> columns, List<IndexInfo> indexes, List<String> primaryKeys, String primaryKeyName) {
            this(schemaName, name, type, columns, indexes, primaryKeys, primaryKeyName, null);
        }
    }

    public record ObjectDdlResponse(String ddl, String source) {
    }

    public record ObjectRowCountResponse(Long value, boolean exact, long elapsedMs) {
    }

    public record ObjectRelations(List<ObjectRelation> importedKeys, List<ObjectRelation> exportedKeys) {
    }

    public record SchemaObjectSummary(
            String objectKey,
            String schemaName,
            String name,
            String displayName,
            String kind,
            String subtype,
            String status
    ) {
    }

    public record SchemaObjectPage(
            List<SchemaObjectSummary> items,
            int total,
            boolean totalExact,
            int page,
            int pageSize,
            boolean hasMore,
            String cachedAt,
            boolean cacheHit
    ) {
    }

    public record SchemaObjectParameter(
            int position,
            String name,
            String mode,
            String typeName,
            Integer jdbcType,
            boolean nullable
    ) {
    }

    public record SchemaObjectDependency(String schemaName, String name, String kind, String direction) {
    }

    public record SchemaObjectDetail(
            SchemaObjectSummary object,
            String source,
            boolean sourceAvailable,
            String sourceUnavailableReason,
            List<SchemaObjectParameter> parameters,
            List<SchemaObjectDependency> dependencies,
            boolean dependenciesAvailable,
            String dependenciesUnavailableReason,
            String structureVersion,
            List<String> operations,
            Map<String, Object> properties
    ) {
    }

    public record SchemaObjectTemplateResponse(String kind, String schemaName, String objectName, String source) {
    }

    public record SchemaObjectLifecycleRequest(
            @NotBlank @Size(max = 20) String operation,
            @NotBlank @Size(max = 40) String kind,
            @Size(max = 240) String schemaName,
            @NotBlank @Size(max = 240) String objectName,
            @Size(max = 4_000) String objectKey,
            @Size(max = 2_000_000) String source,
            @Size(max = 200) String structureVersion,
            @Size(max = 520) String confirmation
    ) {
    }

    public record SchemaObjectLifecycleResponse(List<String> sql, String message) {
    }

    public record RoutineArgumentInput(@Min(0) int position, @Size(max = 240) String name, @Size(max = 1_000_000) String value, boolean nullValue) {
    }

    public record RoutineInvokeRequest(
            @NotBlank @Size(max = 4_000) String objectKey,
            @NotBlank @Size(max = 200) String structureVersion,
            @Size(max = 256) List<@Valid RoutineArgumentInput> arguments
    ) {
    }

    public record RoutineOutParameter(String name, String typeName, Object value) {
    }

    public record RoutineResultItem(String kind, SqlResult result, Integer updateCount) {
    }

    public record RoutineInvokeResponse(
            String status,
            long elapsedMs,
            Object returnValue,
            List<RoutineOutParameter> outParameters,
            List<RoutineResultItem> results,
            boolean truncated
    ) {
    }

    public record ObjectRelation(String constraintName, String pkSchemaName, String pkTableName, String pkColumnName, String fkSchemaName, String fkTableName, String fkColumnName) {
    }

    public record ColumnInfo(String name, String type, int size, boolean nullable, String remarks, int ordinalPosition, String defaultValue) {
    }

    public record IndexInfo(String name, String columnName, boolean unique, int ordinalPosition) {
        public IndexInfo(String name, String columnName, boolean unique) {
            this(name, columnName, unique, 0);
        }
    }

    public record TableDesignRequest(String schemaName, @NotBlank String tableName, List<ColumnDesign> columns, List<IndexDesign> indexes, List<String> primaryKeys, String structureVersion, String confirmation) {
        public TableDesignRequest(String schemaName, String tableName, List<ColumnDesign> columns, List<IndexDesign> indexes, List<String> primaryKeys, String confirmation) {
            this(schemaName, tableName, columns, indexes, primaryKeys, null, confirmation);
        }
    }

    /** @param remarks 列注释；{@code null} 表示这次不改注释，空串表示清空 */
    public record ColumnDesign(@NotBlank String name, @NotBlank String type, Integer size, boolean nullable,
                               String defaultValue, String originalName, boolean deleted,
                               @Size(max = 1000) String remarks) {
        public ColumnDesign(String name, String type, Integer size, boolean nullable, String defaultValue,
                            String originalName, boolean deleted) {
            this(name, type, size, nullable, defaultValue, originalName, deleted, null);
        }
    }

    public record IndexDesign(@NotBlank String name, List<String> columns, boolean unique, String originalName, boolean deleted) {
    }

    public record TableDesignResponse(List<String> sql, String message) {
    }

    /**
     * 结构对比请求：把 target 的结构对齐到 source。
     *
     * <p>{@code tables} 为空表示比较两侧的全部表；{@code includeDrops} 决定迁移脚本里要不要
     * 包含删除目标端多余对象的语句 —— 默认不包含，因为「对方多出来的东西」往往是有意为之。</p>
     */
    public record SchemaDiffRequest(
            @NotNull Long sourceConnectionId,
            @Size(max = 240) String sourceSchema,
            @NotNull Long targetConnectionId,
            @Size(max = 240) String targetSchema,
            List<@Size(max = 240) String> tables,
            boolean includeDrops
    ) {
    }

    public record SchemaDiffEndpoint(long connectionId, String connectionName, String dbType, String schemaName) {
    }

    /** 一处差异。{@code change} 取 ADDED / REMOVED / CHANGED，方向都以 source 为准。 */
    public record SchemaDiffItem(String category, String name, String change, String source, String target) {
    }

    public record SchemaDiffTable(String tableName, String status, List<SchemaDiffItem> items, List<String> migration) {
    }

    public record SchemaDiffSummary(int onlyInSource, int onlyInTarget, int different, int identical) {
    }

    public record SchemaDiffResponse(
            SchemaDiffEndpoint source,
            SchemaDiffEndpoint target,
            SchemaDiffSummary summary,
            List<SchemaDiffTable> tables,
            List<String> migration,
            List<String> warnings
    ) {
    }

    /**
     * 两张表的逐行数据对比。
     *
     * @param targetTable 留空表示与源表同名
     * @param keyColumns 用于匹配行的字段；留空时用目标表（其次源表）的主键
     * @param includeDeletes 是否为「只在目标端存在」的行生成 DELETE。默认不生成 —— 那些行往往是
     *                       目标库自己的数据，默认删等于把一次对比变成危险操作
     */
    public record DataDiffRequest(
            @NotNull Long sourceConnectionId,
            @Size(max = 240) String sourceSchema,
            @NotBlank @Size(max = 240) String sourceTable,
            @NotNull Long targetConnectionId,
            @Size(max = 240) String targetSchema,
            @Size(max = 240) String targetTable,
            @Size(max = 20) List<@Size(max = 240) String> keyColumns,
            boolean includeDeletes
    ) {
    }

    /** {@code change} 取 ONLY_IN_SOURCE / ONLY_IN_TARGET / DIFFERENT，方向都以 source 为准。 */
    public record DataDiffRow(
            List<String> key,
            String change,
            List<String> columns,
            List<String> sourceValues,
            List<String> targetValues
    ) {
    }

    public record DataDiffSummary(int onlyInSource, int onlyInTarget, int different, int identical) {
    }

    /** @param truncated 差异条数触顶：脚本只覆盖了前一部分，界面必须说清楚 */
    public record DataDiffResponse(
            SchemaDiffEndpoint source,
            SchemaDiffEndpoint target,
            String sourceTable,
            String targetTable,
            List<String> keyColumns,
            List<String> columns,
            DataDiffSummary summary,
            List<DataDiffRow> rows,
            List<String> script,
            boolean truncated,
            List<String> warnings
    ) {
    }

    /**
     * 一次备份校验的结果。
     *
     * <p>{@code checksumMatches} 是唯一的硬结论：SHA-256 与备份当时记下的一致，就说明这份文件
     * 与写出来那一刻逐字节相同（远端存储的话，顺带证明它还取得回来）。{@code looksComplete}
     * 只是文本备份尾部的形状提示，各家 dump 的收尾写法不完全一致，不作为成败判据。</p>
     */
    public record BackupVerificationResponse(
            long historyId,
            boolean readable,
            boolean checksumMatches,
            boolean sizeMatches,
            Boolean looksComplete,
            long bytesRead,
            Long recordedSize,
            String checksum,
            String recordedChecksum,
            long elapsedMs,
            String message
    ) {
    }

    public record TableLifecycleRequest(
            @NotBlank @Size(max = 20) String operation,
            @Size(max = 240) String schemaName,
            @NotBlank @Size(max = 240) String tableName,
            @Size(max = 240) String newTableName,
            List<ColumnDesign> columns,
            List<IndexDesign> indexes,
            List<String> primaryKeys,
            String structureVersion,
            String confirmation
    ) {
    }

    public record SqlRequest(@NotNull Long connectionId, @NotBlank @Size(max = 2_000_000) String sql, Integer maxRows, @Size(max = 120) String executionId, @Size(max = 240) String schemaName, boolean unscopedMutationConfirmed) {
        public SqlRequest(Long connectionId, String sql, Integer maxRows, String executionId) {
            this(connectionId, sql, maxRows, executionId, null, false);
        }
    }

    /**
     * 结果网格的一个列筛选。
     *
     * <p>语义与前端一致（按文本、忽略大小写、NULL 等同空串），所以下推之后用户看到的行为不变，
     * 变的只是它作用在整个结果集上而不是当前这一批。</p>
     *
     * @param operator contains / notContains / equals / notEquals / empty / notEmpty
     */
    public record SqlResultFilter(
            @NotBlank @Size(max = 128) String column,
            @NotBlank @Size(max = 20) String operator,
            @Size(max = 2000) String value
    ) {
    }

    /**
     * @param sortColumn 结果集里的列标签；服务端把排序下推进 SQL 再分页，而不是让界面只排当前这一页
     * @param sortDirection {@code ASC} 或 {@code DESC}，空值按升序
     * @param filters 列筛选，同样下推 —— 只筛当前这一批会让「筛选后 3 行」这个数字失去意义
     */
    public record SqlPageRequest(
            @NotNull Long connectionId,
            @NotBlank @Size(max = 2_000_000) String sql,
            Integer offset,
            Integer pageSize,
            @Size(max = 120) String executionId,
            @Size(max = 240) String schemaName,
            @Size(max = 128) String sortColumn,
            @Size(max = 8) String sortDirection,
            @Size(max = 30) List<@jakarta.validation.Valid SqlResultFilter> filters
    ) {
        public SqlPageRequest {
            filters = filters == null ? List.of() : List.copyOf(filters);
        }

        public SqlPageRequest(Long connectionId, String sql, Integer offset, Integer pageSize, String executionId) {
            this(connectionId, sql, offset, pageSize, executionId, null, null, null, List.of());
        }

        public SqlPageRequest(Long connectionId, String sql, Integer offset, Integer pageSize,
                              String executionId, String schemaName) {
            this(connectionId, sql, offset, pageSize, executionId, schemaName, null, null, List.of());
        }
    }

    public record ResultColumn(String key, String label, String typeName) {
    }

    public record ResultSourceTable(List<String> nameParts) {
        public ResultSourceTable {
            nameParts = nameParts == null ? List.of() : List.copyOf(nameParts);
        }
    }

    /**
     * 查询结果能否就地编辑。
     *
     * <p>只有「单表来源 + 该表有稳定行定位字段 + 这些字段都在结果集里」三条同时成立才可编辑；
     * 不成立时 {@code reason} 说明差在哪，界面据此给出人能看懂的解释而不是把功能藏起来。</p>
     */
    public record ResultEditInfo(
            boolean editable,
            String schemaName,
            String tableName,
            List<String> keyColumns,
            List<String> rowKeyTokens,
            String reason
    ) {
        public ResultEditInfo {
            keyColumns = keyColumns == null ? List.of() : List.copyOf(keyColumns);
            rowKeyTokens = rowKeyTokens == null ? List.of() : List.copyOf(rowKeyTokens);
        }

        public static ResultEditInfo notEditable(String reason) {
            return new ResultEditInfo(false, null, null, List.of(), List.of(), reason);
        }
    }

    /**
     * {@code sortColumn} 与 {@code filters} 回显当前生效的排序和筛选，界面据此在正确的列头上画
     * 箭头和漏斗 —— 翻页后它们不能丢。
     */
    public record SqlPageInfo(
            long connectionId,
            int offset,
            int requestedPageSize,
            int effectivePageSize,
            boolean hasMore,
            String schemaName,
            String sortColumn,
            String sortDirection,
            List<SqlResultFilter> filters
    ) {
        public SqlPageInfo {
            filters = filters == null ? List.of() : List.copyOf(filters);
        }

        public SqlPageInfo(long connectionId, int offset, int requestedPageSize, int effectivePageSize, boolean hasMore) {
            this(connectionId, offset, requestedPageSize, effectivePageSize, hasMore, null, null, null, List.of());
        }

        public SqlPageInfo(long connectionId, int offset, int requestedPageSize, int effectivePageSize,
                           boolean hasMore, String schemaName) {
            this(connectionId, offset, requestedPageSize, effectivePageSize, hasMore, schemaName, null, null, List.of());
        }
    }

    public record SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet, int maxRows, boolean truncated, SqlPageInfo page, ResultSourceTable sourceTable, ResultEditInfo edit) {
        public SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet, int maxRows, boolean truncated) {
            this(columns, rows, affectedRows, elapsedMs, resultSet, maxRows, truncated, null, null, null);
        }

        public SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet, int maxRows, boolean truncated, SqlPageInfo page) {
            this(columns, rows, affectedRows, elapsedMs, resultSet, maxRows, truncated, page, null, null);
        }

        public SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet, int maxRows, boolean truncated, SqlPageInfo page, ResultSourceTable sourceTable) {
            this(columns, rows, affectedRows, elapsedMs, resultSet, maxRows, truncated, page, sourceTable, null);
        }

        public SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet) {
            this(columns, rows, affectedRows, elapsedMs, resultSet, 0, false, null, null, null);
        }
    }

    public record SqlScriptRequest(@NotNull Long connectionId, @NotBlank @Size(max = 2_000_000) String sql, Integer maxRows, Integer pageSize, @Size(max = 120) String executionId, @Size(max = 240) String schemaName, boolean unscopedMutationConfirmed) {
        public SqlScriptRequest(Long connectionId, String sql, Integer maxRows, Integer pageSize, String executionId) {
            this(connectionId, sql, maxRows, pageSize, executionId, null, false);
        }
    }

    public record SqlScriptResponse(String status, long elapsedMs, int executedCount, List<SqlStatementResult> results, boolean metadataChanged) {
        public SqlScriptResponse(String status, long elapsedMs, int executedCount, List<SqlStatementResult> results) {
            this(status, elapsedMs, executedCount, results, false);
        }
    }

    public record SqlStatementResult(int index, String sql, int startOffset, int endOffset, String status, String errorMessage, SqlResult result) {
    }

    public record SqlHistoryResponse(long id, long connectionId, String sql, String type, String status, long elapsedMs, String errorMessage, String actor, Long actorUserId, String createdAt) {
        public SqlHistoryResponse(long id, long connectionId, String sql, String type, String status, long elapsedMs,
                                  String errorMessage, String actor, String createdAt) {
            this(id, connectionId, sql, type, status, elapsedMs, errorMessage, actor, null, createdAt);
        }
    }

    public record SqlFileExecutionStartRequest(String productionConfirmation) {
    }

    public record SqlFileExecutionResponse(
            long id,
            long connectionId,
            String connectionName,
            String targetDbType,
            String fileName,
            long fileSize,
            String checksumSha256,
            String detectedCharset,
            String status,
            String phase,
            long processedBytes,
            Long statementTotal,
            long statementCurrent,
            long queryCount,
            long mutationCount,
            long ddlCount,
            long unknownCount,
            long successCount,
            long queryRowCount,
            Long failedStatementIndex,
            String failedSqlPreview,
            String message,
            boolean metadataChanged,
            boolean sessionChanged,
            boolean cancelRequested,
            Instant expiresAt,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt
    ) {
    }

    public record SqlFileExecutionPage(List<SqlFileExecutionResponse> items, int page, int pageSize, boolean hasMore) {
    }

    public record SqlCompletionRequest(@NotNull Long connectionId, @Size(max = 2_000_000) String sql, Integer cursorPosition) {
    }

    public record SqlCompletionItem(String label, String kind, String insertText, String detail) {
    }

    public record FormatRequest(@NotBlank @Size(max = 2_000_000) String sql) {
    }

    public record FormatResponse(String sql) {
    }

    public record DataPreviewRequest(@NotNull Long connectionId, String schemaName, @NotBlank String tableName, List<RowChange> changes) {
    }

    public record RowChange(@NotBlank String type, Map<String, Object> key, Map<String, Object> values, Map<String, Object> originalValues, String keyToken) {
        public RowChange(String type, Map<String, Object> key, Map<String, Object> values, Map<String, Object> originalValues) {
            this(type, key, values, originalValues, null);
        }

        public RowChange(String type, Map<String, Object> key, Map<String, Object> values) {
            this(type, key, values, null, null);
        }
    }

    public record DataPreviewResponse(List<String> sql) {
    }

    public record TableDataRequest(
            @NotNull Long connectionId,
            @Size(max = 240) String schemaName,
            @NotBlank @Size(max = 240) String tableName,
            @Size(max = 8_192) String cursor,
            @Min(1) @Max(200) Integer pageSize,
            @Size(max = 20) List<@NotNull @Valid TableFilterRule> filters,
            @Size(max = 10) List<@NotNull @Valid TableSortRule> sorts,
            @Size(max = 3) String filterLogic
    ) {
    }

    /** 表数据导出：沿用浏览的查询条件，加一个导出格式。 */
    /**
     * SQL 执行的统计视角。
     *
     * <p>耗时、状态、执行人本来就记在 sql_history 里，只是从来没被聚合过 ——「哪条最慢」
     * 「哪条一直在失败」此前只能靠人一页页翻列表。</p>
     */
    public record SqlHistoryStats(
            int days,
            SqlHistorySummary summary,
            List<SqlHistoryResponse> slowest,
            List<SqlHistoryGroup> failures,
            List<SqlHistoryGroup> busiest
    ) {
    }

    public record SqlHistorySummary(int total, int failed, long averageMs, long slowestMs) {
    }

    /** @param text 分组依据：失败排行里是 SQL 原文，执行人排行里是用户名 */
    public record SqlHistoryGroup(String text, int hits, long averageMs, String lastSeenAt) {
    }

    public record TableExportRequest(
            @NotNull @Valid TableDataRequest query,
            @NotBlank @Size(max = 20) String format
    ) {
    }

    public record TableFilterRule(
            @NotBlank @Size(max = 240) String column,
            @NotBlank @Size(max = 24) String operator,
            @Size(max = 10_000) String value,
            @Size(max = 10_000) String secondValue,
            @Size(max = 100) List<@NotNull @Size(max = 10_000) String> values
    ) {
    }

    public record TableSortRule(
            @NotBlank @Size(max = 240) String column,
            @NotBlank @Size(max = 4) String direction
    ) {
    }

    public record TableColumn(String name, String typeName, int jdbcType, boolean nullable, boolean editable, boolean truncated) {
        public TableColumn(String name, String typeName, int jdbcType, boolean nullable, boolean editable) {
            this(name, typeName, jdbcType, nullable, editable, false);
        }

        public TableColumn(String name, String typeName, int jdbcType, boolean nullable) {
            this(name, typeName, jdbcType, nullable, true, false);
        }
    }

    public record TableDataResponse(
            List<TableColumn> columns,
            List<Map<String, Object>> rows,
            List<String> rowKeyTokens,
            List<String> keyColumns,
            boolean editable,
            String navigationMode,
            String nextCursor,
            boolean hasMore
    ) {
        public TableDataResponse(
                List<TableColumn> columns,
                List<Map<String, Object>> rows,
                List<String> keyColumns,
                boolean editable,
                String navigationMode,
                String nextCursor,
                boolean hasMore
        ) {
            this(columns, rows, List.of(), keyColumns, editable, navigationMode, nextCursor, hasMore);
        }
    }

    public record DataCommitResponse(List<String> sql, int affectedRows) {
    }

    public record ExportRequest(@NotNull Long connectionId, @NotBlank @Size(max = 2_000_000) String sql, @NotBlank @Size(max = 10) String format, @Size(max = 240) String schemaName, @Size(max = 3) List<@NotBlank @Size(max = 240) String> targetTableParts) {
        public ExportRequest(Long connectionId, String sql, String format) {
            this(connectionId, sql, format, null, null);
        }
    }

    public record BackupTaskRequest(@NotBlank @Size(max = 120) String name, @NotNull Long connectionId, @NotBlank @Size(max = 20) String scope, @Size(max = 240) String schemaName, @Size(max = 240) String tableName, List<@Size(max = 240) String> tableNames, @Size(max = 120) String cron, boolean enabled, @Size(max = 40) String backupMethod, @Size(max = 1000) String toolPath, @Size(max = 100_000) String extraArgs, @Size(max = 1000) String nativeConnectName, Integer retentionDays, Integer retentionCount, Long storageProfileId, @Size(max = 80) String scheduleZone) {
        public BackupTaskRequest(String name, Long connectionId, String scope, String schemaName, String tableName, List<String> tableNames, String cron, boolean enabled, String backupMethod, String toolPath, String extraArgs, String nativeConnectName, Integer retentionDays, Integer retentionCount, Long storageProfileId) {
            this(name, connectionId, scope, schemaName, tableName, tableNames, cron, enabled, backupMethod, toolPath, extraArgs, nativeConnectName, retentionDays, retentionCount, storageProfileId, null);
        }

        public BackupTaskRequest(String name, Long connectionId, String scope, String schemaName, String tableName, List<String> tableNames, String cron, boolean enabled, String backupMethod, String toolPath, String extraArgs, String nativeConnectName, Integer retentionDays, Integer retentionCount) {
            this(name, connectionId, scope, schemaName, tableName, tableNames, cron, enabled, backupMethod, toolPath, extraArgs, nativeConnectName, retentionDays, retentionCount, null);
        }

        public BackupTaskRequest(String name, Long connectionId, String scope, String schemaName, String tableName, List<String> tableNames, String cron, boolean enabled, String backupMethod, String toolPath, String extraArgs, String nativeConnectName) {
            this(name, connectionId, scope, schemaName, tableName, tableNames, cron, enabled, backupMethod, toolPath, extraArgs, nativeConnectName, null, null, null);
        }

        public BackupTaskRequest(@NotBlank String name, @NotNull Long connectionId, @NotBlank String scope, String schemaName, String tableName, String cron, boolean enabled, String backupMethod, String toolPath, String extraArgs, String nativeConnectName) {
            this(name, connectionId, scope, schemaName, tableName, null, cron, enabled, backupMethod, toolPath, extraArgs, nativeConnectName, null, null, null);
        }

        public BackupTaskRequest(@NotBlank String name, @NotNull Long connectionId, @NotBlank String scope, String schemaName, String tableName, String cron, boolean enabled) {
            this(name, connectionId, scope, schemaName, tableName, null, cron, enabled, "SQL", null, null, null, null, null, null);
        }
    }

    public record BackupTargetItem(String name, boolean current) {
    }

    public record BackupTargetPage(
            String namespaceKind,
            String currentNamespace,
            String namespaceName,
            List<BackupTargetItem> items,
            int total,
            int page,
            int pageSize,
            boolean hasMore,
            boolean totalExact
    ) {
        public BackupTargetPage(
                String namespaceKind,
                String currentNamespace,
                String namespaceName,
                List<BackupTargetItem> items,
                int total,
                int page,
                int pageSize,
                boolean hasMore
        ) {
            this(namespaceKind, currentNamespace, namespaceName, items, total, page, pageSize, hasMore, true);
        }
    }

    public record CronPreviewRequest(@NotBlank String cron, @Size(max = 80) String zoneId) {
        public CronPreviewRequest(String cron) {
            this(cron, null);
        }

    }

    public record CronPreviewResponse(String cron, String zoneId, List<String> nextRuns) {
    }

    public record BackupEnabledRequest(boolean enabled) {
    }

    public record BackupHistoryPage(List<com.example.dbadmin.model.BackupHistory> items, int page, int pageSize, boolean hasMore) {
    }

    public record BackupRunResponse(com.example.dbadmin.model.BackupTask task, com.example.dbadmin.model.BackupHistory execution) {
    }

    public record BackupTaskPage(List<com.example.dbadmin.model.BackupTask> items, int page, int pageSize, boolean hasMore) {
    }

    public record DatabaseSession(
            String sessionId,
            String user,
            String host,
            String database,
            String state,
            String command,
            Long durationSeconds,
            String sql
    ) {
    }

    /** {@code supported} 区分「方言不支持」与「支持但这次读失败」，界面文案不同。 */
    public record DatabaseSessionPage(
            boolean supported,
            boolean canKill,
            List<DatabaseSession> sessions,
            String message
    ) {
        public DatabaseSessionPage {
            sessions = sessions == null ? List.of() : List.copyOf(sessions);
        }
    }

    public record SqlTransactionResponse(
            String id,
            long connectionId,
            String schemaName,
            String startedAt,
            String lastUsedAt,
            int statementCount,
            int idleTimeoutSeconds
    ) {
    }

    public record SqlTransactionScriptResponse(
            SqlTransactionResponse transaction,
            String status,
            long elapsedMs,
            int executedCount,
            List<SqlStatementResult> results,
            boolean metadataChanged
    ) {
    }

    public record SqlTransactionBeginRequest(@NotNull Long connectionId, @Size(max = 240) String schemaName) {
    }

    public record SqlTransactionExecuteRequest(
            @NotBlank @Size(max = 2_000_000) String sql,
            Integer maxRows,
            boolean unscopedMutationConfirmed
    ) {
    }

    public record SqlSnippetResponse(
            long id,
            String name,
            String description,
            String sql,
            String dbType,
            String tags,
            long useCount,
            String lastUsedAt,
            String actor,
            String updatedAt,
            String visibility,
            Long ownerUserId,
            boolean editable
    ) {
    }

    public record SqlSnippetRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotBlank @Size(max = 200_000) String sql,
            @Size(max = 40) String dbType,
            @Size(max = 500) String tags,
            @jakarta.validation.constraints.Pattern(regexp = "PERSONAL|SHARED", message = "片段范围只支持 PERSONAL 或 SHARED") String visibility
    ) {
        public SqlSnippetRequest(String name, String description, String sql, String dbType, String tags) {
            this(name, description, sql, dbType, tags, null);
        }
    }

    public record AuditEventResponse(
            long id,
            String actor,
            String action,
            String target,
            String detail,
            boolean detailTruncated,
            String remoteAddress,
            String forwardedFor,
            String userAgent,
            String requestId,
            String createdAt
    ) {
    }

    public record AuditEventPage(List<AuditEventResponse> items, int page, int pageSize, boolean hasMore) {
    }

    /** 过滤下拉的候选值，来自实际写入过的记录而不是硬编码枚举。 */
    public record AuditFacets(List<String> actors, List<String> actions) {
    }

    public record RestoreSourceRef(@NotBlank String kind, @NotNull Long id) {
    }

    public record RestorePreflightRequest(
            @NotNull RestoreSourceRef source,
            @NotNull Long targetConnectionId,
            @NotBlank @Size(max = 40) String sourceDbType,
            @NotBlank @Size(max = 40) String fileFormat,
            @NotBlank @Size(max = 20) String conflictMode,
            Map<String, String> namespaceMapping,
            @Size(max = 1000) String toolPath,
            @Size(max = 100_000) String extraArgs
    ) {
    }

    public record RestorePreflightResponse(
            boolean valid,
            String planToken,
            String fileFormat,
            String sourceDbType,
            String targetDbType,
            long statementCount,
            List<String> namespaces,
            List<String> tables,
            List<String> warnings,
            List<String> errors
    ) {
    }

    public record RestoreStartRequest(
            @NotBlank String planToken,
            @NotNull RestoreSourceRef source,
            @NotNull Long targetConnectionId,
            @NotBlank String sourceDbType,
            @NotBlank String fileFormat,
            @NotBlank String conflictMode,
            Map<String, String> namespaceMapping,
            String toolPath,
            String extraArgs,
            String productionConfirmation
    ) {
    }

    public record RestoreJobPage(List<com.example.dbadmin.model.RestoreJob> items, int page, int pageSize, boolean hasMore) {
    }

    /**
     * Every background job still in flight for one connection. The three kinds
     * are returned together so a client that only needs "is anything running"
     * — such as the header indicator — costs a single request.
     */
    public record ActiveOperations(
            List<com.example.dbadmin.model.BackupHistory> backups,
            List<com.example.dbadmin.model.RestoreJob> restores,
            List<com.example.dbadmin.model.SqlFileExecution> sqlFiles
    ) {
    }

    public record NativeToolStatus(
            String tool,
            String displayName,
            boolean available,
            String resolvedPath,
            String version,
            String source,
            String message
    ) {
    }

    public record NativeToolsResponse(String detectedAt, List<NativeToolStatus> tools) {
    }
}
