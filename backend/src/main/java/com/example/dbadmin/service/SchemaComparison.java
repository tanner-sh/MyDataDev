package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.IndexDesign;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffItem;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 两张表结构的比较，以及把目标表对齐到源表所需的设计稿。
 *
 * <p>纯逻辑：输入两份 {@code ObjectDetail}，输出差异条目和一份 {@link TableDesignRequest}。
 * 真正的 DDL 由方言按这份设计稿生成 —— 也就是说「结构同步」复用的正是表设计器那条久经使用的
 * 生成路径，而不是另写一套 ALTER 拼装。</p>
 *
 * <p>字段与索引按名字忽略大小写配对：跨库对比时同一张表在 Oracle 里是大写、在 MySQL 里是小写，
 * 按字面配对会把每个字段都报成「一增一删」。</p>
 */
public final class SchemaComparison {
    public static final String STATUS_ONLY_IN_SOURCE = "ONLY_IN_SOURCE";
    public static final String STATUS_ONLY_IN_TARGET = "ONLY_IN_TARGET";
    public static final String STATUS_DIFFERENT = "DIFFERENT";
    public static final String STATUS_IDENTICAL = "IDENTICAL";

    public static final String CATEGORY_COLUMN = "COLUMN";
    public static final String CATEGORY_INDEX = "INDEX";
    public static final String CATEGORY_PRIMARY_KEY = "PRIMARY_KEY";

    public static final String CHANGE_ADDED = "ADDED";
    public static final String CHANGE_REMOVED = "REMOVED";
    public static final String CHANGE_CHANGED = "CHANGED";

    private SchemaComparison() {
    }

    /** 一个索引的可比较形态：JDBC 按「索引 + 列」逐行返回，这里合并成一条。 */
    public record IndexShape(String name, List<String> columns, boolean unique) {
    }

    /**
     * 列出两张表的差异，方向以 source 为准：source 有而 target 没有的记为 ADDED。
     */
    public static List<SchemaDiffItem> compare(ObjectDetail source, ObjectDetail target) {
        List<SchemaDiffItem> items = new ArrayList<>();
        Map<String, ColumnInfo> sourceColumns = byFoldedName(source.columns(), ColumnInfo::name);
        Map<String, ColumnInfo> targetColumns = byFoldedName(target.columns(), ColumnInfo::name);

        for (ColumnInfo column : source.columns()) {
            ColumnInfo counterpart = targetColumns.get(fold(column.name()));
            if (counterpart == null) {
                items.add(new SchemaDiffItem(CATEGORY_COLUMN, column.name(), CHANGE_ADDED, describe(column), null));
            } else if (!sameColumn(column, counterpart)) {
                items.add(new SchemaDiffItem(CATEGORY_COLUMN, column.name(), CHANGE_CHANGED,
                        describe(column), describe(counterpart)));
            }
        }
        for (ColumnInfo column : target.columns()) {
            if (!sourceColumns.containsKey(fold(column.name()))) {
                items.add(new SchemaDiffItem(CATEGORY_COLUMN, column.name(), CHANGE_REMOVED, null, describe(column)));
            }
        }

        Map<String, IndexShape> sourceIndexes = byFoldedName(comparableIndexes(source), IndexShape::name);
        Map<String, IndexShape> targetIndexes = byFoldedName(comparableIndexes(target), IndexShape::name);
        for (IndexShape index : sourceIndexes.values()) {
            IndexShape counterpart = targetIndexes.get(fold(index.name()));
            if (counterpart == null) {
                items.add(new SchemaDiffItem(CATEGORY_INDEX, index.name(), CHANGE_ADDED, describe(index), null));
            } else if (!sameIndex(index, counterpart)) {
                items.add(new SchemaDiffItem(CATEGORY_INDEX, index.name(), CHANGE_CHANGED,
                        describe(index), describe(counterpart)));
            }
        }
        for (IndexShape index : targetIndexes.values()) {
            if (!sourceIndexes.containsKey(fold(index.name()))) {
                items.add(new SchemaDiffItem(CATEGORY_INDEX, index.name(), CHANGE_REMOVED, null, describe(index)));
            }
        }

        if (!sameNames(source.primaryKeys(), target.primaryKeys())) {
            items.add(new SchemaDiffItem(CATEGORY_PRIMARY_KEY, "PRIMARY KEY", CHANGE_CHANGED,
                    describeKey(source.primaryKeys()), describeKey(target.primaryKeys())));
        }
        return List.copyOf(items);
    }

