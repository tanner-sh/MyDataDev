package com.example.dbadmin.service;

import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ResultEditInfo;
import com.example.dbadmin.dto.ApiDtos.ResultSourceTable;
import com.example.dbadmin.model.DbConnection;

import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Set;

/**
 * 判断一段查询结果能不能在结果网格里就地编辑，能的话备好行定位令牌的生成器。
 *
 * <p>判定一律从严：来源表要么驱动明确报出、要么从 SQL 文本认出后再逐列印证；有别名、表达式、
 * 多表来源或任何一列说不清归属，都给「不可编辑」并写明原因 —— 宁可不让改，也不能写到另一行、另一个字段上。
 * 任何一步出错都只是「不可编辑」，绝不让一次成功的查询因此报错。</p>
 *
 * <p>从 {@link SqlService} 拆出来。</p>
 */
final class ResultEditability {
    /** 驱动没报表名、SQL 文本也认不出唯一来源表时的说明。 */
    private static final String SOURCE_TABLE_UNKNOWN =
            "无法确定这批结果来自哪张表（驱动没有报告，这条 SQL 也不是对单张表的直接查询），因此无法就地编辑；"
                    + "在资源树里打开目标表可以直接改数据。";

    private final DialectRegistry dialectRegistry;
    private final DataEditService dataEdit;

    ResultEditability(DialectRegistry dialectRegistry, DataEditService dataEdit) {
        this.dialectRegistry = dialectRegistry;
        this.dataEdit = dataEdit;
    }

    /**
     * 判断这段结果能不能就地编辑，并在可以时准备好行定位令牌生成器。
     *
     * <p>任何一步失败都只是「不可编辑」，绝不能让一次成功的查询因为编辑能力探测而报错 ——
     * 权限不足读不到主键元数据是很常见的情况。</p>
     */
    EditableResult probe(
            Connection connection,
            DbConnection dbConnection,
            ResultSourceTable sourceTable,
            ResultSetMetaData metadata,
            int visibleColumnCount,
            String executionSql,
            String schemaName
    ) {
        boolean fromSqlText = false;
        ResultSourceTable resolved = sourceTable != null && !sourceTable.nameParts().isEmpty() ? sourceTable : null;
        if (resolved == null) {
            // 驱动一列都不报表名（Oracle 的 ojdbc 就是这样）时，退回按 SQL 文本认来源表。
            // 各列报的表名互相对不上则是真的多表结果，那种情况不该兜底。
            if (ResultSetSourceResolver.classifyUnknownSource(metadata, visibleColumnCount)
                    != ResultSetSourceResolver.UnknownSource.NO_TABLE_NAMES) {
                return new EditableResult(null, "查询结果来自多张表，无法就地编辑");
            }
            resolved = SelectSourceTable.parse(executionSql, dbConnection.dbType(), schemaName, dialectRegistry.dialectFor(dbConnection));
            if (resolved == null) return new EditableResult(null, SOURCE_TABLE_UNKNOWN);
            fromSqlText = true;
        }
        // 别名会让「界面上的这一列」与「表里的哪个字段」对不上，而 JDBC 元数据分辨不出别名，
        // 详见 SelectProjection。分辨不了就不给编辑，否则可能定位到另一行、写错另一个字段。
        if (!SelectProjection.isDirectColumnProjection(executionSql)) {
            return new EditableResult(null, "查询结果使用了别名或表达式，无法对应到表字段");
        }
        List<String> parts = resolved.nameParts();
        String table = parts.get(parts.size() - 1);
        String schema = parts.size() > 1 ? parts.get(parts.size() - 2) : null;
        if (fromSqlText) {
            // 表名是从 SQL 文本认出来的，驱动没法印证。落编辑令牌之前先要求结果里的每一列都是
            // 这张表真实存在的字段：认错表时这一步几乎必然不过，而它同时也证明了这张表读得到。
            String mismatch = columnsMissingFromTableReason(connection, dbConnection, schema, table, metadata, visibleColumnCount);
            if (mismatch != null) return new EditableResult(null, mismatch);
        } else {
            // ResultSetSourceResolver 会跳过没有报告表名的列（表达式、别名、部分驱动），这对推断
            // 导出目标表是合适的宽松度，但用来决定「能不能改这张表」就太松了：一次 JOIN 里只要有
            // 一侧的列没报表名，就会被当成单表来源。编辑路径要求每一个可见列都明确属于同一张表。
            String mismatch = columnsOutsideTableReason(metadata, visibleColumnCount, table);
            if (mismatch != null) return new EditableResult(null, mismatch);
        }
        try {
            DataEditService.ResultRowLocator locator = dataEdit.resultRowLocator(
                    connection, dbConnection, schema, table, metadata, visibleColumnCount
            );
            if (locator == null || !locator.editable()) {
                return new EditableResult(null, locator == null ? "无法确认行定位字段" : locator.reason());
            }
            return new EditableResult(locator, null);
        } catch (Exception error) {
            return new EditableResult(null, "无法确认行定位字段：" + SqlService.abbreviate(error.getMessage()));
        }
    }

    /**
     * 表名是从 SQL 文本认出来的，这里再要求结果里的每一列都是这张表真实存在的字段。
     *
     * <p>{@code editableColumns} 走的是 {@code SELECT * FROM 表 WHERE 1 = 0}，所以它同时回答了
     * 「这张表存不存在、读不读得到」。读不到就当作认不出来源表，绝不放行。</p>
     */
    private String columnsMissingFromTableReason(Connection connection, DbConnection dbConnection, String schema,
                                                 String table, ResultSetMetaData metadata, int visibleColumnCount) {
        try {
            Set<String> tableColumns = dataEdit.editableColumns(connection, dbConnection, schema, table);
            for (int index = 1; index <= visibleColumnCount; index++) {
                String label = metadata.getColumnLabel(index);
                if (tableColumns.stream().noneMatch(name -> name.equalsIgnoreCase(label))) {
                    return "结果列 " + label + " 不是 " + table + " 的字段，无法确认改动写回哪里";
                }
            }
            return null;
        } catch (Exception error) {
            return SOURCE_TABLE_UNKNOWN;
        }
    }

    /** 说明为什么这些列对不上同一张表；对得上返回 null。 */
    private String columnsOutsideTableReason(ResultSetMetaData metadata, int visibleColumnCount, String table) {
        try {
            boolean someColumnHasNoTable = false;
            for (int index = 1; index <= visibleColumnCount; index++) {
                String candidate = metadata.getTableName(index);
                if (candidate == null || candidate.isBlank()) {
                    someColumnHasNoTable = true;
                    continue;
                }
                if (!candidate.equalsIgnoreCase(table)) return "查询结果来自多张表，无法定位行";
            }
            return someColumnHasNoTable ? "结果里有列没有报告所属的表，无法确认改动写回哪里" : null;
        } catch (Exception error) {
            return SOURCE_TABLE_UNKNOWN;
        }
    }

    /**
     * 「不可编辑」的说明由 {@link #editableResult} 一处给全 —— 它才知道卡在了哪一步。
     */
    ResultEditInfo editInfo(EditableResult editable, List<String> rowKeyTokens) {
        if (editable.locator() == null) {
            return ResultEditInfo.notEditable(editable.reason() == null ? SOURCE_TABLE_UNKNOWN : editable.reason());
        }
        return new ResultEditInfo(
                true,
                editable.locator().schemaName(),
                editable.locator().tableName(),
                editable.locator().keyColumns(),
                rowKeyTokens,
                null
        );
    }

    record EditableResult(DataEditService.ResultRowLocator locator, String reason) {
    }

}
