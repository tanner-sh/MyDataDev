package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DataTransferRequest;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 跨连接数据传输：把 A 库的一张表或一段查询结果写进 B 库的一张表。
 *
 * <p>此前这件事只能靠「导出成 CSV 再导入」绕过去，代价有三：生产数据要在磁盘上落一份明文、
 * CSV 分不清空字符串和 NULL、列的类型信息在往返中丢干净。</p>
 *
 * <p>这里没有新建任何后台任务机制 —— 读侧照 {@link ExportService} 的规矩（只读作用域、流式游标、
 * 生产确认、审计与 SQL 历史），写侧直接把 {@link ResultSetRowSource} 交给
 * {@link DataImportService#convert}，产出的脚本注册成一个普通的 SQL 文件任务。于是进度、取消、
 * 每连接并发闸门、未限定范围写确认与目标端的生产确认全部沿用现成的那一套，一行都不必重写。</p>
 *
 * <p>与导出的一处刻意不同：这里不套 {@link ExportService#EXPORT_MAX_ROWS} 那个一万行上限。那个
 * 上限存在是因为下载的结果要落到浏览器手里；传输的规模边界是脚本字节上限
 * （{@code app.sql-file.max-upload-bytes}），超了会有一句明确的报错，而不是悄悄少写几百万行。</p>
 */
@Service
public class DataTransferService {
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final MetadataService metadata;
    private final DataEditService dataEdit;
    private final SqlFileExecutionService sqlFiles;
    private final ExecutionGuard executionGuard;
    private final ExportService exportService;
    private final AppProperties properties;
    private final AuditRepository audit;
    private final SqlHistoryRepository history;

    public DataTransferService(
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            MetadataService metadata,
            DataEditService dataEdit,
            SqlFileExecutionService sqlFiles,
            ExecutionGuard executionGuard,
            ExportService exportService,
            AppProperties properties,
            AuditRepository audit,
            SqlHistoryRepository history
    ) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.metadata = metadata;
        this.dataEdit = dataEdit;
        this.sqlFiles = sqlFiles;
        this.executionGuard = executionGuard;
        this.exportService = exportService;
        this.properties = properties;
        this.audit = audit;
        this.history = history;
    }

    /**
     * 准备一次传输：读源端、生成写目标端的脚本、注册成待执行任务。
     *
     * <p>返回的任务不会自己开跑。用户先看到「往哪张表写多少行」，再走既有的
     * {@code /api/sql-file-executions/{id}/start} —— 目标端的生产确认在那一步补上，和文件导入
     * 完全一致。把两步并成一步就等于让一次点击直接往生产库写几百万行。</p>
     *
     * @param sourceProductionConfirmation 源端是生产连接时要回传的连接名。数据离开源库这件事
     *                                     本身就需要确认，与导出同理。
     */
    public SqlFileExecutionResponse prepare(DataTransferRequest request, String actor,
                                            String sourceProductionConfirmation) throws Exception {
        DbConnection source = connections.require(request.sourceConnectionId());
        DbConnection target = connections.require(request.targetConnectionId());
        if (request.targetTable() == null || request.targetTable().isBlank()) {
            throw new IllegalArgumentException("请指定传输的目标表。");
        }
        // 写侧的只读判定要赶在读源库之前：源表几百万行读完才发现目标连接只读，白跑一趟。
        executionGuard.requireWritableConnection(target);

        String sourceSql = resolveSourceSql(request, source);
        // 自由 SQL 与产品生成的查询在这里一视同仁地要生产确认 —— 传输和导出一样，是把数据
        // 带出这条连接，而不只是显示在屏幕上。
        executionGuard.requireQueryAllowed(source, SqlStatementClassifier.Kind.QUERY, sourceProductionConfirmation);

        DatabaseDialect targetDialect = dialectRegistry.dialectFor(target);
        Set<String> targetColumns = targetColumns(target, request.targetSchema(), request.targetTable(), targetDialect);
        DatabaseDialect.ImportConflictStyle style = conflictStyle(request, target, targetDialect, targetColumns);
        String mode = normalizeMode(request.conflictMode());

        String description = "连接「" + source.name() + "」"
                + (request.sourceTable() == null || request.sourceTable().isBlank()
                        ? "的查询结果" : "的表 " + request.sourceTable());
        long started = System.nanoTime();
        try {
            SqlFileExecutionResponse response = sqlFiles.uploadScript(
                    target.id(),
                    scriptName(source.name(), request.targetTable()),
                    0,
                    out -> {
                        try (Connection connection = openSource(request);
                             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
                             Statement statement = connection.createStatement(
                                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                            dialectRegistry.dialectFor(source).configureStreamingStatement(
                                    connection, statement, 500, properties.getSql().getTimeoutSeconds());
                            try (ResultSet rs = statement.executeQuery(sourceSql)) {
                                ResultSetRowSource rows = new ResultSetRowSource(rs);
                                long written = DataImportService.convert(rows, out, targetDialect,
                                        request.targetSchema(), request.targetTable(), targetColumns,
                                        description, style);
                                return "rows=" + written;
                            }
                        }
                    },
                    actor,
                    "DATA_TRANSFER_PREPARE",
                    "source=" + source.name() + "; target=" + request.targetTable() + "; conflict=" + mode
            );
            recordSourceRead(source, sourceSql, actor, request, started, null);
            return response;
        } catch (Exception error) {
            recordSourceRead(source, sourceSql, actor, request, started, error);
            throw error;
        }
    }

    /**
     * 源端也要留一条审计与一条 SQL 历史。
     *
     * <p>{@code uploadScript} 记的是目标连接上的那一半。少了这一条，事后按源连接筛选审计时
     * 「这批数据是什么时候被谁搬走的」就查不出来 —— 而这正是传输功能最需要被追溯的一面。
     * 归属走 {@code onConnection}，不是把连接名拼进 target 字符串。</p>
     */
    private void recordSourceRead(DbConnection source, String sql, String actor, DataTransferRequest request,
                                  long startedNanos, Exception error) {
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000;
        try {
            audit.onConnection(actor, "DATA_TRANSFER_READ", source.id(),
                    "target=" + request.targetConnectionId() + ":" + request.targetTable()
                            + (error == null ? "" : "; failed"));
            history.insert(source.id(), sql, "TRANSFER", error == null ? "SUCCESS" : "FAILED", elapsed,
                    error == null ? null : abbreviate(error.getMessage()), actor);
        } catch (Exception ignored) {
            // 审计与历史是旁路记录，写不进去不该把一次已经完成的传输改判成失败。
        }
    }

    /** 源端的取数 SQL：给了自由 SQL 就用它，否则按元数据解析出的规范表名生成一条全表查询。 */
    private String resolveSourceSql(DataTransferRequest request, DbConnection source) throws Exception {
        String raw = request.sourceSql();
        if (raw != null && !raw.isBlank()) {
            // 「导出只能是单条查询」那条规则的唯一定义，传输沿用它而不是再判一次。
            return exportService.requireSingleQuery(raw);
        }
        if (request.sourceTable() == null || request.sourceTable().isBlank()) {
            throw new IllegalArgumentException("请指定源表，或给出一条源查询。");
        }
        // 表名一律用元数据解析出的规范名，不拿用户输入去拼带引号的 SQL —— 与数据对比同一条规矩。
        var detail = metadata.detail(request.sourceConnectionId(), request.sourceSchema(), request.sourceTable());
        if (detail.columns().isEmpty()) {
            throw new IllegalArgumentException("源表 " + request.sourceTable() + " 没有可读取的字段。");
        }
        DatabaseDialect dialect = dialectRegistry.dialectFor(source);
        String columns = String.join(", ", detail.columns().stream()
                .map(column -> dialect.quoteIdentifier(column.name())).toList());
        return "SELECT " + columns + " FROM " + dialect.qualifiedName(detail.schemaName(), detail.name());
    }

    private Connection openSource(DataTransferRequest request) throws Exception {
        return request.sourceSchema() == null || request.sourceSchema().isBlank()
                ? connections.open(request.sourceConnectionId())
                : connections.open(request.sourceConnectionId(), request.sourceSchema());
    }

    private Set<String> targetColumns(DbConnection target, String schemaName, String tableName,
                                      DatabaseDialect dialect) throws Exception {
        try (Connection connection = connections.open(target.id(), schemaName)) {
            Set<String> columns = new LinkedHashSet<>(
                    dataEdit.editableColumns(connection, target, schemaName, tableName));
            if (columns.isEmpty()) throw new IllegalArgumentException("未找到目标表的字段：" + tableName);
            return columns;
        } catch (SQLException error) {
            throw new IllegalArgumentException(
                    "无法读取目标表 " + dialect.qualifiedName(schemaName, tableName)
                            + " 的字段，请确认表名与所在 Schema 是否正确。", error);
        }
    }

    private DatabaseDialect.ImportConflictStyle conflictStyle(DataTransferRequest request, DbConnection target,
                                                             DatabaseDialect dialect, Set<String> targetColumns) {
        List<String> keys = targetPrimaryKeys(target.id(), request.targetSchema(), request.targetTable());
        String mode = normalizeMode(request.conflictMode());
        DatabaseDialect.ImportConflictStyle style =
                dialect.importConflictStyle(mode, List.copyOf(targetColumns), keys);
        if (style == null) {
            throw new IllegalArgumentException(keys.isEmpty()
                    ? "目标表没有主键，无法按主键判断重复；请改用「直接插入」，或先给表加主键。"
                    : "目标数据库类型不支持传输时的「" + modeLabel(mode) + "」策略，请改用「直接插入」。");
        }
        return style;
    }

    private List<String> targetPrimaryKeys(long connectionId, String schemaName, String tableName) {
        try {
            return metadata.detail(connectionId, schemaName, tableName).primaryKeys();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "INSERT" : mode.trim().toUpperCase(Locale.ROOT);
    }

    private static String modeLabel(String mode) {
        return switch (mode) {
            case "SKIP" -> "跳过重复行";
            case "UPSERT" -> "更新已存在的行";
            default -> mode;
        };
    }

    static String scriptName(String sourceName, String tableName) {
        String name = (sourceName + "-传输-" + tableName + ".sql").replaceAll("[\\\\/:*?\"<>|\\x00]", "_");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        String single = value.replaceAll("\\R", " ");
        return single.length() > 500 ? single.substring(0, 500) + "…" : single;
    }
}
