package com.example.dbadmin.service.ai;

import java.util.List;

/**
 * 发给模型的结构上下文。
 *
 * <p>一张表一条 {@link Table}，渲染成文本之后进系统提示。样本行只有在连接开了样本档时
 * 才会有值，且行数受策略约束 —— 这个记录本身不判断能不能发，判断在 {@link AiSharingRules}。</p>
 */
public record SchemaContext(String dbType, String namespace, List<Table> tables, boolean truncated) {
    public record Table(
            String namespace,
            String name,
            List<Column> columns,
            List<String> primaryKeys,
            List<String> indexes,
            /** 样本行，每行已经按列顺序转成字符串；不发样本时为空。 */
            List<List<String>> sampleRows
    ) {
    }

    public record Column(String name, String type, boolean nullable, String remarks) {
    }

    public static SchemaContext empty(String dbType, String namespace) {
        return new SchemaContext(dbType, namespace, List.of(), false);
    }

    public boolean isEmpty() {
        return tables.isEmpty();
    }
}
