package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.dto.ApiDtos.ObjectDdlResponse;
import com.example.dbadmin.service.MetadataService.RowIdentity;
import org.springframework.stereotype.Service;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.time.Duration;

@Service
public class MetadataCacheService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final Cache<Long, SchemaCatalogSnapshot> schemaCatalogs = Caffeine.newBuilder()
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
    private final Cache<ObjectKey, CachedValue<ObjectStructure>> structures = detailCache();
    private final Cache<ObjectKey, CachedValue<ObjectDetail>> details = detailCache();
    private final Cache<ObjectKey, CachedValue<ObjectRelations>> relations = detailCache();
    private final Cache<ObjectKey, CachedValue<ObjectDdlResponse>> ddls = detailCache();
    private final Cache<ObjectKey, CachedValue<RowIdentity>> rowIdentities = detailCache();

    public Optional<SchemaCatalogSnapshot> schemaCatalog(long connectionId) {
        return Optional.ofNullable(schemaCatalogs.getIfPresent(connectionId));
    }

    public SchemaCatalogSnapshot putSchemaCatalog(long connectionId, List<String> schemas, String currentSchema, boolean currentIsCatalog) {
        SchemaCatalogSnapshot snapshot = new SchemaCatalogSnapshot(List.copyOf(schemas), currentSchema, currentIsCatalog, Instant.now());
        schemaCatalogs.put(connectionId, snapshot);
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

    public void evictConnection(long connectionId) {
        schemaCatalogs.invalidate(connectionId);
        metadataPages.asMap().keySet().removeIf(key -> key.connectionId() == connectionId);
        structures.asMap().keySet().removeIf(key -> key.connectionId() == connectionId);
        details.asMap().keySet().removeIf(key -> key.connectionId() == connectionId);
        relations.asMap().keySet().removeIf(key -> key.connectionId() == connectionId);
        ddls.asMap().keySet().removeIf(key -> key.connectionId() == connectionId);
        rowIdentities.asMap().keySet().removeIf(key -> key.connectionId() == connectionId);
    }

    public void evictObject(long connectionId, String schemaName, String objectName) {
        String normalizedSchema = exact(schemaName);
        String normalizedObject = exact(objectName);
        metadataPages.asMap().keySet().removeIf(pageKey -> pageKey.connectionId() == connectionId
                && (normalizedSchema.isBlank() || pageKey.schemaName().equalsIgnoreCase(normalizedSchema)));
        structures.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, normalizedSchema, normalizedObject));
        details.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, normalizedSchema, normalizedObject));
        relations.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, normalizedSchema, normalizedObject));
        ddls.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, normalizedSchema, normalizedObject));
        rowIdentities.asMap().keySet().removeIf(key -> matchesObject(key, connectionId, normalizedSchema, normalizedObject));
    }

    private boolean matchesObject(ObjectKey key, long connectionId, String schemaName, String objectName) {
        return key.connectionId() == connectionId
                && (schemaName.isBlank() || key.schemaName().equalsIgnoreCase(schemaName))
                && key.objectName().equalsIgnoreCase(objectName);
    }

    private <T> Optional<T> value(CachedValue<T> cached) {
        return cached == null ? Optional.empty() : Optional.of(cached.value());
    }

    private ObjectKey key(long connectionId, String schemaName, String objectName) {
        // Quoted identifiers can differ only by case (for example "Foo" and
        // "foo" in PostgreSQL). Never fold object cache keys.
        return new ObjectKey(connectionId, exact(schemaName), exact(objectName));
    }

    private MetadataPageKey metadataPageKey(long connectionId, String schemaName, String keyword, int page, int pageSize) {
        return new MetadataPageKey(connectionId, exact(schemaName), folded(keyword), page, pageSize);
    }

    private String exact(String value) {
        return value == null ? "" : value;
    }

    private String folded(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static <T> Cache<ObjectKey, CachedValue<T>> detailCache() {
        return Caffeine.newBuilder()
                .maximumSize(5_000)
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

    private record ObjectKey(long connectionId, String schemaName, String objectName) {
    }

    private record MetadataPageKey(long connectionId, String schemaName, String keyword, int page, int pageSize) {
    }
}
