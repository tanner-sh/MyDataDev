package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DataCommitResponse;
import com.example.dbadmin.dto.ApiDtos.DataPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.DataPreviewResponse;
import com.example.dbadmin.dto.ApiDtos.RowChange;
import com.example.dbadmin.dto.ApiDtos.TableColumn;
import com.example.dbadmin.dto.ApiDtos.TableDataResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.MetadataService.RowIdentity;
import com.example.dbadmin.service.TableCursorCodec.CursorState;
import com.example.dbadmin.service.RowLocatorCodec.RowLocatorState;
import com.example.dbadmin.service.RowLocatorCodec.RowLocatorValue;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DataEditService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final long MAX_OFFSET = 100_000;
    private static final int MAX_CHANGES = 1_000;
    private static final int MAX_TABLE_RESULT_CELLS = 50_000;
    private static final long MAX_TABLE_TEXT_CHARS = 5_000_000;
    private static final int MAX_TABLE_CELL_TEXT_CHARS = 100_000;
    private static final int MAX_PREVIEW_PARAMETER_CHARS = 2_000;
    private static final int MAX_PREVIEW_SQL_CHARS = 10_000;
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
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().tableBrowse()) {
            throw new IllegalStateException("当前数据库类型不支持表数据浏览。");
        }
        try (Connection connection = connections.open(connectionId)) {
            RowIdentity identity = metadata.rowIdentity(connection, dbConnection, schemaName, tableName);
            Map<String, ColumnDescriptor> descriptors = loadColumnDescriptors(connection, dialect, schemaName, tableName);
            int effectivePageSize = Math.min(
                    safePageSize,
                    Math.max(1, MAX_TABLE_RESULT_CELLS / Math.max(descriptors.size(), 1))
            );
            CursorState state = cursorCodec.decode(cursor);
            validateCursor(state, connectionId, schemaName, tableName, identity.columns());
            String mode = identity.stable() && !identity.columns().isEmpty() ? "KEYSET" : "OFFSET";
            if (state != null && !mode.equals(state.mode())) throw new IllegalArgumentException("分页方式已变化，请从第一页重新加载");
            long offset = state == null ? 0 : state.offset();
            if ("OFFSET".equals(mode) && offset > MAX_OFFSET) {
                throw new IllegalArgumentException("无稳定主键的表最多浏览到偏移 " + MAX_OFFSET + " 行，请为表增加主键或唯一非空索引。");
            }

            Query query = tableQuery(dialect, schemaName, tableName, identity.columns(), descriptors, mode, state, effectivePageSize);
            try (PreparedStatement statement = connection.prepareStatement(query.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                dialect.configureReadStatement(connection, statement, Math.min(effectivePageSize + 1, 200), properties.getSql().getTimeoutSeconds());
                bindCursor(statement, query.parameters());
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
                            Object rawValue = rs.getObject(index);
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
                            1, mode, connectionId, normalize(schemaName), tableName, identity.columns(),
                            "KEYSET".equals(mode) ? lastKeyValues : List.of(),
                            "OFFSET".equals(mode) ? offset + returnedRows : 0
                    ))
                            : null;
                    boolean editable = dialect.capabilities().tableEdit() && identity.stable() && !identity.columns().isEmpty();
                    return new TableDataResponse(responseColumns, rows, rowKeyTokens, identity.columns(), editable, mode, nextCursor, hasMore);
                }
            }
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
                for (PreparedOperation operation : operations) {
                    try (PreparedStatement statement = connection.prepareStatement(operation.sql())) {
                        statement.setQueryTimeout(properties.getSql().getTimeoutSeconds());
                        bind(statement, operation.parameters());
                        int count = statement.executeUpdate();
                        if ((operation.type().equals("UPDATE") || operation.type().equals("DELETE")) && count != 1) {
                            throw new IllegalStateException(count == 0
                                    ? "数据已被其他操作修改或删除，本次提交已回滚。"
                                    : "行定位条件影响了多行数据，本次提交已回滚。");
                        }
                        if (operation.type().equals("INSERT") && count != 1) {
                            throw new IllegalStateException("新增数据影响行数异常，本次提交已回滚。");
                        }
                        affected += count;
                    }
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
            List<String> previews = operations.stream().map(PreparedOperation::previewSql).toList();
            audit.log(actor, "DATA_COMMIT", "connection:" + request.connectionId() + " table:" + request.tableName(), String.join("\n", previews));
            return new DataCommitResponse(previews, affected);
        }
    }

    public DataCommitResponse commit(DataPreviewRequest request, String actor) throws Exception {
        return commit(request, actor, null);
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
        return new PreparedOperation("INSERT", sql, parameters, preview(sql, parameters, dialect));
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
        return new PreparedOperation("UPDATE", sql, parameters, preview(sql, parameters, dialect));
    }

    private PreparedOperation deleteOperation(DataPreviewRequest request, RowChange change, DatabaseDialect dialect, Map<String, ColumnDescriptor> columns, List<String> keyColumns) {
        Map<String, BoundValue> key = validatedLocator(request, change.keyToken(), columns, keyColumns);
        WhereClause where = whereClause(key, Map.of(), dialect, columns);
        String sql = "DELETE FROM " + dialect.qualifiedName(request.schemaName(), request.tableName()) + " WHERE " + where.sql();
        return new PreparedOperation("DELETE", sql, where.parameters(), preview(sql, where.parameters(), dialect));
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
            ColumnDescriptor descriptor = descriptor(columns, keyColumn);
            RowLocatorValue value = locatorValue(supplied, keyColumn);
            if (value == null || value.jdbcType() != descriptor.jdbcType()) {
                throw new IllegalArgumentException("行定位字段与服务端识别的主键/唯一索引不一致");
            }
            ordered.put(descriptor.name(), new BoundValue(
                    decodeDatabaseValue(value.encodedValue(), descriptor), descriptor.jdbcType(), descriptor.typeName()
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
        Map<String, Object> nonKeyOriginals = originals.entrySet().stream()
                .filter(entry -> !key.containsKey(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
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
                ColumnDescriptor descriptor = descriptor(columns, entry.getKey());
                parameters.add(new BoundValue(entry.getValue(), descriptor.jdbcType(), descriptor.typeName()));
            }
        }
    }

    private List<BoundValue> boundValues(Map<String, Object> values, Map<String, ColumnDescriptor> columns) {
        return values.entrySet().stream()
                .map(entry -> {
                    ColumnDescriptor column = descriptor(columns, entry.getKey());
                    return new BoundValue(entry.getValue(), column.jdbcType(), column.typeName());
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Map<String, Object> canonicalValues(Map<String, Object> values, Map<String, ColumnDescriptor> columns, boolean allowEmpty) {
        if (values == null || values.isEmpty()) return allowEmpty ? Map.of() : new LinkedHashMap<>();
        Map<String, Object> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            ColumnDescriptor column = descriptor(columns, entry.getKey());
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

    private Query tableQuery(DatabaseDialect dialect, String schemaName, String tableName, List<String> keyColumns, Map<String, ColumnDescriptor> descriptors, String mode, CursorState state, int pageSize) {
        StringBuilder base = new StringBuilder("SELECT * FROM ").append(dialect.qualifiedName(schemaName, tableName));
        List<CursorParameter> parameters = new ArrayList<>();
        if ("KEYSET".equals(mode) && state != null && !state.keyValues().isEmpty()) {
            if (state.keyValues().size() != keyColumns.size()) throw new IllegalArgumentException("分页游标字段数量不匹配");
            List<String> alternatives = new ArrayList<>();
            for (int index = 0; index < keyColumns.size(); index++) {
                List<String> parts = new ArrayList<>();
                for (int prior = 0; prior < index; prior++) {
                    parts.add(dialect.quoteIdentifier(keyColumns.get(prior)) + " = ?");
                    parameters.add(new CursorParameter(state.keyValues().get(prior), descriptor(descriptors, keyColumns.get(prior))));
                }
                parts.add(dialect.quoteIdentifier(keyColumns.get(index)) + " > ?");
                parameters.add(new CursorParameter(state.keyValues().get(index), descriptor(descriptors, keyColumns.get(index))));
                alternatives.add("(" + String.join(" AND ", parts) + ")");
            }
            base.append(" WHERE ").append(String.join(" OR ", alternatives));
        }
        if ("KEYSET".equals(mode)) {
            base.append(" ORDER BY ").append(keyColumns.stream().map(dialect::quoteIdentifier).collect(Collectors.joining(", ")));
        }
        int offset = "OFFSET".equals(mode) && state != null ? Math.toIntExact(state.offset()) : 0;
        return new Query(dialect.pageQuery(base.toString(), pageSize + 1, offset), parameters);
    }

    private void validateCursor(CursorState state, long connectionId, String schemaName, String tableName, List<String> keyColumns) {
        if (state == null) return;
        if (state.version() != 1
                || state.connectionId() != connectionId
                || !normalize(schemaName).equals(normalize(state.schemaName()))
                || !tableName.equals(state.tableName())
                || !state.keyColumns().equals(keyColumns)) {
            throw new IllegalArgumentException("分页游标与当前数据表不匹配");
        }
        if (state.offset() < 0 || state.offset() > MAX_OFFSET + MAX_PAGE_SIZE) throw new IllegalArgumentException("分页游标偏移量无效");
    }

    private void bindCursor(PreparedStatement statement, List<CursorParameter> parameters) throws Exception {
        for (int index = 0; index < parameters.size(); index++) {
            CursorParameter parameter = parameters.get(index);
            statement.setObject(index + 1, decodeCursorValue(parameter.value(), parameter.column()));
        }
    }

    private ColumnDescriptor descriptor(Map<String, ColumnDescriptor> descriptors, String column) {
        ColumnDescriptor exact = descriptors.get(column);
        if (exact != null) return exact;
        List<ColumnDescriptor> folded = descriptors.values().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(column))
                .toList();
        if (folded.size() == 1) return folded.get(0);
        if (folded.size() > 1) throw new IllegalArgumentException("字段名大小写不明确，请使用精确名称：" + column);
        throw new IllegalArgumentException("字段不存在：" + column);
    }

    private Object decodeCursorValue(String value, ColumnDescriptor column) {
        return decodeDatabaseValue(value, column);
    }

    private Object decodeDatabaseValue(String value, ColumnDescriptor column) {
        if (value == null) return null;
        return switch (column.jdbcType()) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Integer.valueOf(value);
            case Types.BIGINT -> new BigDecimal(value);
            case Types.NUMERIC, Types.DECIMAL -> new BigDecimal(value);
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> Double.valueOf(value);
            case Types.BOOLEAN, Types.BIT -> parseBoolean(value);
            case Types.DATE -> Date.valueOf(value.substring(0, Math.min(value.length(), 10)));
            case Types.TIME -> Time.valueOf(value.length() >= 8 ? value.substring(0, 8) : value);
            case Types.TIME_WITH_TIMEZONE -> OffsetTime.parse(value);
            case Types.TIMESTAMP -> Timestamp.valueOf(value.replace('T', ' '));
            case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(value.replace(' ', 'T'));
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> Base64.getDecoder().decode(value.replaceFirst("^base64:", ""));
            case Types.OTHER -> {
                String typeName = column.typeName() == null ? "" : column.typeName().toLowerCase(Locale.ROOT);
                yield Set.of("uuid", "uniqueidentifier").contains(typeName) ? UUID.fromString(value) : value;
            }
            default -> value;
        };
    }

    private void bind(PreparedStatement statement, List<BoundValue> values) throws Exception {
        for (int index = 0; index < values.size(); index++) {
            BoundValue bound = values.get(index);
            if (bound.value() == null) statement.setNull(index + 1, bound.jdbcType() == Types.OTHER ? Types.NULL : bound.jdbcType());
            else {
                Object value = coerceValue(bound);
                if (bound.jdbcType() == Types.OTHER) statement.setObject(index + 1, value);
                else statement.setObject(index + 1, value, bound.jdbcType());
            }
        }
    }

    private Boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new IllegalArgumentException("布尔字段仅支持 true、false、1 或 0：" + value);
        };
    }

    private Object coerceValue(BoundValue bound) {
        Object value = bound.value();
        if (!(value instanceof String text)) return value;
        return decodeDatabaseValue(text, new ColumnDescriptor("", bound.jdbcType(), bound.typeName(), true));
    }

    private String preview(String sql, List<BoundValue> values, DatabaseDialect dialect) {
        StringBuilder result = new StringBuilder();
        int parameter = 0;
        for (int index = 0; index < sql.length(); index++) {
            char ch = sql.charAt(index);
            if (ch == '?' && parameter < values.size()) {
                Object previewValue = coerceValue(values.get(parameter++));
                if (previewValue instanceof CharSequence text && text.length() > MAX_PREVIEW_PARAMETER_CHARS) {
                    previewValue = "<值预览已截断，共 " + text.length() + " 字符>";
                }
                String literal = dialect.literal(previewValue);
                result.append(literal.length() <= MAX_PREVIEW_PARAMETER_CHARS
                        ? literal
                        : dialect.literal("<值预览已截断>"));
            }
            else result.append(ch);
            if (result.length() > MAX_PREVIEW_SQL_CHARS) {
                return result.substring(0, MAX_PREVIEW_SQL_CHARS) + "… /* 预览已截断 */";
            }
        }
        return result.append(';').toString();
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
            ColumnDescriptor descriptor = descriptor(descriptors, keyColumn);
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
        if (value == null) return null;
        if (value instanceof Clob clob) {
            long length = clob.length();
            int visible = (int) Math.min(length, Math.min(4_096, Math.max(maxTextChars, 0)));
            String text = visible == 0 ? "" : clob.getSubString(1, visible);
            return length > visible ? truncateText(text, "… <CLOB 已截断，共 " + length + " 字符>", maxTextChars) : text;
        }
        if (value instanceof Blob blob) return truncateText("<BLOB " + blob.length() + " bytes>", "", maxTextChars);
        if (value instanceof byte[] bytes) return truncateText("<BINARY " + bytes.length + " bytes>", "", maxTextChars);
        if (value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return truncateText(value.toString(), "", maxTextChars);
        }
        if (value instanceof CharSequence text) {
            String string = text.toString();
            return string.length() > maxTextChars
                    ? truncateText(string, "… <文本已截断，共 " + string.length() + " 字符>", maxTextChars)
                    : string;
        }
        if (value instanceof Float number && !Float.isFinite(number)) return number.toString();
        if (value instanceof Double number && !Double.isFinite(number)) return number.toString();
        if (value instanceof Number || value instanceof Boolean) return value;
        return truncateText(value.toString(), "", maxTextChars);
    }

    private boolean serializedValueWouldTruncate(Object value, int maxTextChars) throws Exception {
        if (value == null || value instanceof Blob || value instanceof byte[]) return false;
        if (value instanceof Clob clob) return clob.length() > Math.min(4_096, Math.max(maxTextChars, 0));
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Float floatValue && Float.isFinite(floatValue)
                || value instanceof Double doubleValue && Double.isFinite(doubleValue)
                || value instanceof Boolean) {
            return false;
        }
        return value.toString().length() > Math.max(maxTextChars, 0);
    }

    private String truncateText(String prefixSource, String marker, int maxChars) {
        if (maxChars <= 0) return "";
        if (prefixSource.length() <= maxChars && marker.isEmpty()) return prefixSource;
        if (marker.length() >= maxChars) return prefixSource.substring(0, Math.min(prefixSource.length(), maxChars));
        int prefixLength = Math.min(prefixSource.length(), maxChars - marker.length());
        return prefixSource.substring(0, prefixLength) + marker;
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }

    private record Query(String sql, List<CursorParameter> parameters) {
    }

    private record CursorParameter(String value, ColumnDescriptor column) {
    }

    private record ColumnDescriptor(String name, int jdbcType, String typeName, boolean nullable) {
    }

    private record BoundValue(Object value, int jdbcType, String typeName) {
    }

    private record PreparedOperation(String type, String sql, List<BoundValue> parameters, String previewSql) {
    }

    private record WhereClause(String sql, List<BoundValue> parameters) {
    }
}
