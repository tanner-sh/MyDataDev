package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DataDiffRequest;
import com.example.dbadmin.dto.ApiDtos.DataDiffResponse;
import com.example.dbadmin.dto.ApiDtos.DataDiffRow;
import com.example.dbadmin.dto.ApiDtos.DataDiffSummary;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffEndpoint;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 两张表之间的逐行数据对比，以及把目标端对齐到源端的同步脚本。
 *
 * <p>与 {@link SchemaDiffService} 是一对：那边回答「表结构差在哪」，这边回答「同一张表两边的
 * 数据差在哪」。同样只读 —— 生成的 INSERT/UPDATE/DELETE 交给用户在 SQL 工作台执行，生产确认、
 * 未限定范围写操作确认与审计都在那条路上，这里一条都不绕过。</p>
 *
 * <p><b>两侧整批读进内存后按主键比对</b>，所以行数必须封顶（{@link #MAX_ROWS_PER_SIDE}）。
 * 换成流式归并可以省内存，但归并要求两侧按主键有序，而顺序取决于数据库的排序规则 —— 两边
 * 排序规则不同的话，归并会把大批相同的行报成差异。宁可限制规模，也不要给出错误的对比结果。</p>
 *
 * <p>二进制与大对象列不参与对比：它们按文本读出来不可靠（驱动行为不一），而且一行几 MB 的
 * 内容进内存会让上面那个上限失去意义。跳过了哪些列会写进 warnings，不静默处理。</p>
 */
@Service
public class DataDiffService {
    /** 每侧最多读多少行。超过就拒绝，而不是给一个只比了一部分的结论。 */
    static final int MAX_ROWS_PER_SIDE = 100_000;
    /** 最多记多少条差异。再多的话，同步脚本本身就不是人能复核的东西了。 */
    static final int MAX_DIFFERENCES = 2_000;
    /** 回给界面的单元格文本上限；脚本用的是完整值。 */
    static final int MAX_CELL_CHARS = 200;
    public static final String ACTION_DATA_DIFF = "DATA_DIFF";

    private final ConnectionService connections;
    private final MetadataService metadata;
    private final DialectRegistry dialectRegistry;
    private final AuditRepository audit;
    private final AppProperties properties;

    public DataDiffService(
            ConnectionService connections,
            MetadataService metadata,
            DialectRegistry dialectRegistry,
            AuditRepository audit,
            AppProperties properties
    ) {
        this.connections = connections;
        this.metadata = metadata;
        this.dialectRegistry = dialectRegistry;
        this.audit = audit;
        this.properties = properties;
    }

    public DataDiffResponse compare(DataDiffRequest request, String actor) throws Exception {
        DbConnection source = connections.require(request.sourceConnectionId());
        DbConnection target = connections.require(request.targetConnectionId());
        String sourceSchema = resolveSchema(request.sourceConnectionId(), request.sourceSchema());
        String targetSchema = resolveSchema(request.targetConnectionId(), request.targetSchema());
        String sourceTable = require(request.sourceTable(), "源表名不能为空。");
        String targetTable = request.targetTable() == null || request.targetTable().isBlank()
                ? sourceTable : request.targetTable().trim();
        if (request.sourceConnectionId() == request.targetConnectionId()
                && fold(sourceSchema).equals(fold(targetSchema))
                && fold(sourceTable).equals(fold(targetTable))) {
            throw new IllegalArgumentException("源和目标指向同一张表，没有可对比的内容。");
        }

        ObjectDetail sourceDetail = metadata.detail(request.sourceConnectionId(), sourceSchema, sourceTable);
        ObjectDetail targetDetail = metadata.detail(request.targetConnectionId(), targetSchema, targetTable);
        // 之后一律用元数据解析出的规范名：用户在界面上敲的 orders，库里存的可能是 ORDERS，
        // 拿原文去拼带引号的 SQL 会直接找不到表 —— 生成的同步脚本同理。
        sourceTable = canonical(sourceDetail.name(), sourceTable);
        targetTable = canonical(targetDetail.name(), targetTable);
        sourceSchema = canonical(sourceDetail.schemaName(), sourceSchema);
        targetSchema = canonical(targetDetail.schemaName(), targetSchema);
        List<String> warnings = new ArrayList<>();

        List<String> columns = comparableColumns(sourceDetail, targetDetail, warnings);
        if (columns.isEmpty()) throw new IllegalArgumentException("两张表没有同名且可对比的字段。");
        List<String> keyColumns = keyColumns(request, sourceDetail, targetDetail, columns);

        DatabaseDialect sourceDialect = dialectRegistry.dialectFor(source);
        DatabaseDialect targetDialect = dialectRegistry.dialectFor(target);
        Map<String, DataComparison.Row> sourceRows = readRows(
                request.sourceConnectionId(), sourceSchema, sourceTable, sourceDialect, keyColumns, columns, "源");
        Map<String, DataComparison.Row> targetRows = readRows(
                request.targetConnectionId(), targetSchema, targetTable, targetDialect, keyColumns, columns, "目标");

        DataComparison.Result result = DataComparison.compare(sourceRows, targetRows, columns, MAX_DIFFERENCES);
        if (result.truncated()) {
            warnings.add("差异超过 " + MAX_DIFFERENCES + " 条，只列出并生成了前 " + MAX_DIFFERENCES
                    + " 条的同步语句 —— 这份脚本是不完整的，请缩小对比范围后重跑。");
        }
        List<String> script = DataComparison.syncScript(result, targetDialect,
                targetDialect.qualifiedName(targetSchema, targetTable), columns, keyColumns, request.includeDeletes());

        audit.onConnection(actor, ACTION_DATA_DIFF, request.sourceConnectionId(), "table:" + sourceTable,
                "target=" + target.name() + "." + targetTable
                        + " onlyInSource=" + result.onlyInSource()
                        + " onlyInTarget=" + result.onlyInTarget()
                        + " different=" + result.different()
                        + " identical=" + result.identical());

        return new DataDiffResponse(
                endpoint(source, sourceSchema),
                endpoint(target, targetSchema),
                sourceTable,
                targetTable,
                keyColumns,
                columns,
                new DataDiffSummary(result.onlyInSource(), result.onlyInTarget(), result.different(), result.identical()),
                rows(result, columns),
                script,
                result.truncated(),
                warnings);
    }

    /**
     * 参与对比的列：两侧同名的交集，去掉二进制与大对象。
     *
     * <p>只在一侧存在的列会写进 warnings —— 那通常正是用户想知道的事（一边加了字段还没同步），
     * 静默跳过等于把线索藏起来。</p>
     */
    private static List<String> comparableColumns(ObjectDetail source, ObjectDetail target, List<String> warnings) {
        Map<String, ColumnInfo> targetColumns = new LinkedHashMap<>();
        for (ColumnInfo column : target.columns()) targetColumns.put(fold(column.name()), column);

        List<String> columns = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> onlyInSource = new ArrayList<>();
        Set<String> matched = new LinkedHashSet<>();
        for (ColumnInfo column : source.columns()) {
            ColumnInfo peer = targetColumns.get(fold(column.name()));
            if (peer == null) {
                onlyInSource.add(column.name());
                continue;
            }
            matched.add(fold(column.name()));
            if (isUncomparable(column.type()) || isUncomparable(peer.type())) {
                skipped.add(column.name());
                continue;
            }
            columns.add(column.name());
        }
        List<String> onlyInTarget = target.columns().stream()
                .map(ColumnInfo::name)
                .filter(name -> !matched.contains(fold(name)))
                .toList();

        if (!onlyInSource.isEmpty()) warnings.add("只有源表有的字段（未参与对比）：" + String.join("、", onlyInSource));
        if (!onlyInTarget.isEmpty()) warnings.add("只有目标表有的字段（未参与对比）：" + String.join("、", onlyInTarget));
        if (!skipped.isEmpty()) {
            warnings.add("二进制与大对象字段不参与对比：" + String.join("、", skipped)
                    + "（按文本读取不可靠，且会让行数上限失去意义）");
        }
        return columns;
    }

    /** 按类型名判断，而不是 JDBC 类型码：类型名是元数据里现成的，各家对 LOB 的命名也足够一致。 */
    private static boolean isUncomparable(String typeName) {
        if (typeName == null) return false;
        String upper = typeName.toUpperCase(Locale.ROOT);
        return upper.contains("BLOB") || upper.contains("CLOB") || upper.contains("BINARY")
                || upper.contains("IMAGE") || upper.contains("BYTEA") || upper.contains("RAW");
    }

    private static List<String> keyColumns(
            DataDiffRequest request,
            ObjectDetail source,
            ObjectDetail target,
            List<String> columns
    ) {
        List<String> requested = request.keyColumns() == null ? List.of() : request.keyColumns().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .toList();
        List<String> keys = requested.isEmpty()
                ? (target.primaryKeys().isEmpty() ? source.primaryKeys() : target.primaryKeys())
                : requested;
        if (keys.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "DATA_DIFF_NO_KEY",
                    "两张表都没有主键，无法逐行对比。请指定用于匹配行的字段。");
        }
        Set<String> available = new LinkedHashSet<>();
        for (String column : columns) available.add(fold(column));
        for (ColumnInfo column : source.columns()) available.add(fold(column.name()));
        for (String key : keys) {
            if (!available.contains(fold(key))) {
                throw new IllegalArgumentException("用于匹配行的字段在表里不存在：" + key);
            }
        }
        return List.copyOf(keys);
    }

    /**
     * 读一侧的全部行。
     *
     * <p>不加 ORDER BY：比对按主键哈希，不依赖顺序，省掉数据库那一次排序。</p>
     */
    private Map<String, DataComparison.Row> readRows(
            long connectionId,
            String schemaName,
            String tableName,
            DatabaseDialect dialect,
            List<String> keyColumns,
            List<String> columns,
            String side
    ) throws Exception {
        List<String> selected = new ArrayList<>(keyColumns);
        for (String column : columns) {
            if (selected.stream().noneMatch(name -> fold(name).equals(fold(column)))) selected.add(column);
        }
        String sql = "SELECT " + String.join(", ", selected.stream().map(dialect::quoteIdentifier).toList())
                + " FROM " + dialect.qualifiedName(schemaName, tableName);

        Map<String, DataComparison.Row> rows = new LinkedHashMap<>();
        try (Connection connection = connections.open(connectionId, schemaName);
             Statement statement = connection.createStatement()) {
            dialect.configureReadStatement(connection, statement, 1_000, properties.getSql().getTimeoutSeconds());
            statement.setMaxRows(MAX_ROWS_PER_SIDE + 1);
            try (ResultSet rs = statement.executeQuery(sql)) {
                Map<String, Integer> position = new LinkedHashMap<>();
                for (int index = 0; index < selected.size(); index++) position.put(fold(selected.get(index)), index + 1);
                while (rs.next()) {
                    if (rows.size() >= MAX_ROWS_PER_SIDE) {
                        throw new ApiProblemException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_DIFF_TOO_MANY_ROWS",
                                side + "表超过 " + MAX_ROWS_PER_SIDE + " 行，超出逐行对比的规模上限。"
                                        + "请改为对比数据量更小的表，或先用备份/校验和缩小范围。");
                    }
                    List<String> key = new ArrayList<>(keyColumns.size());
                    for (String column : keyColumns) key.add(rs.getString(position.get(fold(column))));
                    List<String> values = new ArrayList<>(columns.size());
                    for (String column : columns) values.add(rs.getString(position.get(fold(column))));
                    DataComparison.Row row = new DataComparison.Row(key, values);
                    // 主键重复说明这个键选错了（不是唯一），继续比下去只会给出没有意义的结论。
                    if (rows.putIfAbsent(DataComparison.keyOf(key), row) != null) {
                        throw new ApiProblemException(HttpStatus.CONFLICT, "DATA_DIFF_DUPLICATE_KEY",
                                side + "表里有重复的匹配键：" + DataComparison.keyOf(key).replace((char) 0, '/')
                                        + "。请换一组能唯一确定一行的字段。");
                    }
                }
            }
        }
        return rows;
    }

    private static List<DataDiffRow> rows(DataComparison.Result result, List<String> columns) {
        List<DataDiffRow> rows = new ArrayList<>();
        for (DataComparison.Difference difference : result.differences()) {
            rows.add(new DataDiffRow(
                    difference.key(),
                    difference.change().name(),
                    difference.columns(),
                    clamp(difference.source(), columns),
                    clamp(difference.target(), columns)));
        }
        return List.copyOf(rows);
    }

    private static List<String> clamp(DataComparison.Row row, List<String> columns) {
        if (row == null) return List.of();
        List<String> values = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            String value = index < row.values().size() ? row.values().get(index) : null;
            values.add(value == null || value.length() <= MAX_CELL_CHARS
                    ? value : value.substring(0, MAX_CELL_CHARS) + "…");
        }
        return values;
    }

    private String resolveSchema(long connectionId, String requested) throws Exception {
        if (requested != null && !requested.isBlank()) return requested.trim();
        String selected = metadata.inspect(connectionId, null, null, 0, 1, false).selectedSchema();
        if (selected == null || selected.isBlank()) {
            throw new IllegalArgumentException("无法确定要对比的 Schema/数据库，请显式指定。");
        }
        return selected;
    }

    private static SchemaDiffEndpoint endpoint(DbConnection connection, String schemaName) {
        return new SchemaDiffEndpoint(connection.id(), connection.name(), connection.dbType(), schemaName);
    }

    /** 元数据给出的规范名优先；给不出时退回用户输入的那个。 */
    private static String canonical(String resolved, String requested) {
        return resolved == null || resolved.isBlank() ? requested : resolved;
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String fold(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
