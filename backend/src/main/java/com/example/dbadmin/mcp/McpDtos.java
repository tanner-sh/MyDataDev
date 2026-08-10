package com.example.dbadmin.mcp;

import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectRelation;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;

import java.util.List;
import java.util.Map;

public final class McpDtos {
    private McpDtos() {
    }

    public record ConnectionView(
            long connectionId,
            String name,
            String dbType,
            String environment,
            boolean readonly,
            boolean tableBrowse,
            boolean explain
    ) {
    }

    public record ConnectionList(List<ConnectionView> connections) {
    }

    public record NamespaceItem(String name, boolean current) {
    }

    public record NamespacePage(
            String namespaceKind,
            String currentNamespace,
            List<NamespaceItem> items,
            int page,
            int pageSize,
            boolean hasMore
    ) {
    }

    public record ObjectSummary(String schemaName, String name, String type) {
    }

    public record ObjectPage(
            String namespaceKind,
            String selectedSchema,
            List<ObjectSummary> items,
            int page,
            int pageSize,
            boolean hasMore,
            boolean totalExact,
            int total
    ) {
    }

    public record ObjectDescription(
            String schemaName,
            String name,
            String type,
            List<ColumnInfo> columns,
            List<IndexInfo> indexes,
            List<String> primaryKeys,
            String primaryKeyName,
            String structureVersion,
            List<ObjectRelation> importedKeys,
            List<ObjectRelation> exportedKeys
    ) {
    }

    public record ObjectDdl(String ddl, String source) {
    }

    public record TableColumnView(String name, String typeName, boolean nullable, boolean truncated) {
    }

    public record TablePage(
            List<TableColumnView> columns,
            List<Map<String, Object>> rows,
            String navigationMode,
            String nextCursor,
            boolean hasMore,
            boolean truncated
    ) {
    }

    public record QueryResult(
            List<ResultColumn> columns,
            List<List<Object>> rows,
            long elapsedMs,
            int maxRows,
            boolean truncated,
            String truncatedReason
    ) {
    }
}
