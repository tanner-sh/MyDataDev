package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.CellSerializer;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DataCommitResponse;
import com.example.dbadmin.dto.ApiDtos.DataPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.DataPreviewResponse;
import com.example.dbadmin.dto.ApiDtos.RowChange;
import com.example.dbadmin.dto.ApiDtos.TableColumn;
import com.example.dbadmin.dto.ApiDtos.TableDataResponse;
import com.example.dbadmin.dto.ApiDtos.TableDataRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.MetadataService.RowIdentity;
import com.example.dbadmin.service.TableCursorCodec.CursorState;
import com.example.dbadmin.service.RowLocatorCodec.RowLocatorState;
import com.example.dbadmin.service.RowLocatorCodec.RowLocatorValue;
import org.springframework.stereotype.Service;

import java.sql.Blob;
import java.sql.Clob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.example.dbadmin.service.TableQueries.NormalizedSort;
import com.example.dbadmin.service.TableQueries.Query;
import com.example.dbadmin.service.TableQueries.TableQueryPlan;
import com.example.dbadmin.service.TableValues.BoundValue;
import com.example.dbadmin.service.TableValues.ColumnDescriptor;

@Service
public class DataEditService {
    private static final Logger log = LoggerFactory.getLogger(DataEditService.class);

    private static final int MAX_PAGE_SIZE = 200;

    private static final long MAX_OFFSET = 100_000;

    private static final int MAX_CHANGES = 1_000;

    private static final int MAX_TABLE_RESULT_CELLS = 50_000;

    private static final long MAX_TABLE_TEXT_CHARS = 5_000_000;

    private static final int MAX_TABLE_CELL_TEXT_CHARS = 100_000;

    /**
     * CLOB 先读回多少字符用于判断截断。
     *
     * <p>比查询结果那条路更窄：表格单元格本来就只显示一行，读回 10 万字符再截掉是白读。
     * {@code serializedValueWouldTruncate} 必须用同一个窗口 —— 「会不会截断」和「实际截断」
     * 用不同阈值，就会出现标了截断却没截、或截了却没标的行。</p>
     */
    private static final int MAX_CLOB_WINDOW_CHARS = 4_096;

    private final MetadataService metadata;

    private final ConnectionService connections;

    private final AuditRepository audit;

    private final DialectRegistry dialectRegistry;

    private final AppProperties properties;

    private final TableCursorCodec cursorCodec;

    private final RowLocatorCodec rowLocatorCodec;

    private final ExecutionGuard executionGuard;

    public DataEditService(
            MetadataService metadata,
            ConnectionService connections,
            AuditRepository audit,
            DialectRegistry dialectRegistry,
            AppProperties properties,
            TableCursorCodec cursorCodec,
            RowLocatorCodec rowLocatorCodec,
            ExecutionGuard executionGuard
    ) {
        this.metadata = metadata;
        this.connections = connections;
        this.audit = audit;
        this.dialectRegistry = dialectRegistry;
        this.properties = properties;
        this.cursorCodec = cursorCodec;
        this.rowLocatorCodec = rowLocatorCodec;
        this.executionGuard = executionGuard;
    }

    public TableDataResponse table(long connectionId, String schemaName, String tableName, String cursor, int pageSize) throws Exception {
        return table(new TableDataRequest(connectionId, schemaName, tableName, cursor, pageSize, List.of(), List.of(), "AND"));
    }

