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

    public record ScriptJobPage(List<ScriptJobResult> items, int page, int pageSize, boolean hasMore) { }

    public record ScriptJobResult(long taskId, long connectionId, String status, String phase,
                                  @Schema(nullable = true) Long statementTotal, long executedCount,
                                  @Schema(nullable = true) Long failedStatementIndex,
                                  String transactionMode, String endOfFileAction, String transactionOutcome,
                                  long commitCount, long rollbackCount,
                                  @Schema(nullable = true) Long lastCommitIndex,
                                  @Schema(nullable = true) Integer failedStartLine,
                                  @Schema(nullable = true) Integer failedEndLine,
                                  String message,
                                  /** 正在执行（或最后执行到）的语句序号，从 1 开始。 */
                                  long currentStatementIndex,
                                  @Schema(nullable = true) String startedAt,
                                  @Schema(nullable = true) String finishedAt,
                                  /** 已运行时长；未开始为 null，进行中按当前时间计。 */
                                  @Schema(nullable = true) Long elapsedMs,
                                  /** 语句成功但对象编译失败（Oracle 系 INVALID）的明细。 */
                                  List<String> compilationErrors) { }

    public record ConnectionView(
            long connectionId,
            String name,
            String dbType,
            String environment,
            boolean readonly,
            boolean tableBrowse,
            boolean explain,
            /** 本 agent 在这条连接上的档位：READ_ONLY / DATA_WRITE / FULL。 */
            String accessLevel,
            /** 同连接后台脚本并发上限（所有 agent 与界面 SQL 文件任务共享）。 */
            int scriptConcurrencyLimit,
            /** 该连接上排队或运行中的脚本任务数；等于上限时 db_execute_script 会被拒绝。 */
            int activeScriptTasks
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

    /** status 是数据库报告的原文（Oracle 为 VALID / INVALID），不报状态的库为 null。 */
    public record SchemaObjectView(
            String objectKey,
            @Schema(nullable = true) String schemaName,
            String name,
            @Schema(nullable = true) String displayName,
            String kind,
            @Schema(nullable = true) String subtype,
            @Schema(nullable = true) String status
    ) {
    }

    public record SchemaObjectPageView(
            List<SchemaObjectView> items,
            int page,
            int pageSize,
            boolean hasMore,
            boolean totalExact,
            int total
    ) {
    }

    public record SchemaObjectParameterView(
            int position,
            @Schema(nullable = true) String name,
            @Schema(nullable = true) String mode,
            @Schema(nullable = true) String typeName,
            boolean nullable
    ) {
    }

    public record SchemaObjectSourceView(
            SchemaObjectView object,
            @Schema(nullable = true) String source,
            boolean sourceAvailable,
            @Schema(nullable = true) String sourceUnavailableReason,
            /** 当前分片后是否还有源码；完整性还需核对从 0 开始的累计长度。 */
            boolean sourceTruncated,
            int sourceOffset,
            /** 完整源码的 UTF-16 长度；源码不可用时为 null。 */
            @Schema(nullable = true) Integer sourceTotalChars,
            @Schema(nullable = true) Integer nextSourceOffset,
            /** 完整源码 UTF-8 字节的 SHA-256，用于检测分片之间的源码变更。 */
            @Schema(nullable = true) String sourceVersion,
            List<SchemaObjectParameterView> parameters,
            List<String> compilationErrors
    ) {
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
            boolean truncated,
            /** 语句成功但对象编译失败（Oracle 系 INVALID）的明细；为空表示没有，或这条语句不建例程类对象。 */
            List<String> compilationErrors
    ) {
    }

    public record QueryResult(
            List<QueryColumnView> columns,
            List<List<Object>> rows,
            long elapsedMs,
            int maxRows,
            boolean truncated,
            @Schema(nullable = true) String truncatedReason,
            /** 本页从第几行开始（0 起）。 */
            int offset,
            /** 因行数或单元格上限截断时，用它作 offset 取下一页；没有更多或无法续取时为 null。 */
            @Schema(nullable = true) Integer nextOffset
    ) {
    }
}
