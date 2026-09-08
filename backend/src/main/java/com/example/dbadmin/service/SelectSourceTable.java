package com.example.dbadmin.service;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.dto.ApiDtos.ResultSourceTable;

import java.util.List;

/**
 * 从 SQL 文本里认出「这条查询唯一的来源表」。
 *
 * <p>只在驱动一列都不报表名时用作兜底。Oracle 的 ojdbc 对每一列的
 * {@code ResultSetMetaData.getTableName()} 都返回空串 —— 它压根不带这份元数据回来，于是
 * 结果集就地编辑在 Oracle 上从来没能用过，界面上只有一句「无法确定来源表」。而
 * {@code select * from BD_ACCASOA} 到底查的是哪张表，SQL 文本本身说得清清楚楚。</p>
 *
 * <p><b>判断刻意保守到近乎苛刻</b>：认错表就是把改动写进另一张表，属于数据损坏，比「不给编辑」
 * 严重得多。因此只接受最没有歧义的一种形状 —— 单条 SELECT、FROM 后面正好一张物理表、没有
 * JOIN / 逗号连接 / 子查询 / 表函数、没有 WITH、集合运算、DISTINCT、GROUP BY、HAVING。
 * 认不出来一律返回 null，由调用方继续报「不可编辑」。</p>
 *
 * <p>光认出表名还不够。调用方拿到名字之后还要走三道校验才发编辑令牌：投影必须是直投影
 * （{@link SelectProjection}），结果里的每一列都必须是这张表真实存在的字段，主键字段必须在
 * 结果集里（{@link DataEditService#resultRowLocator}）。三道里任何一道不过就不给编辑，
 * 所以即使这里认错了表，也几乎必然停在后面而不是写错数据。</p>
 */
final class SelectSourceTable {
    private SelectSourceTable() {
    }

    /**
     * @param defaultSchema SQL 里没写 schema 时用的当前 schema，可为空
     * @param dialect       用来把未加引号的标识符折成库里实际存的形态
     * @return 唯一来源表，认不出时返回 null
     */
    static ResultSourceTable parse(String sql, String dbType, String defaultSchema, DatabaseDialect dialect) {
        if (sql == null || sql.isBlank()) return null;
        try {
            var statement = SQLUtils.parseSingleStatement(sql, new SqlRestoreTranslator().dbType(dbType));
            if (!(statement instanceof SQLSelectStatement select)) return null;
            // WITH 之后真正的来源可能是 CTE 而不是物理表；UNION 之类的集合运算连行都不再一一对应。
            if (select.getSelect().getWithSubQuery() != null) return null;
            if (!(select.getSelect().getQuery() instanceof SQLSelectQueryBlock query)) return null;
            // 去重与分组之后，结果里的一行不再对应表里的一行。
            if (query.getDistionOption() != 0 || query.getGroupBy() != null) return null;
            SQLTableSource from = query.getFrom();
            if (!(from instanceof SQLExprTableSource table)) return null;
            return nameParts(table.getExpr(), defaultSchema, dialect);
        } catch (Exception | LinkageError ignored) {
            // 解析不了就是认不出，不该让一次已经成功的查询受影响。
            return null;
        }
    }

    private static ResultSourceTable nameParts(SQLExpr expr, String defaultSchema, DatabaseDialect dialect) {
        if (expr instanceof SQLIdentifierExpr identifier) {
            String name = canonical(identifier.getName(), dialect);
            if (name == null || name.isBlank()) return null;
            return new ResultSourceTable(defaultSchema == null || defaultSchema.isBlank()
                    ? List.of(name) : List.of(defaultSchema, name));
        }
        if (expr instanceof SQLPropertyExpr property && property.getOwner() instanceof SQLIdentifierExpr owner) {
            String schema = canonical(owner.getName(), dialect);
            String name = canonical(property.getName(), dialect);
            if (schema == null || schema.isBlank() || name == null || name.isBlank()) return null;
            return new ResultSourceTable(List.of(schema, name));
        }
        // dblink（t@remote）、表函数、变量等一律不认。
        return null;
    }

    /**
     * 折成库里实际存的形态。
     *
     * <p>用户写 {@code select * from bd_accasoa}，Oracle 存的是 {@code BD_ACCASOA} —— 按原样
     * 拿去查主键会一无所获，一张有主键的表于是被报成「没有主键」。加了引号的名字不折：
     * 那是用户明确写下的大小写。</p>
     */
    private static String canonical(String value, DatabaseDialect dialect) {
        String unquoted = unquote(value);
        return unquoted != null && unquoted.equals(value) ? dialect.foldUnquotedIdentifier(value) : unquoted;
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0), last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
            return value.substring(1, value.length() - 1).replace("" + last + last, "" + last);
        }
        return value;
    }
}
