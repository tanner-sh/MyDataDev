package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.dto.ApiDtos.TableDataRequest;
import com.example.dbadmin.dto.ApiDtos.TableFilterRule;
import com.example.dbadmin.dto.ApiDtos.TableSortRule;
import com.example.dbadmin.service.TableCursorCodec.CursorState;
import com.example.dbadmin.service.TableValues.BoundValue;
import com.example.dbadmin.service.TableValues.ColumnDescriptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表浏览的查询拼装：筛选 / 排序校验成参数化的 WHERE 与 ORDER BY（带查询指纹，翻页时校验游标没换条件），
 * 再按 keyset 或 offset 分页包成最终的 SQL。
 *
 * <p>从 {@link DataEditService} 拆出来。导出走的也是这里的 {@link #plan}：「界面上 12 行、导出 40 行」
 * 这种对不上，根子就是两边各拼一份 WHERE。</p>
 */
final class TableQueries {
    static TableQueryPlan plan(TableDataRequest request, Map<String, ColumnDescriptor> descriptors, DatabaseDialect dialect) {
        List<TableFilterRule> filters = request.filters() == null ? List.of() : request.filters();
        List<TableSortRule> sorts = request.sorts() == null ? List.of() : request.sorts();
        if (filters.size() > 20) throw new IllegalArgumentException("筛选条件最多 20 条");
        if (sorts.size() > 10) throw new IllegalArgumentException("排序条件最多 10 条");
        String logic = request.filterLogic() == null || request.filterLogic().isBlank()
                ? "AND" : request.filterLogic().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AND", "OR").contains(logic)) throw new IllegalArgumentException("筛选关系只支持 AND 或 OR");

        List<String> predicates = new ArrayList<>();
        List<BoundValue> parameters = new ArrayList<>();
        StringBuilder fingerprint = new StringBuilder("logic=").append(logic).append(';');
        for (TableFilterRule rule : filters) {
            ColumnDescriptor column = TableValues.descriptor(descriptors, rule.column());
            String operator = rule.operator().trim().toUpperCase(Locale.ROOT);
            String quoted = dialect.quoteIdentifier(column.name());
            appendFingerprint(fingerprint, column.name(), operator, rule.value(), rule.secondValue());
            switch (operator) {
                case "EQ", "NE", "GT", "GTE", "LT", "LTE" -> {
                    String value = requiredFilterValue(rule.value(), column.name(), operator);
                    String sqlOperator = switch (operator) {
                        case "EQ" -> "=";
                        case "NE" -> "<>";
                        case "GT" -> ">";
                        case "GTE" -> ">=";
                        case "LT" -> "<";
                        default -> "<=";
                    };
                    predicates.add(quoted + " " + sqlOperator + " ?");
                    parameters.add(new BoundValue(value, column.jdbcType(), column.typeName(), column.name()));
                }
                case "BETWEEN" -> {
                    String first = requiredFilterValue(rule.value(), column.name(), operator);
                    String second = requiredFilterValue(rule.secondValue(), column.name(), operator);
                    predicates.add(quoted + " BETWEEN ? AND ?");
                    parameters.add(new BoundValue(first, column.jdbcType(), column.typeName(), column.name()));
                    parameters.add(new BoundValue(second, column.jdbcType(), column.typeName(), column.name()));
                }
                case "CONTAINS", "NOT_CONTAINS", "STARTS_WITH", "ENDS_WITH" -> {
                    if (!TableValues.textJdbcType(column.jdbcType())) throw new IllegalArgumentException("字段不支持文本匹配：" + column.name());
                    String value = escapeLike(requiredFilterValue(rule.value(), column.name(), operator));
                    String pattern = switch (operator) {
                        case "STARTS_WITH" -> value + "%";
                        case "ENDS_WITH" -> "%" + value;
                        default -> "%" + value + "%";
                    };
                    predicates.add(quoted + (operator.equals("NOT_CONTAINS") ? " NOT LIKE ? ESCAPE '!'" : " LIKE ? ESCAPE '!'"));
                    parameters.add(new BoundValue(pattern, Types.VARCHAR, "VARCHAR", column.name()));
                }
                case "IN" -> {
                    List<String> values = rule.values() == null ? List.of() : rule.values();
                    if (values.isEmpty()) throw new IllegalArgumentException("IN 筛选至少需要一个值：" + column.name());
                    if (values.size() > 100) throw new IllegalArgumentException("IN 筛选最多支持 100 个值：" + column.name());
                    predicates.add(quoted + " IN (" + String.join(", ", java.util.Collections.nCopies(values.size(), "?")) + ")");
                    for (String value : values) {
                        appendFingerprint(fingerprint, value);
                        parameters.add(new BoundValue(value, column.jdbcType(), column.typeName(), column.name()));
                    }
                }
                case "IS_NULL" -> predicates.add(quoted + " IS NULL");
                case "IS_NOT_NULL" -> predicates.add(quoted + " IS NOT NULL");
                default -> throw new IllegalArgumentException("不支持的筛选操作：" + operator);
            }
        }

        List<NormalizedSort> normalizedSorts = new ArrayList<>();
        Set<String> sortedColumns = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (TableSortRule rule : sorts) {
            ColumnDescriptor column = TableValues.descriptor(descriptors, rule.column());
            String direction = rule.direction().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ASC", "DESC").contains(direction)) throw new IllegalArgumentException("排序方向只支持 ASC 或 DESC");
            if (!sortedColumns.add(column.name())) throw new IllegalArgumentException("排序字段重复：" + column.name());
            normalizedSorts.add(new NormalizedSort(column, direction));
            appendFingerprint(fingerprint, column.name(), direction);
        }
        String where = predicates.isEmpty() ? "" : "(" + String.join(" " + logic + " ", predicates) + ")";
        return new TableQueryPlan(where, parameters, normalizedSorts, sha256(fingerprint.toString()));
    }

    private static String requiredFilterValue(String value, String column, String operator) {
        if (value == null) throw new IllegalArgumentException("筛选条件缺少值：" + column + " (" + operator + ")");
        return value;
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static void appendFingerprint(StringBuilder target, String... values) {
        for (String value : values) {
            String safe = value == null ? "<null>" : value;
            target.append(safe.length()).append(':').append(safe).append(';');
        }
    }

    private static String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new IllegalStateException("无法生成查询指纹", e);
        }
    }

    static Query pageQuery(DatabaseDialect dialect, String schemaName, String tableName, List<String> keyColumns, Map<String, ColumnDescriptor> descriptors, String mode, CursorState state, int pageSize, TableQueryPlan plan) {
        StringBuilder base = new StringBuilder("SELECT * FROM ").append(dialect.qualifiedName(schemaName, tableName));
        List<BoundValue> parameters = new ArrayList<>(plan.parameters());
        List<String> predicates = new ArrayList<>();
        if (!plan.whereSql().isBlank()) predicates.add(plan.whereSql());
        if ("KEYSET".equals(mode) && state != null && !state.keyValues().isEmpty()) {
            if (state.keyValues().size() != keyColumns.size()) throw new IllegalArgumentException("分页游标字段数量不匹配");
            List<String> alternatives = new ArrayList<>();
            for (int index = 0; index < keyColumns.size(); index++) {
                List<String> parts = new ArrayList<>();
                for (int prior = 0; prior < index; prior++) {
                    parts.add(dialect.quoteIdentifier(keyColumns.get(prior)) + " = ?");
                    ColumnDescriptor column = TableValues.descriptor(descriptors, keyColumns.get(prior));
                    parameters.add(new BoundValue(state.keyValues().get(prior), column.jdbcType(), column.typeName(), column.name()));
                }
                parts.add(dialect.quoteIdentifier(keyColumns.get(index)) + " > ?");
                ColumnDescriptor column = TableValues.descriptor(descriptors, keyColumns.get(index));
                parameters.add(new BoundValue(state.keyValues().get(index), column.jdbcType(), column.typeName(), column.name()));
                alternatives.add("(" + String.join(" AND ", parts) + ")");
            }
            predicates.add("(" + String.join(" OR ", alternatives) + ")");
        }
        if (!predicates.isEmpty()) base.append(" WHERE ").append(String.join(" AND ", predicates));
        if ("KEYSET".equals(mode)) {
            base.append(" ORDER BY ").append(keyColumns.stream().map(dialect::quoteIdentifier).collect(Collectors.joining(", ")));
        } else if (!plan.sorts().isEmpty()) {
            List<String> orderBy = new ArrayList<>();
            Set<String> sorted = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (NormalizedSort sort : plan.sorts()) {
                orderBy.add(dialect.quoteIdentifier(sort.column().name()) + " " + sort.direction());
                sorted.add(sort.column().name());
            }
            for (String keyColumn : keyColumns) {
                if (sorted.add(keyColumn)) orderBy.add(dialect.quoteIdentifier(keyColumn) + " ASC");
            }
            base.append(" ORDER BY ").append(String.join(", ", orderBy));
        }
        int offset = "OFFSET".equals(mode) && state != null ? Math.toIntExact(state.offset()) : 0;
        return new Query(dialect.pageQuery(base.toString(), pageSize + 1, offset), parameters);
    }

    record Query(String sql, List<BoundValue> parameters) {
    }

    record TableQueryPlan(String whereSql, List<BoundValue> parameters, List<NormalizedSort> sorts, String fingerprint) {
        TableQueryPlan {
            parameters = List.copyOf(parameters);
            sorts = List.copyOf(sorts);
        }
    }

    record NormalizedSort(ColumnDescriptor column, String direction) {
    }

    private TableQueries() {
    }
}
