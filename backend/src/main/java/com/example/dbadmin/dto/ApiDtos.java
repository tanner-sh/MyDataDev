package com.example.dbadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public final class ApiDtos {
    private ApiDtos() {
    }

    public record ConnectionRequest(
            @NotBlank String name,
            @NotBlank String dbType,
            @NotBlank String jdbcUrl,
            String username,
            String password,
            String environment,
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
            boolean readonly
    ) {
    }

    public record TestConnectionRequest(@NotBlank String jdbcUrl, String username, String password) {
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
            boolean cacheHit
    ) {
    }

    public record DbObject(String schemaName, String name, String type, List<ColumnInfo> columns, List<IndexInfo> indexes) {
    }

    public record ObjectStructure(String schemaName, String name, String type, List<ColumnInfo> columns, List<IndexInfo> indexes) {
    }

    public record ObjectDetail(String schemaName, String name, String type, List<ColumnInfo> columns, List<IndexInfo> indexes, List<String> primaryKeys, String primaryKeyName, Long rowCount, String ddl, String ddlSource) {
    }

    public record ObjectRelations(List<ObjectRelation> importedKeys, List<ObjectRelation> exportedKeys) {
    }

    public record ObjectRelation(String constraintName, String pkSchemaName, String pkTableName, String pkColumnName, String fkSchemaName, String fkTableName, String fkColumnName) {
    }

    public record ColumnInfo(String name, String type, int size, boolean nullable, String remarks, int ordinalPosition, String defaultValue) {
    }

    public record IndexInfo(String name, String columnName, boolean unique) {
    }

    public record TableDesignRequest(String schemaName, @NotBlank String tableName, List<ColumnDesign> columns, List<IndexDesign> indexes, List<String> primaryKeys, String confirmation) {
    }

    public record ColumnDesign(@NotBlank String name, @NotBlank String type, Integer size, boolean nullable, String defaultValue, String originalName, boolean deleted) {
    }

    public record IndexDesign(@NotBlank String name, List<String> columns, boolean unique, String originalName, boolean deleted) {
    }

    public record TableDesignResponse(List<String> sql, String message) {
    }

    public record SqlRequest(@NotNull Long connectionId, @NotBlank String sql, Integer maxRows) {
    }

    public record SqlResult(List<String> columns, List<Map<String, Object>> rows, int affectedRows, long elapsedMs, boolean resultSet, int maxRows, boolean truncated) {
        public SqlResult(List<String> columns, List<Map<String, Object>> rows, int affectedRows, long elapsedMs, boolean resultSet) {
            this(columns, rows, affectedRows, elapsedMs, resultSet, 0, false);
        }
    }

    public record SqlScriptRequest(@NotNull Long connectionId, @NotBlank String sql, Integer maxRows) {
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

    public record RowChange(@NotBlank String type, Map<String, Object> key, Map<String, Object> values) {
    }

    public record DataPreviewResponse(List<String> sql) {
    }

    public record TableDataResponse(List<String> columns, List<Map<String, Object>> rows, List<String> keyColumns, boolean editable, int page, int pageSize, boolean hasMore) {
        public TableDataResponse(List<String> columns, List<Map<String, Object>> rows, List<String> keyColumns, boolean editable) {
            this(columns, rows, keyColumns, editable, 0, rows == null ? 0 : rows.size(), false);
        }
    }

    public record DataCommitResponse(List<String> sql, int affectedRows) {
    }

    public record ExportRequest(@NotNull Long connectionId, @NotBlank String sql, @NotBlank String format) {
    }

    public record BackupTaskRequest(@NotBlank String name, @NotNull Long connectionId, @NotBlank String scope, String schemaName, String tableName, List<String> tableNames, String cron, boolean enabled, String backupMethod, String toolPath, String extraArgs, String nativeConnectName) {
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
            boolean hasMore
    ) {
    }

    public record CronPreviewRequest(@NotBlank String cron) {
    }

    public record CronPreviewResponse(String cron, String zoneId, List<String> nextRuns) {
    }

    public record BackupEnabledRequest(boolean enabled) {
    }
}
