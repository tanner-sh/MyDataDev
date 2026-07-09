package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class MetadataCacheService {
    private final ConcurrentMap<Long, MetadataSnapshot> metadata = new ConcurrentHashMap<>();
    private final ConcurrentMap<ObjectKey, CachedValue<ObjectStructure>> structures = new ConcurrentHashMap<>();
    private final ConcurrentMap<ObjectKey, CachedValue<ObjectDetail>> details = new ConcurrentHashMap<>();
    private final ConcurrentMap<ObjectKey, CachedValue<ObjectRelations>> relations = new ConcurrentHashMap<>();

    public Optional<MetadataSnapshot> metadata(long connectionId) {
        return Optional.ofNullable(metadata.get(connectionId));
    }

    public MetadataSnapshot putMetadata(long connectionId, List<String> schemas, List<DbObject> objects) {
        MetadataSnapshot snapshot = new MetadataSnapshot(List.copyOf(schemas), List.copyOf(objects), Instant.now());
        metadata.put(connectionId, snapshot);
        return snapshot;
    }

    public Optional<ObjectStructure> structure(long connectionId, String schemaName, String objectName) {
        return value(structures.get(key(connectionId, schemaName, objectName)));
    }

    public void putStructure(long connectionId, String schemaName, String objectName, ObjectStructure structure) {
        structures.put(key(connectionId, schemaName, objectName), new CachedValue<>(structure, Instant.now()));
    }

    public Optional<ObjectDetail> detail(long connectionId, String schemaName, String objectName) {
        return value(details.get(key(connectionId, schemaName, objectName)));
    }

    public void putDetail(long connectionId, String schemaName, String objectName, ObjectDetail detail) {
        details.put(key(connectionId, schemaName, objectName), new CachedValue<>(detail, Instant.now()));
    }

    public Optional<ObjectRelations> relations(long connectionId, String schemaName, String objectName) {
        return value(relations.get(key(connectionId, schemaName, objectName)));
    }

    public void putRelations(long connectionId, String schemaName, String objectName, ObjectRelations value) {
        relations.put(key(connectionId, schemaName, objectName), new CachedValue<>(value, Instant.now()));
    }

    public void evictConnection(long connectionId) {
        metadata.remove(connectionId);
        structures.keySet().removeIf(key -> key.connectionId() == connectionId);
        details.keySet().removeIf(key -> key.connectionId() == connectionId);
        relations.keySet().removeIf(key -> key.connectionId() == connectionId);
    }

    public void evictObject(long connectionId, String schemaName, String objectName) {
        ObjectKey key = key(connectionId, schemaName, objectName);
        metadata.remove(connectionId);
        structures.remove(key);
        details.remove(key);
        relations.remove(key);
    }

    private <T> Optional<T> value(CachedValue<T> cached) {
        return cached == null ? Optional.empty() : Optional.of(cached.value());
    }

    private ObjectKey key(long connectionId, String schemaName, String objectName) {
        return new ObjectKey(connectionId, normalize(schemaName), normalize(objectName));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record MetadataSnapshot(List<String> schemas, List<DbObject> objects, Instant cachedAt) {
    }

    private record CachedValue<T>(T value, Instant cachedAt) {
    }

    private record ObjectKey(long connectionId, String schemaName, String objectName) {
    }
}
