package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.core.SchemaObjectOperation;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectSearchHit;
import com.example.dbadmin.dto.ApiDtos.ObjectSearchResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectCapability;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectPage;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectSummary;
import com.example.dbadmin.model.DbConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 跨对象类型的统一搜索。
 *
 * <p>资源管理器的搜索必须先在下拉里选定一种对象类型，再在该类型内搜 —— 想找一个记不清
 * 是视图还是函数的对象，得把类型挨个切一遍。MCP 的 db_search_objects 也只覆盖表和视图。
 * 这里把一次关键字同时投到表/视图与该方言支持的全部 schema 对象上，供命令面板式的
 * 全局搜索使用。</p>
 *
 * <p>成本是有界的：每种类型各取 {@link #PER_KIND_LIMIT} 条，总量截到调用方给的上限；
 * 命中的都是已经带缓存的列表接口（MetadataCacheService 的对象目录与 schema 对象分页），
 * 连续输入时基本不会真的打到目标库。</p>
 */
@Service
public class ObjectSearchService {
    private static final Logger log = LoggerFactory.getLogger(ObjectSearchService.class);
    static final int PER_KIND_LIMIT = 10;
    static final int DEFAULT_LIMIT = 40;
    static final int MAX_LIMIT = 100;
    static final int MAX_KEYWORD_LENGTH = 200;

    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final MetadataService metadata;
    private final SchemaObjectService schemaObjects;

    public ObjectSearchService(
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            MetadataService metadata,
            SchemaObjectService schemaObjects
    ) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.metadata = metadata;
        this.schemaObjects = schemaObjects;
    }

    public ObjectSearchResponse search(long connectionId, String schemaName, String keyword, Integer limit) throws Exception {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("搜索关键字最多 " + MAX_KEYWORD_LENGTH + " 个字符。");
        }
        int cap = Math.min(Math.max(limit == null ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);
        DbConnection configured = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(configured);

        List<ObjectSearchHit> hits = new ArrayList<>();
        // 每种类型各自只取一页，任何一页还有下文都意味着这次搜索是不完整的 —— 只看总条数
        // 有没有超过 cap 会把「表匹配了 30 张、这里只拿回 10 张」报告成结果完整。
        // 表和视图走已有的元数据分页，它同时决定了本次搜索落在哪个 schema 上。
        MetadataResponse tables = metadata.inspect(connectionId, schemaName, normalized, 0, PER_KIND_LIMIT, false);
        boolean incomplete = tables.hasMore();
        String resolvedSchema = tables.selectedSchema();
        for (DbObject object : tables.objects()) {
            hits.add(new ObjectSearchHit(
                    object.type() != null && object.type().toUpperCase(Locale.ROOT).contains("VIEW") ? "VIEW" : "TABLE",
                    object.schemaName() == null || object.schemaName().isBlank() ? resolvedSchema : object.schemaName(),
                    object.name(),
                    object.name(),
                    object.type(),
                    null
            ));
        }

        for (SchemaObjectCapability capability : dialect.capabilities().schemaObjects()) {
            if (!capability.operations().contains(SchemaObjectOperation.LIST.name())) continue;
            SchemaObjectKind kind = SchemaObjectKind.parse(capability.kind());
            // 视图已经由上面的表/视图分页覆盖，再查一遍只会出重复项。
            if (kind == SchemaObjectKind.VIEW) continue;
            try {
                SchemaObjectPage page = schemaObjects.list(
                        connectionId, resolvedSchema, capability.kind(), normalized, 0, PER_KIND_LIMIT, false
                );
                incomplete = incomplete || page.hasMore();
                for (SchemaObjectSummary item : page.items()) {
                    hits.add(new ObjectSearchHit(
                            capability.kind(),
                            item.schemaName(),
                            item.name(),
                            item.displayName() == null || item.displayName().isBlank() ? item.name() : item.displayName(),
                            item.subtype(),
                            item.objectKey()
                    ));
                }
            } catch (Exception error) {
                // 单一类型查询失败（权限不足、方言差异）不该让整个搜索变成一个错误弹窗，
                // 但少了一整类结果同样不能宣称结果完整。
                incomplete = true;
                log.debug("搜索 {} 失败，跳过该类型", capability.kind(), error);
            }
        }

        boolean overCap = hits.size() > cap;
        return new ObjectSearchResponse(
                dialect.namespaceKind().name(),
                resolvedSchema,
                overCap ? List.copyOf(hits.subList(0, cap)) : List.copyOf(hits),
                overCap || incomplete
        );
    }
}
