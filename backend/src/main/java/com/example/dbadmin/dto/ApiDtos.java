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

    public record MetadataResponse(List<String> schemas, List<DbObject> objects) {
    }

    public record DbObject(String schemaName, String name, String type, List<ColumnInfo> columns, List<IndexInfo> indexes) {
    }

    public record ObjectDetail(String schemaName, String name, String type, List<ColumnInfo> columns, List<IndexInfo> indexes, List<String> primaryKeys, Long rowCount, String ddl) {
    }

    public record ColumnInfo(String name, String type, int size, boolean nullable, String remarks) {
    }

    public record IndexInfo(String name, String columnName, boolean unique) {
    }

    public record SqlRequest(@NotNull Long connectionId, @NotBlank String sql, Integer maxRows) {
    }

    public record SqlResult(List<String> columns, List<Map<String, Object>> rows, int affectedRows, long elapsedMs, boolean resultSet) {
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

    public record TableDataResponse(List<String> columns, List<Map<String, Object>> rows, List<String> keyColumns, boolean editable) {
    }

    public record DataCommitResponse(List<String> sql, int affectedRows) {
    }

    public record ExportRequest(@NotNull Long connectionId, @NotBlank String sql, @NotBlank String format) {
    }

    public record BackupTaskRequest(@NotBlank String name, @NotNull Long connectionId, @NotBlank String scope, String schemaName, String tableName, String cron, boolean enabled) {
    }
}
