package com.example.dbadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

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
            List<String> nativeBackupMethods
    ) {
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

    public record SqlRequest(@NotNull Long connectionId, @NotBlank String sql, Integer maxRows, String executionId) {
    }

    public record ResultColumn(String key, String label, String typeName) {
    }

    public record SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet, int maxRows, boolean truncated) {
        public SqlResult(List<ResultColumn> columns, List<List<Object>> rows, int affectedRows, long elapsedMs, boolean resultSet) {
            this(columns, rows, affectedRows, elapsedMs, resultSet, 0, false);
        }
    }

    public record SqlScriptRequest(@NotNull Long connectionId, @NotBlank String sql, Integer maxRows, String executionId) {
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

    public record SqlCompletionRequest(@NotNull Long connectionId, String sql, Integer cursorPosition) {
    }

    public record SqlCompletionItem(String label, String kind, String insertText, String detail) {
    }

    public record FormatRequest(@NotBlank String sql) {
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

    public record ExportRequest(@NotNull Long connectionId, @NotBlank String sql, @NotBlank String format) {
    }

    public record BackupTaskRequest(@NotBlank @Size(max = 120) String name, @NotNull Long connectionId, @NotBlank @Size(max = 20) String scope, @Size(max = 240) String schemaName, @Size(max = 240) String tableName, List<@Size(max = 240) String> tableNames, @Size(max = 120) String cron, boolean enabled, @Size(max = 40) String backupMethod, @Size(max = 1000) String toolPath, @Size(max = 100_000) String extraArgs, @Size(max = 1000) String nativeConnectName) {
        public BackupTaskRequest(@NotBlank String name, @NotNull Long connectionId, @NotBlank String scope, String schemaName, String tableName, String cron, boolean enabled, String backupMethod, String toolPath, String extraArgs, String nativeConnectName) {
            this(name, connectionId, scope, schemaName, tableName, null, cron, enabled, backupMethod, toolPath, extraArgs, nativeConnectName);
        }

        public BackupTaskRequest(@NotBlank String name, @NotNull Long connectionId, @NotBlank String scope, String schemaName, String tableName, String cron, boolean enabled) {
            this(name, connectionId, scope, schemaName, tableName, null, cron, enabled, "SQL", null, null, null);
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
}
