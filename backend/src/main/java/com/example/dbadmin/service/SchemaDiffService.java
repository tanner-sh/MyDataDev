package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.BackupTargetItem;
import com.example.dbadmin.dto.ApiDtos.BackupTargetPage;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffEndpoint;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffItem;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffRequest;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffSummary;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffTable;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 两个 Schema 之间的结构对比，以及把目标端对齐到源端的迁移脚本。
 *
 * <p>只读：这里不执行任何 DDL。生成的脚本交给用户在 SQL 工作台里复核后执行，这样生产确认、
 * 未限定写操作确认、审计这些既有闸门一个都不会被绕过。</p>
 *
 * <p>DDL 生成全部委托给目标端的方言（{@code createTableSql} / {@code alterTableSql}），
 * 复用的是表设计器那条路径；这里只负责决定「要把哪张表改成什么样」。</p>
 */
@Service
public class SchemaDiffService {
    private static final Logger log = LoggerFactory.getLogger(SchemaDiffService.class);
    /** 每张表都要读一次结构，表太多会把对比拖成一次长事务级别的操作。 */
    static final int MAX_TABLES = 300;
    private static final int LIST_PAGE_SIZE = 500;
    private static final int MAX_LIST_PAGES = 20;

    private final ConnectionService connections;
    private final MetadataService metadata;
    private final DialectRegistry dialectRegistry;
    private final AuditRepository audit;

    public SchemaDiffService(ConnectionService connections, MetadataService metadata,
                             DialectRegistry dialectRegistry, AuditRepository audit) {
        this.connections = connections;
        this.metadata = metadata;
        this.dialectRegistry = dialectRegistry;
        this.audit = audit;
    }

