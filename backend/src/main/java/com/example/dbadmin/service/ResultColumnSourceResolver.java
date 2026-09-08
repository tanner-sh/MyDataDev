package com.example.dbadmin.service;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.ResultColumnSource;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

/** 只为备注提供来源，不参与查询改写、结果编辑或行定位。拿不准的列保持无来源。 */
final class ResultColumnSourceResolver {
    private ResultColumnSourceResolver() { }

    static List<ResultColumn> resolve(List<ResultColumn> columns, ResultSetMetaData metadata,
                                      String sql, String dbType, long connectionId,
                                      String schemaName, DatabaseDialect dialect) {
        try {
            var statement = SQLUtils.parseSingleStatement(sql, new SqlRestoreTranslator().dbType(dbType));
            if (!(statement instanceof SQLSelectStatement select) || select.getSelect().getWithSubQuery() != null
                    || !(select.getSelect().getQuery() instanceof SQLSelectQueryBlock query)) return columns;
            List<Table> tables = new ArrayList<>();
            if (!collectTables(query.getFrom(), tables, schemaName) || tables.isEmpty()) return columns;
            var projections = query.getSelectList();
            long wildcards = projections.stream().filter(item -> wildcard(item.getExpr())).count();
            // 一个星号可由结果列数确定展开区间；多个星号无法可靠划分投影边界。
            if (wildcards > 1) return columns;
            int wildcardSize = columns.size() - projections.size() + 1;
            if (wildcards == 0 && projections.size() != columns.size() || wildcards == 1 && wildcardSize < 1) return columns;
            List<ResultColumn> resolved = new ArrayList<>();
            int index = 0;
            for (var projection : projections) {
                SQLExpr expression = projection.getExpr();
                int count = wildcard(expression) ? wildcardSize : 1;
                for (int offset = 0; offset < count; offset++) {
                    ResultColumn column = columns.get(index);
                    Table table = resolveTable(expression, tables, metadata, index + 1, dialect);
                    String name = wildcard(expression) ? column.label() : columnName(expression);
                    ResultColumnSource source = table == null || name == null ? null
                            : new ResultColumnSource(connectionId, table.schema(), table.name(), name);
                    resolved.add(new ResultColumn(column.key(), column.label(), column.typeName(), source));
                    index++;
                }
            }
            return resolved;
        } catch (Exception | LinkageError ignored) {
            return columns;
        }
    }

    private static Table resolveTable(SQLExpr expr, List<Table> tables, ResultSetMetaData metadata, int index, DatabaseDialect dialect) {
        if (!wildcard(expr) && columnName(expr) == null) return null;
        if (expr instanceof SQLPropertyExpr property) {
            String owner = property.getOwner().toString();
            List<Table> matches = tables.stream().filter(table -> table.matches(owner)).toList();
            return matches.size() == 1 ? matches.get(0) : null;
        }
        if (tables.size() == 1) return tables.get(0);
        try {
            String tableName = metadata.getTableName(index);
            String namespace = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG
                    ? metadata.getCatalogName(index) : metadata.getSchemaName(index);
            List<Table> matches = tables.stream().filter(table -> same(table.name(), tableName)
                    && (namespace == null || namespace.isBlank() || same(table.schema(), namespace))).toList();
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean collectTables(SQLTableSource source, List<Table> tables, String schema) {
        if (source instanceof SQLJoinTableSource join) {
            return collectTables(join.getLeft(), tables, schema) && collectTables(join.getRight(), tables, schema);
        }
        if (!(source instanceof SQLExprTableSource physical)) return false;
        SQLExpr expr = physical.getExpr();
        if (expr instanceof SQLIdentifierExpr identifier) {
            tables.add(new Table(schema, unquote(identifier.getName()), physical.getAlias()));
        } else if (expr instanceof SQLPropertyExpr property && property.getOwner() instanceof SQLIdentifierExpr owner) {
            tables.add(new Table(unquote(owner.getName()), unquote(property.getName()), physical.getAlias()));
        } else return false;
        return true;
    }

    private static boolean wildcard(SQLExpr expr) {
        return expr instanceof SQLAllColumnExpr || expr instanceof SQLPropertyExpr property && property.getName().equals("*");
    }

    private static String columnName(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr identifier) return unquote(identifier.getName());
        if (expr instanceof SQLPropertyExpr property) return unquote(property.getName());
        return null;
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && unquote(left).equalsIgnoreCase(unquote(right));
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0), last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
            return value.substring(1, value.length() - 1).replace("" + last + last, "" + last);
        }
        return value;
    }

    private record Table(String schema, String name, String alias) {
        boolean matches(String owner) {
            return same(alias, owner) || same(name, owner) || schema != null && same(schema + "." + name, owner);
        }
    }
}
