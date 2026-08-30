package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectRelation;
import com.example.dbadmin.dto.SchemaDiagramDtos.DiagramColumn;
import com.example.dbadmin.dto.SchemaDiagramDtos.DiagramRelation;
import com.example.dbadmin.dto.SchemaDiagramDtos.DiagramTable;
import com.example.dbadmin.dto.SchemaDiagramDtos.SchemaDiagram;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schema 级 ER 图的数据装配。
 *
 * <p>JDBC 元数据没有「一次取出整个 schema 的外键」这种调用，{@code getImportedKeys} 必须按表问。
 * 所以这里是每张表两次元数据往返（结构一次、外键一次），全部走 {@link MetadataService} 已有的
 * 缓存路径 —— 第二次打开同一个 schema 就不再压库。</p>
 *
 * <p>表数有硬上限。既是为了这些往返，也是因为一张画着几百个方块的图本来就读不动：宁可明确告诉
 * 用户「只画了前 N 张」，也不要悄悄画出一张没人看得懂的图。</p>
 */
@Service
public class SchemaDiagramService {
    /** 默认画多少张表。再多，方框就小到看不清表名了。 */
    public static final int DEFAULT_TABLE_LIMIT = 60;
    /** 上限的上限。每张表两次元数据往返，放开了会把远端连接池占住。 */
    public static final int MAX_TABLE_LIMIT = 150;

    private final MetadataService metadata;

    public SchemaDiagramService(MetadataService metadata) {
        this.metadata = metadata;
    }

    public SchemaDiagram build(long connectionId, String schemaName, Integer requestedLimit) throws Exception {
        int limit = Math.min(Math.max(requestedLimit == null ? DEFAULT_TABLE_LIMIT : requestedLimit, 1), MAX_TABLE_LIMIT);
        // 多取一页判断有没有被截断：只按 limit 取的话，恰好等于上限时分不出「正好这么多」和「还有更多」。
        MetadataResponse inspection = metadata.inspect(connectionId, schemaName, null, 0, Math.min(limit + 1, 500), false);
        List<DbObject> tables = inspection.objects().stream().filter(object -> isTable(object.type())).toList();
        boolean truncated = tables.size() > limit || inspection.totalObjects() > tables.size();
        List<DbObject> selected = tables.size() > limit ? tables.subList(0, limit) : tables;

        String resolvedSchema = inspection.selectedSchema();
        Map<String, List<ColumnInfo>> columnsByTable = new LinkedHashMap<>();
        Map<String, Set<String>> primaryKeysByTable = new LinkedHashMap<>();
        List<ObjectRelation> imported = new ArrayList<>();
        for (DbObject table : selected) {
            ObjectDetail detail = metadata.detail(connectionId, table.schemaName(), table.name());
            columnsByTable.put(key(table.name()), detail.columns());
            primaryKeysByTable.put(key(table.name()), new LinkedHashSet<>(detail.primaryKeys()));
            imported.addAll(metadata.relations(connectionId, table.schemaName(), table.name()).importedKeys());
        }

        Set<String> present = selected.stream().map(table -> key(table.name())).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        // 指向图外的外键要丢掉：画一根连到不存在的方块的线，比不画更让人困惑。
        List<DiagramRelation> relations = imported.stream()
                .filter(relation -> relation.fkTableName() != null && relation.pkTableName() != null)
                .filter(relation -> present.contains(key(relation.fkTableName())) && present.contains(key(relation.pkTableName())))
                .map(relation -> new DiagramRelation(
                        relation.constraintName(), relation.fkTableName(), relation.fkColumnName(),
                        relation.pkTableName(), relation.pkColumnName()))
                .distinct()
                .toList();

        Map<String, Set<String>> foreignKeyColumns = new LinkedHashMap<>();
        for (DiagramRelation relation : relations) {
            foreignKeyColumns.computeIfAbsent(key(relation.fromTable()), ignored -> new LinkedHashSet<>())
                    .add(key(relation.fromColumn()));
        }

        List<DiagramTable> diagramTables = selected.stream().map(table -> {
            String tableKey = key(table.name());
            List<ColumnInfo> columns = columnsByTable.getOrDefault(tableKey, List.of());
            Set<String> primaryKeys = primaryKeysByTable.getOrDefault(tableKey, Set.of()).stream()
                    .map(SchemaDiagramService::key)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> foreignKeys = foreignKeyColumns.getOrDefault(tableKey, Set.of());
            List<DiagramColumn> keyColumns = new ArrayList<>();
            for (ColumnInfo column : columns) {
                boolean isPrimary = primaryKeys.contains(key(column.name()));
                boolean isForeign = foreignKeys.contains(key(column.name()));
                if (!isPrimary && !isForeign) continue;
                keyColumns.add(new DiagramColumn(column.name(), column.type(), column.nullable(), isPrimary, isForeign));
            }
            keyColumns.sort((left, right) -> Boolean.compare(right.primaryKey(), left.primaryKey()));
            return new DiagramTable(table.schemaName(), table.name(), List.copyOf(keyColumns), columns.size());
        }).toList();

        return new SchemaDiagram(resolvedSchema, diagramTables, relations, inspection.totalObjects(), truncated);
    }

    private static boolean isTable(String type) {
        return type != null && ("TABLE".equalsIgnoreCase(type) || "BASE TABLE".equalsIgnoreCase(type));
    }

    /** 表名与列名的大小写在各数据库之间不统一，比较一律折叠后再做。 */
    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