    public SchemaDiffResponse compare(SchemaDiffRequest request, String actor) throws Exception {
        long sourceId = request.sourceConnectionId();
        long targetId = request.targetConnectionId();
        DbConnection source = connections.require(sourceId);
        DbConnection target = connections.require(targetId);
        String sourceSchema = resolveSchema(sourceId, request.sourceSchema());
        String targetSchema = resolveSchema(targetId, request.targetSchema());
        if (sourceId == targetId && fold(sourceSchema).equals(fold(targetSchema))) {
            throw new IllegalArgumentException("源和目标指向同一个 Schema，没有可对比的内容。");
        }

        List<String> warnings = new ArrayList<>();
        if (!source.dbType().equals(target.dbType())) {
            warnings.add("两侧数据库类型不同（" + source.dbType() + " / " + target.dbType()
                    + "）：字段类型名称的写法差异属于正常现象，生成的迁移脚本需要人工复核。");
        }

        Set<String> requested = normalizeRequestedTables(request.tables());
        List<String> sourceTables = filter(listTables(sourceId, sourceSchema), requested);
        List<String> targetTables = filter(listTables(targetId, targetSchema), requested);
        Map<String, String> targetByName = index(targetTables);
        Map<String, String> sourceByName = index(sourceTables);
        int total = new LinkedHashSet<>(union(sourceTables, targetTables)).size();
        if (total > MAX_TABLES) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "SCHEMA_DIFF_TOO_MANY_TABLES",
                    "两侧共有 " + total + " 张表，超过单次对比上限 " + MAX_TABLES + " 张。请指定要对比的表后重试。");
        }

        DatabaseDialect targetDialect = dialectRegistry.dialectFor(target);
        // 表设计器在 MetadataService 里也有这道闸门。生成建表/改表语句走的是同一个方言方法，
        // 声明不支持表设计的方言（SQL Server、ClickHouse、SQLite）没有重写它，继承的是
        // DefaultDialect 那套 PostgreSQL 写法 —— 在 T-SQL 里 `ALTER COLUMN x TYPE y` 根本
        // 不是合法语法。与其发一份跑不通的脚本，不如只给差异清单，让人自己写 DDL。
        boolean canGenerateDdl = targetDialect.capabilities().tableDesign();
        if (!canGenerateDdl) {
            warnings.add("目标库（" + target.dbType() + "）不支持自动生成建表/改表语句，本次只列出结构差异。"
                    + "删除语句仍会生成，其余请按差异清单手工编写 DDL。");
        }
        List<SchemaDiffTable> tables = new ArrayList<>();
        List<String> migration = new ArrayList<>();
        int onlyInSource = 0;
        int onlyInTarget = 0;
        int different = 0;
        int identical = 0;

        for (String tableName : union(sourceTables, targetTables)) {
            String folded = fold(tableName);
            String sourceName = sourceByName.get(folded);
            String targetName = targetByName.get(folded);
            if (sourceName != null && targetName == null) {
                ObjectDetail detail = detail(sourceId, sourceSchema, sourceName, warnings);
                if (detail == null) continue;
                onlyInSource++;
                List<String> sql = canGenerateDdl
                        ? safeDdl(warnings, sourceName, () -> targetDialect.createTableSql(
                                targetSchema, detail.name(), SchemaComparison.creationDesign(detail, targetSchema)))
                        : List.<String>of();
                tables.add(new SchemaDiffTable(sourceName, SchemaComparison.STATUS_ONLY_IN_SOURCE, List.of(), sql));
                appendSection(migration, sourceName, "目标端缺失，需要新建", sql);
            } else if (sourceName == null) {
                onlyInTarget++;
                List<String> sql = request.includeDrops()
                        ? List.of(targetDialect.dropTableSql(targetSchema, targetName))
                        : List.of();
                tables.add(new SchemaDiffTable(targetName, SchemaComparison.STATUS_ONLY_IN_TARGET, List.of(), sql));
                appendSection(migration, targetName, request.includeDrops() ? "源端没有，按要求删除" : "源端没有（未生成删除语句）", sql);
            } else {
                ObjectDetail sourceDetail = detail(sourceId, sourceSchema, sourceName, warnings);
                ObjectDetail targetDetail = detail(targetId, targetSchema, targetName, warnings);
                if (sourceDetail == null || targetDetail == null) continue;
                List<SchemaDiffItem> items = SchemaComparison.compare(sourceDetail, targetDetail);
                if (items.isEmpty()) {
                    identical++;
                    tables.add(new SchemaDiffTable(targetName, SchemaComparison.STATUS_IDENTICAL, List.of(), List.of()));
                    continue;
                }
                different++;
                TableDesignRequest design = SchemaComparison.alignmentDesign(sourceDetail, targetDetail, request.includeDrops());
                List<String> sql = canGenerateDdl
                        ? safeDdl(warnings, targetName,
                                () -> targetDialect.alterTableSql(targetSchema, targetName, targetDetail, design))
                        : List.<String>of();
                tables.add(new SchemaDiffTable(targetName, SchemaComparison.STATUS_DIFFERENT, items, sql));
                appendSection(migration, targetName, "结构不一致", sql);
            }
        }

        // 归到目标连接名下：对比是只读的，但它服务的是「把目标端改成源端的样子」。
        audit.onConnection(actor, "SCHEMA_DIFF", targetId, "schema:" + targetSchema,
                "源=" + source.name() + "/" + sourceSchema + "，对比 " + tables.size() + " 张表");
        return new SchemaDiffResponse(
                new SchemaDiffEndpoint(sourceId, source.name(), source.dbType(), sourceSchema),
                new SchemaDiffEndpoint(targetId, target.name(), target.dbType(), targetSchema),
                new SchemaDiffSummary(onlyInSource, onlyInTarget, different, identical),
                List.copyOf(tables),
                List.copyOf(migration),
                List.copyOf(warnings)
        );
    }

    /**
     * 读一张表的结构。
     *
     * <p>单张表读不出来（权限、并发 DDL、驱动脾气）不该让整次对比失败 —— 报成警告，其余的表
     * 照常对比。</p>
     */
    private ObjectDetail detail(long connectionId, String schemaName, String tableName, List<String> warnings) {
        try {
            return metadata.detail(connectionId, schemaName, tableName);
        } catch (Exception error) {
            log.debug("读取表结构失败：连接 {} 的 {}.{}", connectionId, schemaName, tableName, error);
            warnings.add("无法读取表 " + tableName + " 的结构，已跳过：" + rootMessage(error));
            return null;
        }
    }

    /** DDL 生成会因为方言不支持某些改动而抛错，同样降级成警告。 */
    private List<String> safeDdl(List<String> warnings, String tableName, DdlSupplier supplier) {
        try {
            List<String> sql = supplier.get();
            return sql == null ? List.of() : List.copyOf(sql);
        } catch (RuntimeException error) {
            warnings.add("无法为表 " + tableName + " 生成迁移语句：" + rootMessage(error));
            return List.of();
        }
    }

    private void appendSection(List<String> migration, String tableName, String reason, List<String> sql) {
        if (sql.isEmpty()) return;
        migration.add("-- " + tableName + "：" + reason);
        migration.addAll(sql);
    }

    private String resolveSchema(long connectionId, String requested) throws Exception {
        if (requested != null && !requested.isBlank()) return requested.trim();
        String selected = metadata.inspect(connectionId, null, null, 0, 1, false).selectedSchema();
        if (selected == null || selected.isBlank()) {
            throw new IllegalArgumentException("无法确定要对比的 Schema/数据库，请显式指定。");
        }
        return selected;
    }

    private List<String> listTables(long connectionId, String schemaName) throws Exception {
        List<String> names = new ArrayList<>();
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            BackupTargetPage result = metadata.backupTargetTables(connectionId, schemaName, null, page, LIST_PAGE_SIZE, false);
            for (BackupTargetItem item : result.items()) names.add(item.name());
            if (!result.hasMore()) break;
        }
        return names;
    }

    private Set<String> normalizeRequestedTables(List<String> tables) {
        Set<String> requested = new LinkedHashSet<>();
        if (tables == null) return requested;
        for (String table : tables) {
            if (table == null || table.isBlank()) continue;
            requested.add(fold(table));
        }
        return requested;
    }

    private List<String> filter(List<String> tables, Set<String> requested) {
        if (requested.isEmpty()) return tables;
        return tables.stream().filter(name -> requested.contains(fold(name))).toList();
    }

    /** 两侧表名的并集，源端顺序优先，目标端独有的排在后面。 */
    private List<String> union(List<String> sourceTables, List<String> targetTables) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String name : sourceTables) merged.putIfAbsent(fold(name), name);
        for (String name : targetTables) merged.putIfAbsent(fold(name), name);
        return List.copyOf(merged.values());
    }

    private Map<String, String> index(List<String> tables) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : tables) result.putIfAbsent(fold(name), name);
        return result;
    }

    private static String fold(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private interface DdlSupplier {
        List<String> get();
    }
}
