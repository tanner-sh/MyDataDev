package com.example.dbadmin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
            boolean readonly
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
            DatabaseCapabilities capabilities
    ) {
    }

    public record DatabaseCapabilities(
            boolean tableBrowse,
            boolean tableEdit,
            boolean tableDesign,
            boolean explain,
            List<String> nativeBackupMethods,
            List<String> nativeRestoreMethods,
            List<SchemaObjectCapability> schemaObjects
    ) {
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

    public record SchemaObjectCapability(String kind, List<String> operations) {
        public SchemaObjectCapability {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    public record TestConnectionRequest(@NotBlank @Size(max = 1000) String jdbcUrl, @Size(max = 240) String username, @Size(max = 10_000) String password) {
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

    public record ColumnDesign(@NotBlank String name, @NotBlank String type, Integer size, boolean nullable, String defaultValue, String originalName, boolean deleted) {
    }

    public record IndexDesign(@NotBlank String name, List<String> columns, boolean unique, String originalName, boolean deleted) {
    }

    public record TableDesignResponse(List<String> sql, String message) {
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

    public record SqlPageRequest(@NotNull Long connectionId, @NotBlank @Size(max = 2_000_000) String sql, Integer offset, Integer pageSize, @Size(max = 120) String executionId, @Size(max = 240) String schemaName) {
        public SqlPageRequest(Long connectionId, String sql, Integer offset, Integer pageSize, String executionId) {
            this(connectionId, sql, offset, pageSize, executionId, null);
        }
    }

    public record ResultColumn(String key, String label, String typeName) {
    }

    public record ResultSourceTable(List<String> nameParts) {
        public ResultSourceTable {
            nameParts = nameParts == null ? List.of() : List.copyOf(nameParts);
        }
    }

    public record SqlPageInfo(long connectionId, int offset, int requestedPageSize, int effectivePageSize, boolean hasMore, String schemaName) {
        public SqlPageInfo(long connectionId, int offset, int requestedPageSize, int effectivePageSize, boolean hasMore) {
            this(connectionId, offset, requestedPageSize, effectivePageSize, hasMore, null);
        }
    }

    public record SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet, int maxRows, boolean truncated, SqlPageInfo page, ResultSourceTable sourceTable) {
        public SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet, int maxRows, boolean truncated) {
            this(columns, rows, affectedRows, elapsedMs, resultSet, maxRows, truncated, null, null);
        }

        public SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet, int maxRows, boolean truncated, SqlPageInfo page) {
            this(columns, rows, affectedRows, elapsedMs, resultSet, maxRows, truncated, page, null);
        }

        public SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet) {
            this(columns, rows, affectedRows, elapsedMs, resultSet, 0, false, null, null);
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

    public record SqlHistoryResponse(long id, long connectionId, String sql, String type, String status, long elapsedMs, String errorMessage, String actor, String createdAt) {
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

    public record BackupTaskRequest(@NotBlank @Size(max = 120) String name, @NotNull Long connectionId, @NotBlank @Size(max = 20) String scope, @Size(max = 240) String schemaName, @Size(max = 240) String tableName, List<@Size(max = 240) String> tableNames, @Size(max = 120) String cron, boolean enabled, @Size(max = 40) String backupMethod, @Size(max = 1000) String toolPath, @Size(max = 100_000) String extraArgs, @Size(max = 1000) String nativeConnectName, Integer retentionDays, Integer retentionCount) {
        public BackupTaskRequest(String name, Long connectionId, String scope, String schemaName, String tableName, List<String> tableNames, String cron, boolean enabled, String backupMethod, String toolPath, String extraArgs, String nativeConnectName) {
            this(name, connectionId, scope, schemaName, tableName, tableNames, cron, enabled, backupMethod, toolPath, extraArgs, nativeConnectName, null, null);
        }

        public BackupTaskRequest(@NotBlank String name, @NotNull Long connectionId, @NotBlank String scope, String schemaName, String tableName, String cron, boolean enabled, String backupMethod, String toolPath, String extraArgs, String nativeConnectName) {
            this(name, connectionId, scope, schemaName, tableName, null, cron, enabled, backupMethod, toolPath, extraArgs, nativeConnectName, null, null);
        }

        public BackupTaskRequest(@NotBlank String name, @NotNull Long connectionId, @NotBlank String scope, String schemaName, String tableName, String cron, boolean enabled) {
            this(name, connectionId, scope, schemaName, tableName, null, cron, enabled, "SQL", null, null, null, null, null);
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

    public record CronPreviewRequest(@NotBlank String cron) {
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

    public record ActiveOperations(List<com.example.dbadmin.model.BackupHistory> backups, List<com.example.dbadmin.model.RestoreJob> restores) {
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
