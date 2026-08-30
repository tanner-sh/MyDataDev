package com.example.dbadmin.dto;

import java.util.List;

/**
 * Schema ER 图的数据。
 *
 * <p>只返回参与关系的列（主键与外键两端），不返回全部列：一张画着六十张表、每张几十列的图
 * 没人读得下去，而完整列定义在对象详情里已经有了。这里的职责是关系，不是字典。</p>
 */
public final class SchemaDiagramDtos {
    private SchemaDiagramDtos() {
    }

    public record DiagramColumn(String name, String type, boolean nullable, boolean primaryKey, boolean foreignKey) {
    }

    public record DiagramTable(
            String schemaName,
            String name,
            /** 主键列与外键列，按「先主键后外键、各自保持原始顺序」排列。 */
            List<DiagramColumn> keyColumns,
            /** 该表的总列数，用来在图上标出「另有 N 列」。 */
            int columnCount
    ) {
    }

    /** 一条外键关系。同一个约束跨多列时会拆成多条，前端按 constraintName 合并成一根连线。 */
    public record DiagramRelation(
            String constraintName,
            String fromTable,
            String fromColumn,
            String toTable,
            String toColumn
    ) {
    }

    public record SchemaDiagram(
            String schemaName,
            List<DiagramTable> tables,
            List<DiagramRelation> relations,
            /** schema 里表的总数；大于 tables.size() 时说明被上限截断了。 */
            int totalTables,
            boolean truncated
    ) {
    }
}
