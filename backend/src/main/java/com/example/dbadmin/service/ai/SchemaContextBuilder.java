package com.example.dbadmin.service.ai;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.MetadataService;
import com.example.dbadmin.service.ReadOnlyQueryScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组装发给模型的结构上下文。
 *
 * <p>取数走 {@link MetadataService}（因此吃得到 Caffeine 缓存），取样本走一次性只读查询。
 * 这个类只负责「取」，能不能取由 {@link AiSettingsService#requireSharedConnection(long)}
 * 事先决定并以 {@link AiConnectionPolicy} 的形式传进来 —— 两件事分开，闸门才不会被绕过。</p>
 *
 * <p>查不到的表直接跳过而不是报错：SQL 里被认出来的名字可能是别名、CTE 或者拼错的表名，
 * 而「这张表不存在」恰恰常常就是用户要诊断的那个错。</p>
 */
@Component
public class SchemaContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(SchemaContextBuilder.class);
    /** 单元格文本上限：样本行里塞一段 100KB 的 JSON 对诊断没有帮助。 */
    private static final int MAX_CELL_CHARS = 120;
    private static final int SAMPLE_TIMEOUT_SECONDS = 5;

    private final MetadataService metadata;
    private final ConnectionService connections;
    private final DialectRegistry dialects;

    public SchemaContextBuilder(MetadataService metadata, ConnectionService connections, DialectRegistry dialects) {
        this.metadata = metadata;
        this.connections = connections;
        this.dialects = dialects;
    }

    /**
     * 按 SQL 里引用到的表来取结构。
     *
     * @param policy 这条连接的共享策略，决定要不要取样本行
     */
    public SchemaContext forSql(long connectionId, String schemaName, String sql, AiConnectionPolicy policy) {
        return forTables(connectionId, schemaName, SqlTableReferences.extract(sql), policy);
    }

    /**
     * 按自然语言问题选表。
     *
     * <p>候选表来自元数据目录（走缓存），挑哪几张交给 {@link TableSelector}。选不出来时返回空
     * 上下文而不是把整库塞进去 —— 让模型明说「看不到相关的表」，比拿八张无关表编一条 SQL 好。</p>
     */
    public SchemaContext forQuestion(long connectionId, String schemaName, String question, AiConnectionPolicy policy) {
        List<String> candidates;
        try {
            candidates = metadata.completionCatalog(connectionId, schemaName, false).objects().stream()
                    .map(DbObject::name)
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
        } catch (Exception e) {
            log.debug("AI 选表时读取元数据目录失败：{}", e.toString());
            candidates = List.of();
        }
        return forTables(connectionId, schemaName, new LinkedHashSet<>(TableSelector.select(question, candidates)), policy);
    }

    public SchemaContext forTables(long connectionId, String schemaName, Set<String> references, AiConnectionPolicy policy) {
        DbConnection connection = connections.require(connectionId);
        if (references.isEmpty()) return SchemaContext.empty(connection.dbType(), schemaName);

        List<SchemaContext.Table> tables = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean truncated = references.size() > SqlTableReferences.MAX_TABLES;
        for (String reference : references) {
            String[] parts = SqlTableReferences.split(reference);
            String namespace = parts[0] == null || parts[0].isBlank() ? schemaName : parts[0];
            String objectName = parts[1];
            if (objectName == null || objectName.isBlank() || !seen.add(namespace + "." + objectName)) continue;
            SchemaContext.Table table = table(connectionId, connection, namespace, objectName, policy);
            if (table != null) tables.add(table);
        }
        return new SchemaContext(connection.dbType(), schemaName, tables, truncated);
    }

    private SchemaContext.Table table(long connectionId, DbConnection connection, String namespace, String name, AiConnectionPolicy policy) {
        ObjectDetail detail;
        try {
            detail = metadata.detail(connectionId, namespace, name);
        } catch (Exception e) {
            // 表不存在恰恰常常就是要诊断的那个错，所以不往上抛。
            log.debug("AI 结构上下文跳过 {}.{}：{}", namespace, name, e.toString());
            return null;
        }
        if (detail == null || detail.columns() == null || detail.columns().isEmpty()) return null;
        List<SchemaContext.Column> columns = detail.columns().stream()
                .map(column -> new SchemaContext.Column(column.name(), typeOf(column), column.nullable(), column.remarks()))
                .toList();
        List<List<String>> samples = policy != null && policy.sharing().allowsSample()
                ? sampleRows(connectionId, connection, namespace, name, policy.sampleRowLimit())
                : List.of();
        return new SchemaContext.Table(
                detail.schemaName() == null ? namespace : detail.schemaName(),
                detail.name() == null ? name : detail.name(),
                columns,
                detail.primaryKeys() == null ? List.of() : detail.primaryKeys(),
                indexes(detail.indexes()),
                samples
        );
    }

    private static String typeOf(ColumnInfo column) {
        if (column.size() > 0 && column.type() != null && !column.type().contains("(")) {
            return column.type() + "(" + column.size() + ")";
        }
        return column.type() == null ? "UNKNOWN" : column.type();
    }

    /** 同名索引的多个列会分成多行返回，这里按索引名合并成「名称(列, 列)」。 */
    private static List<String> indexes(List<IndexInfo> indexes) {
        if (indexes == null || indexes.isEmpty()) return List.of();
        Map<String, List<String>> byName = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        for (IndexInfo index : indexes) {
            if (index.name() == null || index.columnName() == null) continue;
            byName.computeIfAbsent(index.name(), ignored -> new ArrayList<>()).add(index.columnName());
            unique.putIfAbsent(index.name(), index.unique());
        }
        return byName.entrySet().stream()
                .map(entry -> (Boolean.TRUE.equals(unique.get(entry.getKey())) ? "UNIQUE " : "")
                        + entry.getKey() + "(" + String.join(", ", entry.getValue()) + ")")
                .toList();
    }

    /**
     * 取样本行。
     *
     * <p>只在连接开了样本档时才会走到这里，行数由策略给定。查询包在只读事务里并设了超时：
     * 这是一条用户没有主动发起的查询，绝不能因为它拖住目标库。</p>
     */
    private List<List<String>> sampleRows(long connectionId, DbConnection dbConnection, String namespace, String name, int limit) {
        if (limit <= 0) return List.of();
        DatabaseDialect dialect = dialects.dialectFor(dbConnection);
        String qualified = dialect.qualifiedName(namespace, name);
        String sql = dialect.pageQuery("SELECT * FROM " + qualified, limit, 0);
        try (Connection connection = connections.open(connectionId, namespace);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
             Statement statement = connection.createStatement()) {
            dialect.configureReadStatement(connection, statement, limit, SAMPLE_TIMEOUT_SECONDS);
            statement.setMaxRows(limit);
            try (ResultSet rs = statement.executeQuery(sql)) {
                int columnCount = rs.getMetaData().getColumnCount();
                List<List<String>> rows = new ArrayList<>();
                while (rs.next() && rows.size() < limit) {
                    List<String> row = new ArrayList<>(columnCount);
                    for (int index = 1; index <= columnCount; index++) row.add(cell(rs.getObject(index)));
                    rows.add(row);
                }
                return rows;
            }
        } catch (Exception e) {
            log.debug("AI 样本行读取失败 {}.{}：{}", namespace, name, e.toString());
            return List.of();
        }
    }

    private static String cell(Object value) {
        if (value == null) return "NULL";
        String text = value instanceof byte[] bytes ? "<binary " + bytes.length + " bytes>" : String.valueOf(value);
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= MAX_CELL_CHARS ? text : text.substring(0, MAX_CELL_CHARS) + "…";
    }
}
