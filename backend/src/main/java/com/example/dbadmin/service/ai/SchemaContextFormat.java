package com.example.dbadmin.service.ai;

import java.util.List;

/**
 * 把结构上下文渲染成模型读的文本。
 *
 * <p>纯逻辑，因为「发出去的到底长什么样」是这个功能里最该能被测到的一件事：一旦渲染里
 * 混进了本不该出网的东西（比如样本行在只结构档也被写进去），测试要能当场发现。</p>
 *
 * <p>用类 DDL 的紧凑写法而不是 JSON：同样的信息 JSON 要多花三成 token，而模型对建表语句
 * 的形状比对嵌套对象更熟。</p>
 */
public final class SchemaContextFormat {
    /** 单张表最多列出多少列。宽表（几百列）全发出去会把上下文挤满，也没有诊断价值。 */
    public static final int MAX_COLUMNS_PER_TABLE = 60;
    /** 渲染结果的字符上限，超过就截断并明说。 */
    public static final int MAX_CHARS = 12_000;

    private SchemaContextFormat() {
    }

    public static String render(SchemaContext context) {
        if (context == null || context.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        text.append("数据库类型：").append(context.dbType() == null ? "未知" : context.dbType()).append('\n');
        if (context.namespace() != null && !context.namespace().isBlank()) {
            text.append("当前命名空间：").append(context.namespace()).append('\n');
        }
        text.append('\n');
        for (SchemaContext.Table table : context.tables()) {
            appendTable(text, table);
            if (text.length() > MAX_CHARS) {
                return text.substring(0, MAX_CHARS) + "\n-- 结构上下文过长已截断 --\n";
            }
        }
        if (context.truncated()) {
            text.append("-- 还有更多表未列出 --\n");
        }
        return text.toString();
    }

    private static void appendTable(StringBuilder text, SchemaContext.Table table) {
        text.append("表 ").append(qualified(table)).append(" (\n");
        List<SchemaContext.Column> columns = table.columns();
        int shown = Math.min(columns.size(), MAX_COLUMNS_PER_TABLE);
        for (int index = 0; index < shown; index++) {
            SchemaContext.Column column = columns.get(index);
            text.append("  ").append(column.name()).append(' ').append(column.type());
            if (!column.nullable()) text.append(" NOT NULL");
            if (column.remarks() != null && !column.remarks().isBlank()) {
                text.append(" -- ").append(column.remarks().replace('\n', ' ').trim());
            }
            text.append('\n');
        }
        if (columns.size() > shown) {
            text.append("  -- 另有 ").append(columns.size() - shown).append(" 列未列出\n");
        }
        text.append(")\n");
        if (!table.primaryKeys().isEmpty()) {
            text.append("  主键：").append(String.join(", ", table.primaryKeys())).append('\n');
        }
        if (!table.indexes().isEmpty()) {
            text.append("  索引：").append(String.join("；", table.indexes())).append('\n');
        }
        if (!table.sampleRows().isEmpty()) {
            text.append("  样本行（").append(table.sampleRows().size()).append(" 行）：\n");
            for (List<String> row : table.sampleRows()) {
                text.append("    ").append(String.join(" | ", row)).append('\n');
            }
        }
        text.append('\n');
    }

    private static String qualified(SchemaContext.Table table) {
        return table.namespace() == null || table.namespace().isBlank()
                ? table.name()
                : table.namespace() + "." + table.name();
    }
}
