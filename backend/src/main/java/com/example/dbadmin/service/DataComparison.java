package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 两张表的逐行对比，以及把目标端对齐到源端的同步脚本。
 *
 * <p>纯逻辑：取数、连接、权限都不在这里，所以「哪些行算不同」「脚本该怎么写」可以单测。
 * 与 {@link SchemaComparison} 是同一套分工 —— 那边比结构，这边比数据。</p>
 *
 * <p><b>按主键做哈希比对，不做归并。</b>归并要求两侧都按主键排序，而排序取决于数据库的排序
 * 规则：同一批数据在 utf8mb4_general_ci 和 C 排序规则下的顺序并不相同，一旦两侧顺序不一致，
 * 归并会把大量相同的行报成差异。哈希比对不关心顺序，代价是源端要整批进内存 —— 所以行数
 * 必须有上限，由 {@link DataDiffService} 把关。</p>
 *
 * <p>生成的脚本一律交给用户在 SQL 工作台执行，这里不执行任何写操作：生产确认、未限定范围
 * 写操作确认与审计都在那条路上。</p>
 */
final class DataComparison {
    /** 主键各段之间的分隔符。业务数据里不会出现 NUL，用它拼键不会把 ("a|","b") 和 ("a","|b") 撞在一起。 */
    private static final char KEY_SEPARATOR = (char) 0;
    /**
     * NULL 与空串必须区分：一个是「没有值」，一个是「值是空的」，同步脚本里写法完全不同
     * （{@code = NULL} 永远不成立）。用业务数据里不会出现的控制字符当标记，而不是空串。
     */
    private static final String NULL_MARK = String.valueOf((char) 1);

    private DataComparison() {
    }

    /**
     * 一行数据：主键值 + 参与对比的列值。{@code null} 表示 SQL NULL。
     *
     * <p>两个列表都用 {@code unmodifiableList} 包一层而不是 {@code List.copyOf}：后者拒绝
     * null 元素，而主键值本身就可能是 NULL（唯一索引允许，用户自选匹配键时更常见）——
     * 那正是同步脚本里要写成 {@code IS NULL} 的那一类。</p>
     */
    record Row(List<String> key, List<String> values) {
        Row {
            key = java.util.Collections.unmodifiableList(new ArrayList<>(key));
            values = java.util.Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    enum Change { ONLY_IN_SOURCE, ONLY_IN_TARGET, DIFFERENT }

    /** @param columns 值不同的列名；仅 {@link Change#DIFFERENT} 时有内容 */
    record Difference(List<String> key, Change change, List<String> columns, Row source, Row target) {
    }

    /**
     * @param truncated 差异条数达到上限，后面的没有再看 —— 这时同步脚本是不完整的，界面必须说清楚
     */
    record Result(
            int onlyInSource,
            int onlyInTarget,
            int different,
            int identical,
            List<Difference> differences,
            boolean truncated
    ) {
    }

    static String keyOf(List<String> key) {
        StringBuilder text = new StringBuilder();
        for (String part : key) {
            if (!text.isEmpty()) text.append(KEY_SEPARATOR);
            text.append(part == null ? NULL_MARK : part);
        }
        return text.toString();
    }

    /**
     * 比一批源行和一批目标行。
     *
     * @param columns 参与对比的列，顺序与 {@link Row#values()} 一致
     * @param maxDifferences 最多记多少条差异；超过就停下并标记 {@code truncated}
     */
    static Result compare(
            Map<String, Row> source,
            Map<String, Row> target,
            List<String> columns,
            int maxDifferences
    ) {
        int onlyInSource = 0;
        int onlyInTarget = 0;
        int different = 0;
        int identical = 0;
        List<Difference> differences = new ArrayList<>();
        boolean truncated = false;

        for (Map.Entry<String, Row> entry : source.entrySet()) {
            Row targetRow = target.get(entry.getKey());
            if (targetRow == null) {
                onlyInSource++;
                truncated |= !record(differences, maxDifferences,
                        new Difference(entry.getValue().key(), Change.ONLY_IN_SOURCE, List.of(), entry.getValue(), null));
                continue;
            }
            List<String> changed = changedColumns(entry.getValue(), targetRow, columns);
            if (changed.isEmpty()) {
                identical++;
                continue;
            }
            different++;
            truncated |= !record(differences, maxDifferences,
                    new Difference(entry.getValue().key(), Change.DIFFERENT, changed, entry.getValue(), targetRow));
        }
        for (Map.Entry<String, Row> entry : target.entrySet()) {
            if (source.containsKey(entry.getKey())) continue;
            onlyInTarget++;
            truncated |= !record(differences, maxDifferences,
                    new Difference(entry.getValue().key(), Change.ONLY_IN_TARGET, List.of(), null, entry.getValue()));
        }
        return new Result(onlyInSource, onlyInTarget, different, identical, List.copyOf(differences), truncated);
    }

    private static boolean record(List<Difference> differences, int max, Difference difference) {
        if (differences.size() >= max) return false;
        differences.add(difference);
        return true;
    }

    private static List<String> changedColumns(Row source, Row target, List<String> columns) {
        List<String> changed = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            String left = index < source.values().size() ? source.values().get(index) : null;
            String right = index < target.values().size() ? target.values().get(index) : null;
            if (!Objects.equals(left, right)) changed.add(columns.get(index));
        }
        return changed;
    }