    /**
     * 把目标表对齐到源表的设计稿。
     *
     * <p>方言的 {@code alterTableSql} 要求设计稿覆盖目标表的每一个字段和可编辑索引 —— 没被
     * 提到的会被当成「设计器没加载全」而直接报错。所以目标端独有的对象也必须出现在这里：
     * {@code includeDrops} 为真时标记删除，为假时原样保留（不会生成任何语句）。</p>
     */
    public static TableDesignRequest alignmentDesign(ObjectDetail source, ObjectDetail target, boolean includeDrops) {
        Map<String, ColumnInfo> targetColumns = byFoldedName(target.columns(), ColumnInfo::name);
        List<ColumnDesign> columns = new ArrayList<>();
        for (ColumnInfo column : source.columns()) {
            ColumnInfo counterpart = targetColumns.get(fold(column.name()));
            columns.add(new ColumnDesign(column.name(), column.type(), size(column), column.nullable(),
                    column.defaultValue(), counterpart == null ? null : counterpart.name(), false));
        }
        Map<String, ColumnInfo> sourceColumns = byFoldedName(source.columns(), ColumnInfo::name);
        for (ColumnInfo column : target.columns()) {
            if (sourceColumns.containsKey(fold(column.name()))) continue;
            columns.add(new ColumnDesign(column.name(), column.type(), size(column), column.nullable(),
                    column.defaultValue(), column.name(), includeDrops));
        }

        Map<String, IndexShape> targetIndexes = byFoldedName(comparableIndexes(target), IndexShape::name);
        List<IndexDesign> indexes = new ArrayList<>();
        for (IndexShape index : comparableIndexes(source)) {
            IndexShape counterpart = targetIndexes.get(fold(index.name()));
            indexes.add(new IndexDesign(index.name(), index.columns(), index.unique(),
                    counterpart == null ? null : counterpart.name(), false));
        }
        Map<String, IndexShape> sourceIndexes = byFoldedName(comparableIndexes(source), IndexShape::name);
        for (IndexShape index : targetIndexes.values()) {
            if (sourceIndexes.containsKey(fold(index.name()))) continue;
            indexes.add(new IndexDesign(index.name(), index.columns(), index.unique(), index.name(), includeDrops));
        }

        return new TableDesignRequest(target.schemaName(), target.name(), columns, indexes, source.primaryKeys(), null);
    }

    /** 目标端没有这张表时用的建表设计稿。 */
    public static TableDesignRequest creationDesign(ObjectDetail source, String targetSchema) {
        List<ColumnDesign> columns = source.columns().stream()
                .map(column -> new ColumnDesign(column.name(), column.type(), size(column), column.nullable(),
                        column.defaultValue(), null, false))
                .toList();
        List<IndexDesign> indexes = comparableIndexes(source).stream()
                .map(index -> new IndexDesign(index.name(), index.columns(), index.unique(), null, false))
                .toList();
        return new TableDesignRequest(targetSchema, source.name(), columns, indexes, source.primaryKeys(), null);
    }

    /**
     * 参与比较的索引：合并成一条，并剔除主键背后的那个索引。
     *
     * <p>主键单独作为一类差异呈现；把它背后的唯一索引再报一遍，既重复又会让生成的设计稿被
     * 方言当成「不存在的原索引」而报错。判定规则与 {@code DefaultDialect} 保持一致。</p>
     */
    public static List<IndexShape> comparableIndexes(ObjectDetail detail) {
        Map<String, List<IndexInfo>> grouped = new LinkedHashMap<>();
        for (IndexInfo index : detail.indexes()) {
            if (index.name() == null || index.name().isBlank()) continue;
            grouped.computeIfAbsent(index.name(), ignored -> new ArrayList<>()).add(index);
        }
        List<IndexShape> shapes = new ArrayList<>();
        for (Map.Entry<String, List<IndexInfo>> entry : grouped.entrySet()) {
            List<IndexInfo> rows = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(IndexInfo::ordinalPosition))
                    .toList();
            IndexShape shape = new IndexShape(
                    entry.getKey(),
                    rows.stream().map(IndexInfo::columnName).filter(Objects::nonNull).toList(),
                    rows.get(0).unique());
            if (!isPrimaryBackingIndex(detail, shape)) shapes.add(shape);
        }
        return List.copyOf(shapes);
    }

    private static boolean isPrimaryBackingIndex(ObjectDetail detail, IndexShape index) {
        if (detail.primaryKeys() == null || detail.primaryKeys().isEmpty()) return false;
        String primaryKeyName = detail.primaryKeyName();
        if (primaryKeyName != null && !primaryKeyName.isBlank() && primaryKeyName.equals(index.name())) return true;
        return index.unique() && sameNames(index.columns(), detail.primaryKeys());
    }

    static boolean sameColumn(ColumnInfo left, ColumnInfo right) {
        return fold(left.type()).equals(fold(right.type()))
                && left.size() == right.size()
                && left.nullable() == right.nullable()
                && Objects.equals(normalizeDefault(left.defaultValue()), normalizeDefault(right.defaultValue()));
    }

    static boolean sameIndex(IndexShape left, IndexShape right) {
        return left.unique() == right.unique() && sameNames(left.columns(), right.columns());
    }

    static String describe(ColumnInfo column) {
        StringBuilder value = new StringBuilder(column.type() == null ? "" : column.type());
        if (column.size() > 0 && (column.type() == null || column.type().indexOf('(') < 0)) {
            value.append('(').append(column.size()).append(')');
        }
        if (!column.nullable()) value.append(" NOT NULL");
        String defaultValue = normalizeDefault(column.defaultValue());
        if (defaultValue != null) value.append(" DEFAULT ").append(defaultValue);
        return value.toString();
    }

    static String describe(IndexShape index) {
        return (index.unique() ? "UNIQUE (" : "(") + String.join(", ", index.columns()) + ")";
    }

    private static String describeKey(List<String> columns) {
        return columns == null || columns.isEmpty() ? "（无主键）" : "(" + String.join(", ", columns) + ")";
    }

    private static Integer size(ColumnInfo column) {
        return column.size() > 0 ? column.size() : null;
    }

    private static boolean sameNames(List<String> left, List<String> right) {
        List<String> a = left == null ? List.of() : left;
        List<String> b = right == null ? List.of() : right;
        if (a.size() != b.size()) return false;
        for (int index = 0; index < a.size(); index++) {
            if (!fold(a.get(index)).equals(fold(b.get(index)))) return false;
        }
        return true;
    }

    private static String normalizeDefault(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static <T> Map<String, T> byFoldedName(List<T> values, java.util.function.Function<T, String> name) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            String key = fold(name.apply(value));
            if (key.isEmpty()) continue;
            result.putIfAbsent(key, value);
        }
        return result;
    }

    private static String fold(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
