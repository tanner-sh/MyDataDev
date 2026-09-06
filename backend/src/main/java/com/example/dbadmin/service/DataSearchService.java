package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.CellSerializer;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DataSearchColumnHit;
import com.example.dbadmin.dto.ApiDtos.DataSearchRequest;
import com.example.dbadmin.dto.ApiDtos.DataSearchResponse;
import com.example.dbadmin.dto.ApiDtos.DataSearchTableHit;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全库数据检索：在一个 Schema 的所有表里找一个值。
 *
 * <p>「这个手机号在哪张表里」「这个订单号还有谁引用了」—— 排障时这是最常问的一句，而资源树
 * 的搜索只搜得到对象名。</p>
 *
 * <p>三条设计约定：</p>
 * <ul>
 *   <li><b>比较语义与结果表格的筛选完全同源</b>（{@link SqlResultPushdown#textExpression} +
 *       {@link SqlResultPushdown#like}）。「搜到了」和「筛出来」必须是同一件事，否则用户从
 *       检索结果点进那张表，会发现筛不出刚才看到的那一行。</li>
 *   <li><b>宁可限制规模，也不要给出一个说不清边界的结论。</b>扫描有表数上限、每表行数上限和
 *       总时间预算，任何一条触顶都如实写进 {@code stopReason}，而不是把「扫了 40 张表就没时间
 *       了」报告成「全库只有这些」—— 后者会让人放心地得出错误结论。</li>
 *   <li><b>二进制与大对象列不参与检索</b>，跳过了哪些写进 warnings；理由与数据对比同源：按文本
 *       读不可靠。</li>
 * </ul>
 *
 * <p>只读，不改任何东西。生成的每条命中查询原样返回，用户可以送进 SQL 工作台自己跑一遍 ——
 * 那条路上的生产确认与审计一个都不少。</p>
 */
@Service
public class DataSearchService {
    private static final Logger log = LoggerFactory.getLogger(DataSearchService.class);

    static final int MAX_KEYWORD_CHARS = 200;
    static final int DEFAULT_MAX_TABLES = 300;
    static final int MAX_MAX_TABLES = 2000;
    static final int DEFAULT_ROWS_PER_TABLE = 5;
    static final int MAX_ROWS_PER_TABLE = 50;
    static final int DEFAULT_BUDGET_SECONDS = 20;
    static final int MAX_BUDGET_SECONDS = 120;
    /** 一次检索最多回传多少张命中表，避免一个太宽泛的词把整个响应撑爆。 */
    static final int MAX_MATCHED_TABLES = 200;
    /** 目录分页的页大小上限，由 {@link MetadataService#inspect} 决定。 */
    private static final int CATALOG_PAGE_SIZE = 500;
    /** 样例值在响应里的截断长度：这里只是给人认一眼，不是取数。 */
    private static final int SAMPLE_CHARS = 200;

    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final MetadataService metadata;
    private final ExecutionGuard executionGuard;
    private final AppProperties properties;
    private final AuditRepository audit;

    public DataSearchService(
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            MetadataService metadata,
            ExecutionGuard executionGuard,
            AppProperties properties,
            AuditRepository audit
    ) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.metadata = metadata;
        this.executionGuard = executionGuard;
        this.properties = properties;
        this.audit = audit;
    }

    public DataSearchResponse search(DataSearchRequest request, String actor, String productionConfirmation)
            throws Exception {
        String keyword = request.keyword() == null ? "" : request.keyword().trim();
        if (keyword.isEmpty()) throw new IllegalArgumentException("请输入要检索的内容。");
        if (keyword.length() > MAX_KEYWORD_CHARS) {
            throw new IllegalArgumentException("检索内容最多 " + MAX_KEYWORD_CHARS + " 个字符。");
        }
        boolean exact = "EQUALS".equalsIgnoreCase(request.mode());
        int maxTables = clamp(request.maxTables(), DEFAULT_MAX_TABLES, 1, MAX_MAX_TABLES);
        int rowsPerTable = clamp(request.rowsPerTable(), DEFAULT_ROWS_PER_TABLE, 1, MAX_ROWS_PER_TABLE);
        int budgetSeconds = clamp(request.budgetSeconds(), DEFAULT_BUDGET_SECONDS, 1, MAX_BUDGET_SECONDS);

        DbConnection dbConnection = connections.require(request.connectionId());
        // 全库扫描是一次实打实的负载事件：几百张表各跑一条整表 LIKE，和「打开一张表看看」
        // 完全不是一回事。生产连接上要用户明确点头，与自由 SQL 同档。
        executionGuard.requireQueryAllowed(dbConnection, SqlStatementClassifier.Kind.QUERY, productionConfirmation);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);

        Catalog catalog = listTables(request.connectionId(), request.schemaName(), maxTables);
        List<String> warnings = new ArrayList<>(catalog.warnings());
        List<DataSearchTableHit> hits = new ArrayList<>();
        long deadline = System.nanoTime() + budgetSeconds * 1_000_000_000L;
        int scanned = 0;
        String stopReason = null;

        try (Connection connection = openConnection(request.connectionId(), catalog.schemaName());
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true)) {
            for (DbObject table : catalog.tables()) {
                if (System.nanoTime() >= deadline) {
                    stopReason = "达到 " + budgetSeconds + " 秒的时间上限";
                    break;
                }
                if (hits.size() >= MAX_MATCHED_TABLES) {
                    stopReason = "命中表数量达到 " + MAX_MATCHED_TABLES + " 张的上限";
                    break;
                }
                scanned++;
                try {
                    DataSearchTableHit hit = searchTable(connection, dialect, request.connectionId(),
                            catalog.schemaName(), table, keyword, exact, rowsPerTable, deadline, warnings);
                    if (hit != null) hits.add(hit);
                } catch (Exception error) {
                    // 单张表失败（权限不足、视图背后的表没了、方言不支持某个类型的转换）不该让
                    // 整次检索变成一个错误弹窗；但少扫了一张表同样不能宣称结果完整。
                    warnings.add("跳过 " + table.name() + "：" + rootMessage(error));
                    log.debug("检索表 {} 失败", table.name(), error);
                }
            }
        }
        if (stopReason == null && catalog.truncated()) {
            stopReason = "Schema 里的表超过本次扫描上限 " + maxTables + " 张";
        }

        audit.onConnection(actor, "DATA_SEARCH", request.connectionId(),
                "schema=" + catalog.schemaName() + "; tables=" + scanned + "; matched=" + hits.size());

        return new DataSearchResponse(
                keyword,
                catalog.schemaName(),
                scanned,
                catalog.totalTables(),
                hits.size(),
                stopReason == null,
                stopReason,
                List.copyOf(hits),
                List.copyOf(warnings)
        );
    }

    /**
     * 在一张表里找。
     *
     * <p>一条查询搞定：所有可检索列 OR 在一起，靠 {@code setMaxRows} 封顶。逐列 COUNT 会把
     * 一张表变成几十条查询，在一次全库扫描里是几个数量级的差别。代价是「命中多少行」只在
     * 取回的样本内准确 —— 所以响应里那个数叫「样本行数」，并带上 truncated。</p>
     */
    private DataSearchTableHit searchTable(
            Connection connection,
            DatabaseDialect dialect,
            long connectionId,
            String schemaName,
            DbObject table,
            String keyword,
            boolean exact,
            int rowsPerTable,
            long deadline,
            List<String> warnings
    ) throws Exception {
        String tableSchema = table.schemaName() == null || table.schemaName().isBlank()
                ? schemaName : table.schemaName();
        List<ColumnInfo> columns = metadata.detail(connectionId, tableSchema, table.name()).columns();
        List<String> searchable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ColumnInfo column : columns) {
            if (isUnsearchable(column.type())) skipped.add(column.name());
            else searchable.add(column.name());
        }
        if (searchable.isEmpty()) return null;
        if (!skipped.isEmpty()) {
            warnings.add(table.name() + " 的二进制或大对象字段不参与检索：" + String.join("、", skipped));
        }

        String qualified = dialect.qualifiedName(tableSchema, table.name());
        String value = keyword.toLowerCase(Locale.ROOT);
        String bound = exact ? value : SqlResultPushdown.like(value);
        StringBuilder where = new StringBuilder();
        for (String column : searchable) {
            if (!where.isEmpty()) where.append(" OR ");
            String text = SqlResultPushdown.textExpression(dialect, dialect.quoteIdentifier(column));
            where.append(exact ? text + " = ?" : text + " LIKE ? ESCAPE '" + SqlResultPushdown.LIKE_ESCAPE + "'");
        }
        String projection = String.join(", ", searchable.stream().map(dialect::quoteIdentifier).toList());
        String sql = "SELECT " + projection + " FROM " + qualified + " WHERE " + where;

        int remainingSeconds = (int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000_000L);
        Map<String, DataSearchColumnHit> matched = new LinkedHashMap<>();
        int rows = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            dialect.configureReadStatement(connection, statement, rowsPerTable,
                    Math.min(remainingSeconds, properties.getSql().getTimeoutSeconds()));
            statement.setMaxRows(rowsPerTable + 1);
            for (int index = 0; index < searchable.size(); index++) statement.setString(index + 1, bound);
            try (ResultSet rs = statement.executeQuery()) {
                while (rows < rowsPerTable && rs.next()) {
                    rows++;
                    for (int index = 0; index < searchable.size(); index++) {
                        String text = cellText(rs.getObject(index + 1));
                        if (!textMatches(text, value, exact)) continue;
                        String column = searchable.get(index);
                        DataSearchColumnHit existing = matched.get(column);
                        matched.put(column, existing == null
                                ? new DataSearchColumnHit(column, 1, sample(text))
                                : new DataSearchColumnHit(column, existing.rows() + 1, existing.sample()));
                    }
                }
                boolean truncated = rows >= rowsPerTable && rs.next();
                if (rows == 0) return null;
                return new DataSearchTableHit(tableSchema, table.name(), rows, truncated,
                        List.copyOf(matched.values()), displaySql(dialect, sql, bound, exact, searchable.size()));
            }
        }
    }

    /**
     * 回传给用户的那条查询。
     *
     * <p>参数是绑定进去的，而用户要拿到手的是一条能直接贴进 SQL 工作台跑的语句，所以这里把
     * 同一个值按脚本字面量回填一次。用 {@code scriptLiteral} 而不是 {@code literal}：这条语句
     * 会被复制、保存、稍后再执行，写法不能依赖生成时的会话设置。</p>
     */
    private static String displaySql(DatabaseDialect dialect, String sql, String bound, boolean exact, int columns) {
        String literal = dialect.scriptLiteral(bound);
        StringBuilder result = new StringBuilder(sql.length() + columns * literal.length());
        int from = 0;
        for (int index = 0; index < columns; index++) {
            int mark = sql.indexOf('?', from);
            if (mark < 0) break;
            result.append(sql, from, mark).append(literal);
            from = mark + 1;
        }
        return result.append(sql.substring(from)).toString();
    }

    /** 目录：这个 Schema 下要扫哪些表。视图不扫 —— 它的数据来自底下的表，扫了只会把同一行报两遍。 */
    private Catalog listTables(long connectionId, String schemaName, int maxTables) throws Exception {
        List<DbObject> tables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String resolved = schemaName;
        int total = 0;
        boolean truncated = false;
        for (int page = 0; tables.size() < maxTables; page++) {
            MetadataResponse response = metadata.inspect(connectionId, resolved, null, page, CATALOG_PAGE_SIZE, false);
            resolved = response.selectedSchema();
            total = response.totalObjects();
            for (DbObject object : response.objects()) {
                if (object.type() != null && object.type().toUpperCase(Locale.ROOT).contains("VIEW")) continue;
                if (tables.size() >= maxTables) {
                    truncated = true;
                    break;
                }
                tables.add(object);
            }
            if (!response.hasMore()) break;
            if (tables.size() >= maxTables) truncated = true;
        }
        if (tables.isEmpty()) warnings.add("这个 Schema 下没有可检索的表。");
        return new Catalog(resolved, List.copyOf(tables), total, truncated, List.copyOf(warnings));
    }

    private Connection openConnection(long connectionId, String schemaName) throws Exception {
        return schemaName == null || schemaName.isBlank()
                ? connections.open(connectionId)
                : connections.open(connectionId, schemaName);
    }

    /**
     * 把取回的值还原成参与比较的那种文本。
     *
     * <p>数据库那边比的是 {@code LOWER(CAST(列 AS 文本))}，这里也必须按同一种文本判断哪一列
     * 命中了 —— 否则会出现「这一行确实被查出来了，但一列都标不出来」。</p>
     */
    private static String cellText(Object value) {
        return value == null ? "" : CellSerializer.text(value);
    }

    /**
     * 样例值。
     *
     * <p>{@code CellSerializer.truncate} 带 marker 时一律会补上省略号，短值也不例外 —— 那会让
     * 「13800001111」显示成「13800001111…」，看起来像是还有后半截没显示。</p>
     */
    private static String sample(String text) {
        return text.length() <= SAMPLE_CHARS ? text : CellSerializer.truncate(text, "…", SAMPLE_CHARS);
    }

    private static boolean textMatches(String text, String value, boolean exact) {
        String lower = text.toLowerCase(Locale.ROOT);
        return exact ? lower.equals(value) : lower.contains(value);
    }

    /** 与数据对比跳过二进制列同源：按文本读不可靠。 */
    static boolean isUnsearchable(String type) {
        String upper = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return upper.contains("BLOB") || upper.contains("CLOB") || upper.contains("BINARY")
                || upper.contains("BYTEA") || upper.contains("IMAGE") || upper.contains("RAW");
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        return Math.min(Math.max(value == null ? fallback : value, min), max);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getMessage() == null) current = current.getCause();
        String message = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
        String single = message.replaceAll("\\R", " ");
        return single.length() > 160 ? single.substring(0, 160) + "…" : single;
    }

    private record Catalog(String schemaName, List<DbObject> tables, int totalTables, boolean truncated,
                           List<String> warnings) {
    }
}