    /**
     * 把差异写成同步脚本：源端有目标端没有的插入，两边都有但不同的更新。
     *
     * <p>删除默认不生成。目标端多出来的行往往是目标库自己的数据（不同环境的测试数据、还没同步
     * 回源端的新记录），默认生成 DELETE 等于把「对比」变成一次危险操作 —— 要删得由用户明确要求。</p>
     *
     * <p>更新只写值不同的那几列：整行覆盖会把目标端那些不参与对比的列（比如只有目标库才有的
     * 审计字段）连带改掉。</p>
     */
    static List<String> syncScript(
            Result result,
            DatabaseDialect dialect,
            String qualifiedTable,
            List<String> columns,
            List<String> keyColumns,
            boolean includeDeletes
    ) {
        List<String> statements = new ArrayList<>();
        Map<String, Integer> columnIndex = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) columnIndex.put(columns.get(index), index);

        for (Difference difference : result.differences()) {
            switch (difference.change()) {
                case ONLY_IN_SOURCE -> statements.add(insert(dialect, qualifiedTable, columns, difference.source()));
                case DIFFERENT -> statements.add(update(dialect, qualifiedTable, difference, columnIndex, keyColumns));
                case ONLY_IN_TARGET -> {
                    if (includeDeletes) {
                        statements.add("DELETE FROM " + qualifiedTable
                                + where(dialect, keyColumns, difference.target().key()) + ";");
                    }
                }
            }
        }
        return List.copyOf(statements);
    }

    private static String insert(DatabaseDialect dialect, String table, List<String> columns, Row row) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append(dialect.quoteIdentifier(columns.get(index)));
        }
        sql.append(") VALUES (");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append(literal(dialect, index < row.values().size() ? row.values().get(index) : null));
        }
        return sql.append(");").toString();
    }

    private static String update(
            DatabaseDialect dialect,
            String table,
            Difference difference,
            Map<String, Integer> columnIndex,
            List<String> keyColumns
    ) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        boolean first = true;
        for (String column : difference.columns()) {
            if (!first) sql.append(", ");
            first = false;
            Integer index = columnIndex.get(column);
            String value = index == null || index >= difference.source().values().size()
                    ? null : difference.source().values().get(index);
            sql.append(dialect.quoteIdentifier(column)).append(" = ").append(literal(dialect, value));
        }
        return sql.append(where(dialect, keyColumns, difference.key())).append(";").toString();
    }

    /** NULL 主键值要写成 {@code IS NULL}：{@code = NULL} 永远不成立，那条语句会静默地改不到任何行。 */
    private static String where(DatabaseDialect dialect, List<String> keyColumns, List<String> key) {
        StringBuilder sql = new StringBuilder(" WHERE ");
        for (int index = 0; index < keyColumns.size(); index++) {
            if (index > 0) sql.append(" AND ");
            String value = index < key.size() ? key.get(index) : null;
            sql.append(dialect.quoteIdentifier(keyColumns.get(index)));
            sql.append(value == null ? " IS NULL" : " = " + literal(dialect, value));
        }
        return sql.toString();
    }

    /**
     * 值一律按字符串字面量写，由数据库按目标列类型隐式转换 —— 与 CSV 导入同一条约定：
     * 这里没有类型信息，猜错的代价比多一次隐式转换大。转义交给方言的 {@code scriptLiteral}，
     * 因为这份脚本是先生成后执行的，写法不能依赖生成时的会话设置。
     */
    private static String literal(DatabaseDialect dialect, String value) {
        return value == null ? "NULL" : dialect.scriptLiteral(value);
    }
}