    public TableDataResponse table(TableDataRequest request) throws Exception {
        long connectionId = request.connectionId();
        String schemaName = request.schemaName();
        String tableName = request.tableName();
        String cursor = request.cursor();
        int pageSize = request.pageSize() == null ? 100 : request.pageSize();
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().tableBrowse()) {
            throw new IllegalStateException("当前数据库类型不支持表数据浏览。");
        }
        try (Connection connection = connections.open(connectionId)) {
            RowIdentity identity = metadata.rowIdentity(connection, dbConnection, schemaName, tableName);
            Map<String, ColumnDescriptor> descriptors = loadColumnDescriptors(connection, dialect, schemaName, tableName);
            TableQueryPlan plan = TableQueries.plan(request, descriptors, dialect);
            int effectivePageSize = Math.min(
                    safePageSize,
                    Math.max(1, MAX_TABLE_RESULT_CELLS / Math.max(descriptors.size(), 1))
            );
            CursorState state = cursorCodec.decode(cursor);
            validateCursor(state, connectionId, schemaName, tableName, identity.columns(), plan.fingerprint());
            String mode = plan.sorts().isEmpty() && identity.stable() && !identity.columns().isEmpty() ? "KEYSET" : "OFFSET";
            if (state != null && !mode.equals(state.mode())) throw new IllegalArgumentException("分页方式已变化，请从第一页重新加载");
            long offset = state == null ? 0 : state.offset();
            if ("OFFSET".equals(mode) && offset > MAX_OFFSET) {
                throw new IllegalArgumentException("偏移分页最多浏览到偏移 " + MAX_OFFSET + " 行，请缩小筛选范围；有稳定主键的表也可移除自定义排序后重试。");
            }

            Query query = TableQueries.pageQuery(dialect, schemaName, tableName, identity.columns(), descriptors, mode, state, effectivePageSize, plan);
            try (PreparedStatement statement = connection.prepareStatement(query.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                dialect.configureReadStatement(connection, statement, Math.min(effectivePageSize + 1, 200), properties.getSql().getTimeoutSeconds());
                TableValues.bind(statement, query.parameters());
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int visibleColumnCount = visibleColumnCount(md, dialect.paginationHelperColumn());
                    List<TableColumn> columns = tableColumns(md, visibleColumnCount);
                    Map<String, Integer> columnIndexes = columnIndexes(md, visibleColumnCount);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    List<String> rowKeyTokens = new ArrayList<>();
                    Set<String> truncatedColumns = new LinkedHashSet<>();
                    List<String> lastKeyValues = List.of();
                    long textChars = 0;
                    boolean payloadLimitReached = false;
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int index = 1; index <= visibleColumnCount; index++) {
                            int remainingText = (int) Math.min(
                                    MAX_TABLE_CELL_TEXT_CHARS,
                                    Math.max(0, MAX_TABLE_TEXT_CHARS - textChars)
                            );
                            Object rawValue = readDisplayValue(rs, md, index);
                            if (serializedValueWouldTruncate(rawValue, remainingText)) {
                                truncatedColumns.add(columns.get(index - 1).name());
                            }
                            Object value = serializableValue(rawValue, remainingText);
                            row.put(columns.get(index - 1).name(), value);
                            if (value instanceof CharSequence text) textChars += text.length();
                        }
                        rows.add(row);
                        if (identity.stable() && !identity.columns().isEmpty()) {
                            rowKeyTokens.add(rowLocatorCodec.encode(rowLocatorState(
                                    rs, connectionId, schemaName, tableName, identity.columns(), descriptors, columnIndexes
                            )));
                        }
                        if (rows.size() <= effectivePageSize && "KEYSET".equals(mode)) {
                            lastKeyValues = encodedKeyValues(rs, identity.columns(), columnIndexes);
                        }
                        if (textChars >= MAX_TABLE_TEXT_CHARS) {
                            payloadLimitReached = true;
                            break;
                        }
                    }
                    boolean extraRow = rows.size() > effectivePageSize;
                    boolean hasMore = extraRow || payloadLimitReached && rs.next();
                    if (extraRow) {
                        rows = new ArrayList<>(rows.subList(0, effectivePageSize));
                        if (!rowKeyTokens.isEmpty()) rowKeyTokens = new ArrayList<>(rowKeyTokens.subList(0, effectivePageSize));
                    }
                    int returnedRows = rows.size();
                    List<TableColumn> responseColumns = columns.stream()
                            .map(column -> truncatedColumns.contains(column.name())
                                    ? new TableColumn(column.name(), column.typeName(), column.jdbcType(), column.nullable(), false, true)
                                    : column)
                            .toList();
                    String nextCursor = hasMore
                            ? cursorCodec.encode(new CursorState(
                            2, mode, connectionId, normalize(schemaName), tableName, identity.columns(),
                            "KEYSET".equals(mode) ? lastKeyValues : List.of(),
                            "OFFSET".equals(mode) ? offset + returnedRows : 0,
                            plan.fingerprint()
                    ))
                            : null;
                    boolean editable = dialect.capabilities().tableEdit() && identity.stable() && !identity.columns().isEmpty();
                    return new TableDataResponse(responseColumns, rows, rowKeyTokens, identity.columns(), editable, mode, nextCursor, hasMore);
                }
            }
        }
    }

    /**
     * 为一段单表查询结果准备行定位令牌生成器。
     *
     * <p>SQL 查询结果此前一律只读，而表数据工作区早就能就地编辑 —— 两者的差别只在于
     * 「这段结果是不是来自单张表、那张表有没有稳定行定位字段、这些字段在不在结果集里」。
     * 三条都成立时用的就是同一套令牌与同一个提交接口。</p>
     *
     * <p>令牌里的 jdbcType 必须与 {@link #validatedLocator} 校验用的列描述符完全一致，
     * 所以这里刻意用表自身的描述符，而不是查询结果集的元数据 —— 后者在有类型转换时会不一样。</p>
     */
    public ResultRowLocator resultRowLocator(
            Connection connection,
            DbConnection dbConnection,
            String schemaName,
            String tableName,
            ResultSetMetaData md,
            int visibleColumnCount
    ) throws Exception {
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().tableEdit()) {
            return ResultRowLocator.unavailable("当前数据库类型不支持数据编辑");
        }
        if (dbConnection.readonly()) {
            return ResultRowLocator.unavailable("当前连接为只读连接");
        }
        RowIdentity identity = metadata.rowIdentity(connection, dbConnection, schemaName, tableName);
        if (!identity.stable() || identity.columns().isEmpty()) {
            return ResultRowLocator.unavailable("该表没有主键或全非空唯一索引，无法定位行");
        }
        Map<String, ColumnDescriptor> descriptors = loadColumnDescriptors(connection, dialect, schemaName, tableName);
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 1; index <= visibleColumnCount; index++) {
            String label = md.getColumnLabel(index);
            // 第二道防线：能报出底层字段名的驱动（MySQL、H2）在这里就能识破别名。
            // pgjdbc 的 getColumnName 只是 getColumnLabel 的别名，挡不住的那部分由调用方
            // 用 SQL 文本判断（SelectProjection），两层都只在「确定安全」时才放行。
            String underlying = md.getColumnName(index);
            if (underlying != null && !underlying.isBlank() && !underlying.equalsIgnoreCase(label)) {
                return ResultRowLocator.unavailable("查询结果列 " + label + " 使用了别名，无法对应到表字段");
            }
            indexes.putIfAbsent(label, index);
        }
        for (String keyColumn : identity.columns()) {
            if (columnIndex(indexes, keyColumn) == null) {
                return ResultRowLocator.unavailable("查询结果缺少行定位字段：" + keyColumn);
            }
        }
        return new ResultRowLocator(
                schemaName, tableName, identity.columns(), descriptors, indexes, this, dbConnection.id(), null
        );
    }

    /** 结果集里可编辑列的集合：不在表里的（表达式、聚合、别名）不能改。 */
    public Set<String> editableColumns(Connection connection, DbConnection dbConnection, String schemaName, String tableName) throws Exception {
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        return loadColumnDescriptors(connection, dialect, schemaName, tableName).keySet();
    }

    /**
     * 逐行生成行定位令牌。{@code reason} 非空表示这段结果不可编辑。
     */
    public record ResultRowLocator(
            String schemaName,
            String tableName,
            List<String> keyColumns,
            Map<String, ColumnDescriptor> descriptors,
            Map<String, Integer> indexes,
            DataEditService owner,
            long connectionId,
            String reason
    ) {
        static ResultRowLocator unavailable(String reason) {
            return new ResultRowLocator(null, null, List.of(), Map.of(), Map.of(), null, 0, reason);
        }

        public boolean editable() {
            return reason == null;
        }

        public String encode(ResultSet rs) throws Exception {
            return owner.rowLocatorCodec.encode(
                    owner.rowLocatorState(rs, connectionId, schemaName, tableName, keyColumns, descriptors, indexes)
            );
        }
    }

    public DataPreviewResponse preview(DataPreviewRequest request) throws Exception {
        try (Connection connection = connections.open(request.connectionId())) {
            List<PreparedOperation> operations = operations(connection, request);
            return new DataPreviewResponse(operations.stream().map(PreparedOperation::previewSql).toList());
        }
    }

    public DataCommitResponse commit(DataPreviewRequest request, String actor, String productionConfirmation) throws Exception {
        DbConnection dbConnection = connections.require(request.connectionId());
        executionGuard.requireMutationAllowed(dbConnection, productionConfirmation);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().tableEdit()) {
            throw new IllegalStateException("当前数据库类型不支持表数据编辑。");
        }
        try (Connection connection = connections.open(request.connectionId())) {
            List<PreparedOperation> operations = operations(connection, request);
            if (operations.isEmpty()) return new DataCommitResponse(List.of(), 0);
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            int affected = 0;
            try {
                int index = 0;
                while (index < operations.size()) {
                    String sql = operations.get(index).sql();
                    String type = operations.get(index).type();
                    int end = index;
                    while (end < operations.size() && operations.get(end).sql().equals(sql)) {
                        end++;
                    }
                    List<PreparedOperation> batch = operations.subList(index, end);

                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setQueryTimeout(properties.getSql().getTimeoutSeconds());
                        if (type.equals("INSERT")) {
                            for (PreparedOperation operation : batch) {
                                TableValues.bind(statement, operation.parameters());
                                statement.addBatch();
                            }
                            int[] counts = statement.executeBatch();
                            if (counts.length != batch.size()) {
                                throw new IllegalStateException("新增数据批处理结果数量异常，本次提交已回滚。");
                            }
                            for (int count : counts) {
                                if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                                    throw new IllegalStateException("新增数据影响行数异常，本次提交已回滚。");
                                }
                                // A successful INSERT batch item always represents one requested row,
                                // even when the driver cannot report its exact update count.
                                affected++;
                            }
                        } else {
                            // UPDATE/DELETE must remain individually observable so optimistic
                            // predicates cannot be bypassed by JDBC SUCCESS_NO_INFO batch results.
                            for (int operationIndex = index; operationIndex < end; operationIndex++) {
                                PreparedOperation operation = operations.get(operationIndex);
                                TableValues.bind(statement, operation.parameters());
                                int count = statement.executeUpdate();
                                if (count != 1) {
                                    if (count == 0) throw new com.example.dbadmin.api.ApiProblemException(
                                            org.springframework.http.HttpStatus.CONFLICT, "DATA_EDIT_CONFLICT",
                                            "数据已被其他操作修改或删除，本次提交已回滚。",
                                            Map.of("changeIndex", operationIndex, "keyToken", request.changes().get(operationIndex).keyToken()));
                                    throw new IllegalStateException("行定位条件影响了多行数据，本次提交已回滚。");
                                }
                                affected += count;
                            }
                        }
                    }
                    index = end;
                }
                connection.commit();
            } catch (Exception e) {
                /*
                  收尾动作不许顶掉真正的失败。

                  rollback() 和 finally 里的 setAutoCommit() 任何一个抛出，Java 都会用它替换掉
                  正在传播的异常 —— 于是用户看到的是一句和现场无关的 SQLException，而真正的原因
                  （比如 DATA_EDIT_CONFLICT，或者某个字段值不合法）连日志里都不剩。挂到
                  suppressed 上，两边都留得住。
                */
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                restoreAutoCommitQuietly(connection, previousAutoCommit);
            }
            List<String> previews = operations.stream().map(PreparedOperation::previewSql).toList();
            java.util.List<com.example.dbadmin.dto.ApiDtos.RowChange> submittedChanges = request.changes() == null
                    ? java.util.List.of() : request.changes();
            java.util.Map<String, Long> changeCounts = submittedChanges.stream().collect(java.util.stream.Collectors.groupingBy(
                    change -> change.type() == null ? "UNKNOWN" : change.type().trim().toUpperCase(java.util.Locale.ROOT),
                    java.util.LinkedHashMap::new,
                    java.util.stream.Collectors.counting()
            ));
            // 审计只保留变更类型和行数，不把单元格值或带字面量的 SQL 复制到元数据库。
            audit.onConnection(actor, "DATA_COMMIT", request.connectionId(), "table:" + request.tableName(),
                    "schema=" + request.schemaName() + ", changes=" + changeCounts + ", affectedRows=" + affected);
            return new DataCommitResponse(previews, affected);
        }
    }

    public DataCommitResponse commit(DataPreviewRequest request, String actor) throws Exception {
        return commit(request, actor, null);
    }

    /** 根据服务端签名的行令牌读取冲突现场，仍使用参数绑定，不接收自由 SQL。 */
    public Map<String, Object> conflictRow(DataPreviewRequest request, String actor) throws Exception {
        if (request.changes() == null || request.changes().size() != 1) throw new IllegalArgumentException("请选择一条冲突记录。");
        RowChange change = request.changes().get(0);
        DbConnection db = connections.require(request.connectionId());
        DatabaseDialect dialect = dialectRegistry.dialectFor(db);
        try (Connection connection = connections.open(request.connectionId())) {
            RowIdentity identity = metadata.rowIdentity(connection, db, request.schemaName(), request.tableName());
            if (!identity.stable()) throw new IllegalArgumentException("当前表没有稳定的行标识。");
            Map<String, ColumnDescriptor> columns = loadColumnDescriptors(connection, dialect, request.schemaName(), request.tableName());
            Map<String, BoundValue> key = validatedLocator(request, change.keyToken(), columns, identity.columns());
            Map<String, Object> requested = canonicalValues(change.values(), columns, true);
            List<String> names = requested.isEmpty() ? identity.columns() : new ArrayList<>(requested.keySet());
            WhereClause where = whereClause(key, Map.of(), dialect, columns);
            String sql = "SELECT " + names.stream().map(dialect::quoteIdentifier).collect(Collectors.joining(", "))
                    + " FROM " + dialect.qualifiedName(request.schemaName(), request.tableName()) + " WHERE " + where.sql();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(properties.getSql().getTimeoutSeconds());
                statement.setMaxRows(2);
                TableValues.bind(statement, where.parameters());
                Map<String, Object> values = new LinkedHashMap<>();
                boolean found;
                boolean truncated = false;
                try (ResultSet rs = statement.executeQuery()) {
                    found = rs.next();
                    if (found) for (int index = 1; index <= names.size(); index++) {
                        Object raw = readDisplayValue(rs, rs.getMetaData(), index);
                        truncated |= serializedValueWouldTruncate(raw, MAX_TABLE_CELL_TEXT_CHARS);
                        values.put(names.get(index - 1), serializableValue(raw, MAX_TABLE_CELL_TEXT_CHARS));
                    }
                    if (found && rs.next()) throw new IllegalArgumentException("行标识匹配多行，请刷新表结构。");
                }
                audit.onConnection(actor, "DATA_CONFLICT_READ", request.connectionId(), "table:" + request.tableName());
                return Map.of("found", found, "values", values, "truncated", truncated);
            }
        }
    }

    private List<PreparedOperation> operations(Connection connection, DataPreviewRequest request) throws Exception {
        if (request.changes() == null || request.changes().isEmpty()) return List.of();
        if (request.changes().size() > MAX_CHANGES) throw new IllegalArgumentException("单次最多提交 " + MAX_CHANGES + " 项数据变更");
        DbConnection dbConnection = connections.require(request.connectionId());
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        RowIdentity identity = metadata.rowIdentity(connection, dbConnection, request.schemaName(), request.tableName());
        Map<String, ColumnDescriptor> columns = loadColumnDescriptors(connection, dialect, request.schemaName(), request.tableName());
        List<PreparedOperation> operations = new ArrayList<>();
        for (RowChange change : request.changes()) {
            String type = change.type() == null ? "" : change.type().toUpperCase(Locale.ROOT);
            if ((type.equals("UPDATE") || type.equals("DELETE")) && !identity.stable()) {
                throw new IllegalArgumentException("更新和删除需要主键或单个全非空唯一索引");
            }
            operations.add(switch (type) {
                case "INSERT" -> insertOperation(request, change.values(), dialect, columns);
                case "UPDATE" -> updateOperation(request, change, dialect, columns, identity.columns());
                case "DELETE" -> deleteOperation(request, change, dialect, columns, identity.columns());
                default -> throw new IllegalArgumentException("不支持的数据变更类型：" + change.type());
            });
        }
        return operations;
    }

    private PreparedOperation insertOperation(DataPreviewRequest request, Map<String, Object> values, DatabaseDialect dialect, Map<String, ColumnDescriptor> columns) {
        Map<String, Object> canonical = canonicalValues(values, columns, false);
        if (canonical.isEmpty()) throw new IllegalArgumentException("新增行至少需要填写一个字段；未填写字段将使用数据库默认值");
        String names = canonical.keySet().stream().map(dialect::quoteIdentifier).collect(Collectors.joining(", "));
        String placeholders = canonical.keySet().stream().map(ignored -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + dialect.qualifiedName(request.schemaName(), request.tableName()) + " (" + names + ") VALUES (" + placeholders + ")";
        List<BoundValue> parameters = boundValues(canonical, columns);
        return new PreparedOperation("INSERT", sql, parameters, TableValues.previewSql(sql, parameters, dialect));
    }

    private PreparedOperation updateOperation(DataPreviewRequest request, RowChange change, DatabaseDialect dialect, Map<String, ColumnDescriptor> columns, List<String> keyColumns) {
        Map<String, Object> values = canonicalValues(change.values(), columns, false);
        if (values.isEmpty()) throw new IllegalArgumentException("更新内容不能为空");
        Map<String, BoundValue> key = validatedLocator(request, change.keyToken(), columns, keyColumns);
        Map<String, Object> originals = canonicalValues(change.originalValues(), columns, true);
        String set = values.keySet().stream().map(column -> dialect.quoteIdentifier(column) + " = ?").collect(Collectors.joining(", "));
        WhereClause where = whereClause(key, originals, dialect, columns);
        List<BoundValue> parameters = boundValues(values, columns);
        parameters.addAll(where.parameters());
        String sql = "UPDATE " + dialect.qualifiedName(request.schemaName(), request.tableName()) + " SET " + set + " WHERE " + where.sql();
        return new PreparedOperation("UPDATE", sql, parameters, TableValues.previewSql(sql, parameters, dialect));
    }

    private PreparedOperation deleteOperation(DataPreviewRequest request, RowChange change, DatabaseDialect dialect, Map<String, ColumnDescriptor> columns, List<String> keyColumns) {
        Map<String, BoundValue> key = validatedLocator(request, change.keyToken(), columns, keyColumns);
        WhereClause where = whereClause(key, Map.of(), dialect, columns);
        String sql = "DELETE FROM " + dialect.qualifiedName(request.schemaName(), request.tableName()) + " WHERE " + where.sql();
        return new PreparedOperation("DELETE", sql, where.parameters(), TableValues.previewSql(sql, where.parameters(), dialect));
    }

    private Map<String, BoundValue> validatedLocator(DataPreviewRequest request, String token, Map<String, ColumnDescriptor> columns, List<String> keyColumns) {
        RowLocatorState locator = rowLocatorCodec.decode(token);
        if (locator.version() != 1
                || locator.connectionId() != request.connectionId()
                || !normalize(locator.schemaName()).equals(normalize(request.schemaName()))
                || !request.tableName().equals(locator.tableName())
                || locator.values().size() != keyColumns.size()) {
            throw new IllegalArgumentException("行定位令牌与当前数据表不匹配，请刷新后重试");
        }
        Map<String, RowLocatorValue> supplied = locator.values().stream().collect(Collectors.toMap(
                RowLocatorValue::column,
                value -> value,
                (left, right) -> {
                    throw new IllegalArgumentException("行定位令牌包含重复字段");
                },
                LinkedHashMap::new
        ));
        Map<String, BoundValue> ordered = new LinkedHashMap<>();
        for (String keyColumn : keyColumns) {
            ColumnDescriptor descriptor = TableValues.descriptor(columns, keyColumn);
            RowLocatorValue value = locatorValue(supplied, keyColumn);
            if (value == null || value.jdbcType() != descriptor.jdbcType()) {
                throw new IllegalArgumentException("行定位字段与服务端识别的主键/唯一索引不一致");
            }
            ordered.put(descriptor.name(), new BoundValue(
                    TableValues.decodeDatabaseValue(value.encodedValue(), descriptor), descriptor.jdbcType(),
                    descriptor.typeName(), descriptor.name()
            ));
        }
        if (supplied.size() != ordered.size()) {
            throw new IllegalArgumentException("行定位字段与服务端识别的主键/唯一索引不一致");
        }
        return ordered;
    }

    private WhereClause whereClause(Map<String, BoundValue> key, Map<String, Object> originals, DatabaseDialect dialect, Map<String, ColumnDescriptor> columns) {
        List<String> predicates = new ArrayList<>();
        List<BoundValue> parameters = new ArrayList<>();
        appendTypedPredicates(predicates, parameters, key, dialect);
        // NULL 原值需要保留，用于生成 IS NULL 乐观锁条件；toMap 不接受 null 值。
        Map<String, Object> nonKeyOriginals = new LinkedHashMap<>();
        originals.forEach((column, value) -> {
            if (!key.containsKey(column)) nonKeyOriginals.put(column, value);
        });
        appendPredicates(predicates, parameters, nonKeyOriginals, dialect, columns);
        return new WhereClause(String.join(" AND ", predicates), parameters);
    }

    private void appendTypedPredicates(List<String> predicates, List<BoundValue> parameters, Map<String, BoundValue> values, DatabaseDialect dialect) {
        for (Map.Entry<String, BoundValue> entry : values.entrySet()) {
            if (entry.getValue().value() == null) predicates.add(dialect.quoteIdentifier(entry.getKey()) + " IS NULL");
            else {
                predicates.add(dialect.quoteIdentifier(entry.getKey()) + " = ?");
                parameters.add(entry.getValue());
            }
        }
    }

    private void appendPredicates(List<String> predicates, List<BoundValue> parameters, Map<String, Object> values, DatabaseDialect dialect, Map<String, ColumnDescriptor> columns) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                predicates.add(dialect.quoteIdentifier(entry.getKey()) + " IS NULL");
            } else {
                predicates.add(dialect.quoteIdentifier(entry.getKey()) + " = ?");
                ColumnDescriptor descriptor = TableValues.descriptor(columns, entry.getKey());
                parameters.add(new BoundValue(entry.getValue(), descriptor.jdbcType(), descriptor.typeName(), entry.getKey()));
            }
        }
    }

    private List<BoundValue> boundValues(Map<String, Object> values, Map<String, ColumnDescriptor> columns) {
        return values.entrySet().stream()
                .map(entry -> {
                    ColumnDescriptor column = TableValues.descriptor(columns, entry.getKey());
                    return new BoundValue(entry.getValue(), column.jdbcType(), column.typeName(), column.name());
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Map<String, Object> canonicalValues(Map<String, Object> values, Map<String, ColumnDescriptor> columns, boolean allowEmpty) {
        if (values == null || values.isEmpty()) return allowEmpty ? Map.of() : new LinkedHashMap<>();
        Map<String, Object> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            ColumnDescriptor column = TableValues.descriptor(columns, entry.getKey());
            if (!editableJdbcType(column.jdbcType())) {
                throw new IllegalArgumentException("字段类型不支持在表格中直接编辑：" + column.name() + " (" + column.typeName() + ")");
            }
            if (canonical.containsKey(column.name())) throw new IllegalArgumentException("字段重复：" + column.name());
            canonical.put(column.name(), entry.getValue());
        }
        return canonical;
    }

    private Map<String, ColumnDescriptor> loadColumnDescriptors(Connection connection, DatabaseDialect dialect, String schemaName, String tableName) throws Exception {
        String sql = "SELECT * FROM " + dialect.qualifiedName(schemaName, tableName) + " WHERE 1 = 0";
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            Map<String, ColumnDescriptor> columns = new LinkedHashMap<>();
            for (int index = 1; index <= md.getColumnCount(); index++) {
                String name = md.getColumnLabel(index);
                if (columns.put(name, new ColumnDescriptor(name, md.getColumnType(index), md.getColumnTypeName(index), md.isNullable(index) != ResultSetMetaData.columnNoNulls)) != null) {
                    throw new IllegalStateException("数据表包含重复字段名：" + name);
                }
            }
            return columns;
        }
    }

    /**
     * 导出这张表：与浏览用的是同一条查询 —— 同样的筛选、排序、权限与类型绑定，只是去掉分页。
     *
     * <p>不让导出那边自己拼 WHERE：筛选算子的语义、标识符引用和类型绑定都在这里，复制一份
     * 就等于多一处会和浏览结果对不上的地方 —— 用户看到 12 行、导出却得到 40 行，这种不一致
     * 比没有导出更糟。</p>
     *
     * <p>绑定逻辑随查询一起交出去（{@link StatementBinder}），{@code BoundValue} 与类型转换
     * 因此不必外泄。</p>
     */
    public TableExportQuery exportQuery(TableDataRequest request) throws Exception {
        long connectionId = request.connectionId();
        String schemaName = request.schemaName();
        String tableName = request.tableName();
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().tableBrowse()) {
            throw new IllegalStateException("当前数据库类型不支持表数据浏览。");
        }
        try (Connection connection = connections.open(connectionId)) {
            RowIdentity identity = metadata.rowIdentity(connection, dbConnection, schemaName, tableName);
            Map<String, ColumnDescriptor> descriptors = loadColumnDescriptors(connection, dialect, schemaName, tableName);
            TableQueryPlan plan = TableQueries.plan(request, descriptors, dialect);
            StringBuilder base = new StringBuilder("SELECT * FROM ").append(dialect.qualifiedName(schemaName, tableName));
            List<BoundValue> parameters = new ArrayList<>(plan.parameters());
            if (!plan.whereSql().isBlank()) base.append(" WHERE ").append(plan.whereSql());
            List<String> orderBy = new ArrayList<>();
            for (NormalizedSort sort : plan.sorts()) {
                orderBy.add(dialect.quoteIdentifier(sort.column().name()) + " " + sort.direction());
            }
            // 没有排序时按行标识排：导出两次得到不同顺序的文件，对比起来毫无意义。
            if (orderBy.isEmpty() && identity.stable()) {
                identity.columns().forEach(column -> orderBy.add(dialect.quoteIdentifier(column) + " ASC"));
            }
            if (!orderBy.isEmpty()) base.append(" ORDER BY ").append(String.join(", ", orderBy));
            return new TableExportQuery(base.toString(), statement -> TableValues.bind(statement, parameters));
        }
    }

    /** 把参数绑定交给调用方执行，绑定规则仍留在这里。 */
    public interface StatementBinder {
        void bind(PreparedStatement statement) throws Exception;
    }

    public record TableExportQuery(String sql, StatementBinder binder) {
    }

    private void validateCursor(CursorState state, long connectionId, String schemaName, String tableName, List<String> keyColumns, String queryFingerprint) {
        if (state == null) return;
        if (state.version() != 2
                || state.connectionId() != connectionId
                || !normalize(schemaName).equals(normalize(state.schemaName()))
                || !tableName.equals(state.tableName())
                || !state.keyColumns().equals(keyColumns)
                || !state.queryFingerprint().equals(queryFingerprint)) {
            throw new IllegalArgumentException("分页游标与当前数据表不匹配");
        }
        if (state.offset() < 0 || state.offset() > MAX_OFFSET + MAX_PAGE_SIZE) throw new IllegalArgumentException("分页游标偏移量无效");
    }

    private void rollbackQuietly(Connection connection, Exception primary) {
        try {
            connection.rollback();
        } catch (Exception failure) {
            log.warn("提交失败后回滚也失败了", failure);
            primary.addSuppressed(failure);
        }
    }

    private void restoreAutoCommitQuietly(Connection connection, boolean previousAutoCommit) {
        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (Exception failure) {
            log.warn("恢复 autoCommit 失败，连接将由连接池回收", failure);
        }
    }

    private List<String> encodedKeyValues(ResultSet rs, List<String> keyColumns, Map<String, Integer> indexes) throws Exception {
        List<String> values = new ArrayList<>(keyColumns.size());
        for (String column : keyColumns) {
            Integer index = columnIndex(indexes, column);
            if (index == null) throw new IllegalStateException("分页键不在查询结果中：" + column);
            Object value = rs.getObject(index);
            if (value == null) throw new IllegalStateException("稳定分页键包含 NULL：" + column);
            values.add(value instanceof byte[] bytes ? "base64:" + Base64.getEncoder().encodeToString(bytes) : value.toString());
        }
        return values;
    }

    private RowLocatorState rowLocatorState(
            ResultSet rs,
            long connectionId,
            String schemaName,
            String tableName,
            List<String> keyColumns,
            Map<String, ColumnDescriptor> descriptors,
            Map<String, Integer> indexes
    ) throws Exception {
        List<RowLocatorValue> values = new ArrayList<>(keyColumns.size());
        for (String keyColumn : keyColumns) {
            Integer index = columnIndex(indexes, keyColumn);
            ColumnDescriptor descriptor = TableValues.descriptor(descriptors, keyColumn);
            if (index == null) throw new IllegalStateException("行定位字段不在查询结果中：" + keyColumn);
            Object value = rs.getObject(index);
            if (value == null) throw new IllegalStateException("稳定行定位字段包含 NULL：" + keyColumn);
            values.add(new RowLocatorValue(
                    descriptor.name(), descriptor.jdbcType(), descriptor.typeName(), encodeDatabaseValue(value)
            ));
        }
        return new RowLocatorState(1, connectionId, normalize(schemaName), tableName, values);
    }

    private String encodeDatabaseValue(Object value) throws Exception {
        if (value instanceof byte[] bytes) return "base64:" + Base64.getEncoder().encodeToString(bytes);
        if (value instanceof Blob blob) {
            long length = blob.length();
            if (length > 16_384) throw new IllegalStateException("二进制行定位字段过大，无法安全编辑");
            return "base64:" + Base64.getEncoder().encodeToString(blob.getBytes(1, (int) length));
        }
        return value.toString();
    }

    private int visibleColumnCount(ResultSetMetaData md, String helperColumn) throws Exception {
        int count = md.getColumnCount();
        if (helperColumn != null && count > 0 && helperColumn.equalsIgnoreCase(md.getColumnLabel(count))) count--;
        return count;
    }

    private List<TableColumn> tableColumns(ResultSetMetaData md, int count) throws Exception {
        List<TableColumn> columns = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            int jdbcType = md.getColumnType(index);
            columns.add(new TableColumn(
                    md.getColumnLabel(index), md.getColumnTypeName(index), jdbcType,
                    md.isNullable(index) != ResultSetMetaData.columnNoNulls,
                    editableJdbcType(jdbcType)
            ));
        }
        return columns;
    }

    private boolean editableJdbcType(int jdbcType) {
        return !Set.of(
                Types.BLOB, Types.CLOB, Types.NCLOB,
                Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY,
                Types.ARRAY, Types.STRUCT, Types.REF, Types.ROWID, Types.SQLXML,
                Types.JAVA_OBJECT, Types.DISTINCT, Types.DATALINK, Types.REF_CURSOR
        ).contains(jdbcType);
    }

    private Map<String, Integer> columnIndexes(ResultSetMetaData md, int count) throws Exception {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 1; index <= count; index++) indexes.put(md.getColumnLabel(index), index);
        return indexes;
    }

    private Integer columnIndex(Map<String, Integer> indexes, String column) {
        Integer exact = indexes.get(column);
        if (exact != null) return exact;
        List<Integer> folded = indexes.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(column))
                .map(Map.Entry::getValue)
                .toList();
        if (folded.size() == 1) return folded.get(0);
        if (folded.size() > 1) throw new IllegalArgumentException("字段名大小写不明确，请使用精确名称：" + column);
        return null;
    }

    private RowLocatorValue locatorValue(Map<String, RowLocatorValue> values, String column) {
        RowLocatorValue exact = values.get(column);
        if (exact != null) return exact;
        List<RowLocatorValue> folded = values.values().stream()
                .filter(value -> value.column().equalsIgnoreCase(column))
                .toList();
        if (folded.size() == 1) return folded.get(0);
        if (folded.size() > 1) throw new IllegalArgumentException("行定位字段大小写不明确：" + column);
        return null;
    }

    private Object serializableValue(Object value, int maxTextChars) throws Exception {
        return CellSerializer.serialize(value, maxTextChars, MAX_CLOB_WINDOW_CHARS);
    }

    private Object readDisplayValue(ResultSet rs, ResultSetMetaData metadata, int index) throws Exception {
        int jdbcType = metadata.getColumnType(index);
        if (Set.of(Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY).contains(jdbcType)) {
            try {
                Blob blob = rs.getBlob(index);
                if (blob != null) return "<BINARY " + blob.length() + " bytes>";
                if (rs.wasNull()) return null;
            } catch (Exception ignored) {
                // Fall through to a streaming length count for drivers without getBlob support.
            }
            try (java.io.InputStream input = rs.getBinaryStream(index)) {
                if (input == null) return null;
                return CellSerializer.describeBinaryStream(input);
            }
        }
        return rs.getObject(index);
    }

    private boolean serializedValueWouldTruncate(Object value, int maxTextChars) throws Exception {
        if (value == null || value instanceof Blob || value instanceof byte[]) return false;
        if (value instanceof Clob clob) return clob.length() > Math.min(MAX_CLOB_WINDOW_CHARS, Math.max(maxTextChars, 0));
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Float floatValue && Float.isFinite(floatValue)
                || value instanceof Double doubleValue && Double.isFinite(doubleValue)
                || value instanceof Boolean) {
            return false;
        }
        // 长度要按实际会写出去的文本量，也就是 CellSerializer 那一套规则来数（Timestamp 少两个
        // 字符）。这里再抄一个 toString()，就是「标了截断却没截」这类漂移的下一个入口。
        return CellSerializer.text(value).length() > Math.max(maxTextChars, 0);
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }

    private record PreparedOperation(String type, String sql, List<BoundValue> parameters, String previewSql) {
    }

    private record WhereClause(String sql, List<BoundValue> parameters) {
    }
}
