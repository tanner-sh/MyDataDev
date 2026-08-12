package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.dto.ApiDtos.ObjectDdlResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectSummary;
import com.example.dbadmin.service.MetadataService.RowIdentity;
import org.springframework.stereotype.Service;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetadataCacheService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final ConcurrentHashMap<Long, AtomicLong> connectionGenerations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> directoryGenerations = new ConcurrentHashMap<>();
    private final Cache<SchemaCatalogKey, SchemaCatalogSnapshot> schemaCatalogs = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterAccess(TTL)
            .build();
    private final Cache<MetadataPageKey, CachedValue<MetadataObjectPage>> metadataPages = Caffeine.newBuilder()
            .maximumWeight(100_000)
            // Empty/search pages still retain keys and timestamps. Charge a
            // small fixed overhead so an attacker cannot fill the cache with
            // 100k distinct empty searches.
            .weigher((MetadataPageKey ignored, CachedValue<MetadataObjectPage> page) -> 10 + page.value().objects().size())
            .expireAfterAccess(TTL)
            .build();
    private final Cache<ObjectCatalogKey, CachedValue<List<DbObject>>> objectCatalogs = Caffeine.newBuilder()
            .maximumWeight(100_000)
            .weigher((ObjectCatalogKey ignored, CachedValue<List<DbObject>> value) -> 10 + value.value().size())
            .expireAfterAccess(TTL)
            .build();
    private final Cache<ObjectKey, CachedValue<ObjectStructure>> structures = detailCache();
    private final Cache<ObjectKey, CachedValue<ObjectDetail>> details = detailCache();
    private final Cache<ObjectKey, CachedValue<ObjectRelations>> relations = detailCache();
    private final Cache<ObjectKey, CachedValue<ObjectDdlResponse>> ddls = detailCache();
    private final Cache<ObjectKey, CachedValue<RowIdentity>> rowIdentities = detailCache();
    private final Cache<SchemaObjectPageKey, CachedValue<SchemaObjectPageValue>> schemaObjectPages = Caffeine.newBuilder()
            .maximumWeight(100_000)
            .weigher((SchemaObjectPageKey ignored, CachedValue<SchemaObjectPageValue> value) -> 10 + value.value().items().size())
            .expireAfterAccess(TTL)
            .build();
    private final Cache<SchemaObjectDetailKey, CachedValue<SchemaObjectDetail>> schemaObjectDetails = Caffeine.newBuilder()
            .maximumWeight(50_000)
            .weigher((SchemaObjectDetailKey ignored, CachedValue<SchemaObjectDetail> value) ->
                    Math.min(2_000, 1 + String.valueOf(value.value()).length() / 1_024))
            .expireAfterAccess(TTL)
            .build();

    public Optional<SchemaCatalogSnapshot> schemaCatalog(long connectionId) {
        return Optional.ofNullable(schemaCatalogs.getIfPresent(schemaCatalogKey(connectionId)));
    }

    public SchemaCatalogSnapshot putSchemaCatalog(long connectionId, List<String> schemas, String currentSchema, boolean currentIsCatalog) {
        SchemaCatalogSnapshot snapshot = new SchemaCatalogSnapshot(List.copyOf(schemas), currentSchema, currentIsCatalog, Instant.now());
        schemaCatalogs.put(schemaCatalogKey(connectionId), snapshot);
        return snapshot;
    }

    public Optional<CachedValue<MetadataObjectPage>> metadataPage(long connectionId, String schemaName, String keyword, int page, int pageSize) {
        return Optional.ofNullable(metadataPages.getIfPresent(metadataPageKey(connectionId, schemaName, keyword, page, pageSize)));
    }

    public CachedValue<MetadataObjectPage> putMetadataPage(long connectionId, String schemaName, String keyword, int page, int pageSize, MetadataObjectPage value) {
        CachedValue<MetadataObjectPage> cached = new CachedValue<>(value, Instant.now());
        metadataPages.put(metadataPageKey(connectionId, schemaName, keyword, page, pageSize), cached);
        return cached;
    }

    public Optional<CachedValue<List<DbObject>>> objectCatalog(long connectionId, String schemaName) {
        return Optional.ofNullable(objectCatalogs.getIfPresent(objectCatalogKey(connectionId, schemaName)));
    }

    public CachedValue<List<DbObject>> putObjectCatalog(long connectionId, String schemaName, List<DbObject> objects) {
        CachedValue<List<DbObject>> cached = new CachedValue<>(List.copyOf(objects), Instant.now());
        objectCatalogs.put(objectCatalogKey(connectionId, schemaName), cached);
        return cached;
    }

    public Optional<ObjectStructure> structure(long connectionId, String schemaName, String objectName) {
        return value(structures.getIfPresent(key(connectionId, schemaName, objectName)));
    }

    public void putStructure(long connectionId, String schemaName, String objectName, ObjectStructure structure) {
        structures.put(key(connectionId, schemaName, objectName), new CachedValue<>(structure, Instant.now()));
    }

    public Optional<ObjectDetail> detail(long connectionId, String schemaName, String objectName) {
        return value(details.getIfPresent(key(connectionId, schemaName, objectName)));
    }

    public void putDetail(long connectionId, String schemaName, String objectName, ObjectDetail detail) {
        details.put(key(connectionId, schemaName, objectName), new CachedValue<>(detail, Instant.now()));
    }

    public Optional<ObjectRelations> relations(long connectionId, String schemaName, String objectName) {
        return value(relations.getIfPresent(key(connectionId, schemaName, objectName)));
    }

    public void putRelations(long connectionId, String schemaName, String objectName, ObjectRelations value) {
        relations.put(key(connectionId, schemaName, objectName), new CachedValue<>(value, Instant.now()));
    }

    public Optional<ObjectDdlResponse> ddl(long connectionId, String schemaName, String objectName) {
        return value(ddls.getIfPresent(key(connectionId, schemaName, objectName)));
    }

    public void putDdl(long connectionId, String schemaName, String objectName, ObjectDdlResponse value) {
        ddls.put(key(connectionId, schemaName, objectName), new CachedValue<>(value, Instant.now()));
    }

    public Optional<RowIdentity> rowIdentity(long connectionId, String schemaName, String objectName) {
        return value(rowIdentities.getIfPresent(key(connectionId, schemaName, objectName)));
    }

    public void putRowIdentity(long connectionId, String schemaName, String objectName, RowIdentity value) {
        rowIdentities.put(key(connectionId, schemaName, objectName), new CachedValue<>(value, Instant.now()));
    }

    public Optional<CachedValue<SchemaObjectPageValue>> schemaObjectPage(
            long connectionId, String schemaName, String kind, String keyword, int page, int pageSize
    ) {
        return Optional.ofNullable(schemaObjectPages.getIfPresent(
                schemaObjectPageKey(connectionId, schemaName, kind, keyword, page, pageSize)
        ));
    }

    public CachedValue<SchemaObjectPageValue> putSchemaObjectPage(
            long connectionId, String schemaName, String kind, String keyword, int page, int pageSize,
            SchemaObjectPageValue pageValue
    ) {
        CachedValue<SchemaObjectPageValue> value = new CachedValue<>(pageValue, Instant.now());
        schemaObjectPages.put(schemaObjectPageKey(connectionId, schemaName, kind, keyword, page, pageSize), value);
        return value;
    }

    public void evictSchemaObjectPages(long connectionId, String schemaName, String kind) {
        String normalizedSchema = exact(schemaName);
        long generation = directoryGeneration(connectionId);
        schemaObjectPages.asMap().keySet().removeIf(key -> key.connectionId() == connectionId
                && key.generation() == generation
                && key.schemaName().equals(normalizedSchema)
                && key.kind().equals(kind));
    }

    public Optional<CachedValue<SchemaObjectDetail>> schemaObjectDetail(long connectionId, String objectKey) {
        return Optional.ofNullable(schemaObjectDetails.getIfPresent(schemaObjectDetailKey(connectionId, objectKey)));
    }

    public CachedValue<SchemaObjectDetail> putSchemaObjectDetail(long connectionId, String objectKey, SchemaObjectDetail detail) {
        CachedValue<SchemaObjectDetail> value = new CachedValue<>(detail, Instant.now());
        schemaObjectDetails.put(schemaObjectDetailKey(connectionId, objectKey), value);
        return value;
    }

    public void evictConnection(long connectionId) {
        generation(connectionGenerations, connectionId).incrementAndGet();
        generation(directoryGenerations, connectionId).incrementAndGet();
    }

    /**
     * Invalidates only namespace/object listings. Object details, DDL and row
     * identities remain valid after a user merely refreshes the explorer.
     */
    public void evictMetadataDirectory(long connectionId) {
        generation(directoryGenerations, connectionId).incrementAndGet();
    }

    public void evictObject(long connectionId, String schemaName, String objectName) {
        String normalizedSchema = exact(schemaName);
        String normalizedObject = exact(objectName);
        evictMetadataDirectory(connectionId);
        long generation = connectionGeneration(connectionId);
        structures.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, generation, normalizedSchema, normalizedObject));
        details.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, generation, normalizedSchema, normalizedObject));
        relations.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, generation, normalizedSchema, normalizedObject));
        ddls.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, generation, normalizedSchema, normalizedObject));
        rowIdentities.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, generation, normalizedSchema, normalizedObject));
    }

    private boolean matchesObject(ObjectKey key, long connectionId, long generation, String schemaName, String objectName) {
        return key.connectionId() == connectionId
                && key.generation() == generation
                && (schemaName.isBlank() || key.schemaName().equalsIgnoreCase(schemaName))
                && key.objectName().equalsIgnoreCase(objectName);
    }

    private <T> Optional<T> value(CachedValue<T> cached) {
        return cached == null ? Optional.empty() : Optional.of(cached.value());
    }

    private ObjectKey key(long connectionId, String schemaName, String objectName) {
        // Quoted identifiers can differ only by case (for example "Foo" and
        // "foo" in PostgreSQL). Never fold object cache keys.
        return new ObjectKey(connectionId, connectionGeneration(connectionId), exact(schemaName), exact(objectName));
    }

    private MetadataPageKey metadataPageKey(long connectionId, String schemaName, String keyword, int page, int pageSize) {
        return new MetadataPageKey(connectionId, directoryGeneration(connectionId), exact(schemaName), folded(keyword), page, pageSize);
    }

    private ObjectCatalogKey objectCatalogKey(long connectionId, String schemaName) {
        return new ObjectCatalogKey(connectionId, directoryGeneration(connectionId), exact(schemaName));
    }

    private SchemaCatalogKey schemaCatalogKey(long connectionId) {
        return new SchemaCatalogKey(connectionId, directoryGeneration(connectionId));
    }

    private SchemaObjectPageKey schemaObjectPageKey(
            long connectionId, String schemaName, String kind, String keyword, int page, int pageSize
    ) {
        return new SchemaObjectPageKey(
                connectionId, directoryGeneration(connectionId), exact(schemaName), kind, folded(keyword), page, pageSize
        );
    }

    private SchemaObjectDetailKey schemaObjectDetailKey(long connectionId, String objectKey) {
        return new SchemaObjectDetailKey(connectionId, connectionGeneration(connectionId), objectKey);
    }

    private long connectionGeneration(long connectionId) {
        return generation(connectionGenerations, connectionId).get();
    }

    private long directoryGeneration(long connectionId) {
        return generation(directoryGenerations, connectionId).get();
    }

    private AtomicLong generation(ConcurrentHashMap<Long, AtomicLong> generations, long connectionId) {
        return generations.computeIfAbsent(connectionId, ignored -> new AtomicLong());
    }

    private String exact(String value) {
        return value == null ? "" : value;
    }

    private String folded(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static <T> Cache<ObjectKey, CachedValue<T>> detailCache() {
        return Caffeine.newBuilder()
                .maximumWeight(50_000)
                .weigher((ObjectKey ignored, CachedValue<T> value) ->
                        Math.min(1_000, 1 + String.valueOf(value.value()).length() / 1_024))
                .expireAfterAccess(TTL)
                .build();
    }

    public record SchemaCatalogSnapshot(List<String> schemas, String currentSchema, boolean currentIsCatalog, Instant cachedAt) {
    }

    public record CachedValue<T>(T value, Instant cachedAt) {
    }

    public record MetadataObjectPage(List<DbObject> objects, int total, boolean totalExact, boolean hasMore) {
        public MetadataObjectPage {
            objects = List.copyOf(objects);
        }
    }

    public record SchemaObjectPageValue(
            List<SchemaObjectSummary> items,
            int total,
            boolean totalExact,
            boolean hasMore
    ) {
        public SchemaObjectPageValue {
            items = List.copyOf(items);
        }
    }

    private record ObjectKey(long connectionId, long generation, String schemaName, String objectName) {
    }

    private record MetadataPageKey(long connectionId, long generation, String schemaName, String keyword, int page, int pageSize) {
    }

    private record ObjectCatalogKey(long connectionId, long generation, String schemaName) {
    }

    private record SchemaCatalogKey(long connectionId, long generation) {
    }

    private record SchemaObjectPageKey(
            long connectionId,
            long generation,
            String schemaName,
            String kind,
            String keyword,
            int page,
            int pageSize
    ) {
    }

    private record SchemaObjectDetailKey(long connectionId, long generation, String objectKey) {
    }
}
