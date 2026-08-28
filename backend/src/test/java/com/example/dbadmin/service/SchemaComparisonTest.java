package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.IndexDesign;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffItem;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaComparisonTest {
    @Test
    void reportsAddedRemovedAndChangedColumns() {
        ObjectDetail source = table(
                List.of(column("ID", "BIGINT", 19, false, null), column("NOTE", "VARCHAR", 200, true, null)),
                List.of(), List.of("ID"), "PK_ORDERS");
        ObjectDetail target = table(
                List.of(column("ID", "BIGINT", 19, false, null), column("LEGACY", "VARCHAR", 40, true, null)),
                List.of(), List.of("ID"), "PK_ORDERS");

        List<SchemaDiffItem> items = SchemaComparison.compare(source, target);

        assertThat(items).extracting(SchemaDiffItem::name, SchemaDiffItem::change)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("NOTE", SchemaComparison.CHANGE_ADDED),
                        org.assertj.core.groups.Tuple.tuple("LEGACY", SchemaComparison.CHANGE_REMOVED));
    }

    @Test
    void detectsColumnAttributeChanges() {
        ObjectDetail source = table(List.of(column("AMOUNT", "DECIMAL", 12, false, "0")), List.of(), List.of(), null);
        ObjectDetail target = table(List.of(column("AMOUNT", "DECIMAL", 12, true, null)), List.of(), List.of(), null);

        List<SchemaDiffItem> items = SchemaComparison.compare(source, target);

        assertThat(items).singleElement()
                .satisfies(item -> {
                    assertThat(item.category()).isEqualTo(SchemaComparison.CATEGORY_COLUMN);
                    assertThat(item.change()).isEqualTo(SchemaComparison.CHANGE_CHANGED);
                    assertThat(item.source()).isEqualTo("DECIMAL(12) NOT NULL DEFAULT 0");
                    assertThat(item.target()).isEqualTo("DECIMAL(12)");
                });
    }

    @Test
    void pairsNamesCaseInsensitively() {
        // 同一张表在 Oracle 里是大写、在 MySQL 里是小写，按字面配对会把每个字段都报成一增一删。
        ObjectDetail source = table(List.of(column("id", "BIGINT", 19, false, null)), List.of(), List.of("id"), null);
        ObjectDetail target = table(List.of(column("ID", "BIGINT", 19, false, null)), List.of(), List.of("ID"), null);

        assertThat(SchemaComparison.compare(source, target)).isEmpty();
    }

    @Test
    void comparesIndexesAndSkipsThePrimaryKeyBackingIndex() {
        ObjectDetail source = table(
                List.of(column("ID", "BIGINT", 19, false, null), column("NOTE", "VARCHAR", 200, true, null)),
                List.of(new IndexInfo("PK_ORDERS", "ID", true, 1), new IndexInfo("IDX_NOTE", "NOTE", false, 1)),
                List.of("ID"), "PK_ORDERS");
        ObjectDetail target = table(
                List.of(column("ID", "BIGINT", 19, false, null), column("NOTE", "VARCHAR", 200, true, null)),
                List.of(new IndexInfo("PK_ORDERS", "ID", true, 1)),
                List.of("ID"), "PK_ORDERS");

        assertThat(SchemaComparison.comparableIndexes(source)).extracting(SchemaComparison.IndexShape::name)
                .containsExactly("IDX_NOTE");
        assertThat(SchemaComparison.compare(source, target)).singleElement()
                .satisfies(item -> {
                    assertThat(item.category()).isEqualTo(SchemaComparison.CATEGORY_INDEX);
                    assertThat(item.name()).isEqualTo("IDX_NOTE");
                    assertThat(item.change()).isEqualTo(SchemaComparison.CHANGE_ADDED);
                });
    }

    @Test
    void reportsPrimaryKeyChanges() {
        ObjectDetail source = table(List.of(column("ID", "BIGINT", 19, false, null)), List.of(), List.of("ID"), null);
        ObjectDetail target = table(List.of(column("ID", "BIGINT", 19, false, null)), List.of(), List.of(), null);

        assertThat(SchemaComparison.compare(source, target)).singleElement()
                .satisfies(item -> {
                    assertThat(item.category()).isEqualTo(SchemaComparison.CATEGORY_PRIMARY_KEY);
                    assertThat(item.target()).isEqualTo("（无主键）");
                });
    }

    @Test
    void alignmentDesignCoversEveryTargetColumnAndIndex() {
        // 方言的 alterTableSql 要求设计稿覆盖目标表的全部字段与可编辑索引，否则会判定
        // 「设计器未加载」直接报错，所以目标端独有的对象必须原样出现。
        ObjectDetail source = table(
                List.of(column("ID", "BIGINT", 19, false, null), column("NOTE", "VARCHAR", 200, true, null)),
                List.of(new IndexInfo("IDX_NOTE", "NOTE", false, 1)), List.of("ID"), null);
        ObjectDetail target = table(
                List.of(column("ID", "BIGINT", 19, false, null), column("LEGACY", "VARCHAR", 40, true, null)),
                List.of(new IndexInfo("IDX_LEGACY", "LEGACY", false, 1)), List.of("ID"), null);

        TableDesignRequest keep = SchemaComparison.alignmentDesign(source, target, false);
        assertThat(keep.columns()).extracting(ColumnDesign::name, ColumnDesign::originalName, ColumnDesign::deleted)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ID", "ID", false),
                        org.assertj.core.groups.Tuple.tuple("NOTE", null, false),
                        org.assertj.core.groups.Tuple.tuple("LEGACY", "LEGACY", false));
        assertThat(keep.indexes()).extracting(IndexDesign::name, IndexDesign::originalName, IndexDesign::deleted)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("IDX_NOTE", null, false),
                        org.assertj.core.groups.Tuple.tuple("IDX_LEGACY", "IDX_LEGACY", false));

        TableDesignRequest drop = SchemaComparison.alignmentDesign(source, target, true);
        assertThat(drop.columns()).filteredOn(ColumnDesign::deleted).extracting(ColumnDesign::name).containsExactly("LEGACY");
        assertThat(drop.indexes()).filteredOn(IndexDesign::deleted).extracting(IndexDesign::name).containsExactly("IDX_LEGACY");
    }

    @Test
    void creationDesignTargetsTheDestinationSchema() {
        ObjectDetail source = table(List.of(column("ID", "BIGINT", 19, false, null)), List.of(), List.of("ID"), null);

        TableDesignRequest design = SchemaComparison.creationDesign(source, "reporting");

        assertThat(design.schemaName()).isEqualTo("reporting");
        assertThat(design.tableName()).isEqualTo("ORDERS");
        assertThat(design.primaryKeys()).containsExactly("ID");
        assertThat(design.columns()).allSatisfy(column -> assertThat(column.originalName()).isNull());
    }

    private static ObjectDetail table(List<ColumnInfo> columns, List<IndexInfo> indexes,
                                      List<String> primaryKeys, String primaryKeyName) {
        return new ObjectDetail("PUBLIC", "ORDERS", "TABLE", columns, indexes, primaryKeys, primaryKeyName);
    }

    private static ColumnInfo column(String name, String type, int size, boolean nullable, String defaultValue) {
        return new ColumnInfo(name, type, size, nullable, null, 0, defaultValue);
    }
}
