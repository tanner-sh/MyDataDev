package com.example.dbadmin.mcp;

import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectRelation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

public final class McpDtos {
    private McpDtos() {
    }

    public record ScriptJobResult(long taskId, long connectionId, String status, String phase,
                                  @Schema(nullable = true) Long statementTotal, long executedCount,
                                  @Schema(nullable = true) Long failedStatementIndex,
                                  String transactionMode, String endOfFileAction, String transactionOutcome,
                                  long commitCount, long rollbackCount,
                                  @Schema(nullable = true) Long lastCommitIndex,
                                  @Schema(nullable = true) Integer failedStartLine,
                                  @Schema(nullable = true) Integer failedEndLine,
                                  String message) { }

    public record ConnectionView(
            long connectionId,
            String name,
            String dbType,
            String environment,
            boolean readonly,
            boolean tableBrowse,
            boolean explain,
            /** 本 agent 在这条连接上的档位：READ_ONLY / DATA_WRITE / FULL。 */
            String accessLevel
    ) {
    }

    public record ConnectionList(List<ConnectionView> connections) {
    }

    public record NamespaceItem(String name, boolean current) {
    }

    public record NamespacePage(
            String namespaceKind,
            @Schema(nullable = true) String currentNamespace,
            List<NamespaceItem> items,
            int page,
            int pageSize,
            boolean hasMore
    ) {
    }

    public record ObjectSummary(@Schema(nullable = true) String schemaName, String name, String type) {
    }

    public record ObjectPage(
            String namespaceKind,
            @Schema(nullable = true) String selectedSchema,
            List<ObjectSummary> items,
            int page,
            int pageSize,
            boolean hasMore,
            boolean totalExact,
            int total
    ) {
    }

    public record ObjectDescription(
            @Schema(nullable = true) String schemaName,
            String name,
            String type,
            List<ColumnInfo> columns,
            List<IndexInfo> indexes,
            List<String> primaryKeys,
            @Schema(nullable = true) String primaryKeyName,
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
            @Schema(nullable = true) String nextCursor,
            boolean hasMore,
            boolean truncated
    ) {
    }

    /** MCP 保持原有稳定列格式，Web 专用的可选备注来源不进入严格输出 schema。 */
    public record QueryColumnView(String key, String label, String typeName) {
    }

    /** 写操作的结果。写语句通常没有结果集，受影响行数才是调用方要的东西。 */
    public record ExecuteResult(
            String statementKind,
            int updatedRows,
            long elapsedMs,
            List<QueryColumnView> columns,
            List<List<Object>> rows,
            boolean truncated
    ) {
    }

    public record QueryResult(
            List<QueryColumnView> columns,
            List<List<Object>> rows,
            long elapsedMs,
            int maxRows,
            boolean truncated,
            @Schema(nullable = true) String truncatedReason
    ) {
    }
}
