package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataCacheServiceTest {
    @Test
    void directoryRefreshPreservesObjectDetails() {
        MetadataCacheService cache = new MetadataCacheService();
        ObjectDetail detail = new ObjectDetail("PUBLIC", "USERS", "TABLE", List.of(), List.of(), List.of(), null);
        cache.putDetail(1L, "PUBLIC", "USERS", detail);
        cache.putMetadataPage(1L, "PUBLIC", null, 0, 200,
                new MetadataCacheService.MetadataObjectPage(
                        List.of(new DbObject("PUBLIC", "USERS", "TABLE", List.of(), List.of())), 1, true, false
                ));

        cache.evictMetadataDirectory(1L);

        assertThat(cache.metadataPage(1L, "PUBLIC", null, 0, 200)).isEmpty();
        assertThat(cache.detail(1L, "PUBLIC", "USERS")).contains(detail);
    }

    @Test
    void fullInvalidationAdvancesAllCacheGenerations() {
        MetadataCacheService cache = new MetadataCacheService();
        cache.putSchemaCatalog(1L, List.of("PUBLIC"), "PUBLIC", false);
        cache.putDetail(1L, "PUBLIC", "USERS",
                new ObjectDetail("PUBLIC", "USERS", "TABLE", List.of(), List.of(), List.of(), null));

        cache.evictConnection(1L);

        assertThat(cache.schemaCatalog(1L)).isEmpty();
        assertThat(cache.detail(1L, "PUBLIC", "USERS")).isEmpty();
    }

    @Test
    void refreshingOneSchemaObjectDetailLeavesOtherCachesIntact() {
        MetadataCacheService cache = new MetadataCacheService();
        SchemaObjectSummary summary = new SchemaObjectSummary("view:PUBLIC.V1", "PUBLIC", "V1", "V1", "VIEW", null, null);
        SchemaObjectDetail detail = new SchemaObjectDetail(
                summary, "SELECT 1", true, null, List.of(), List.of(), true, null, "v1", List.of(), Map.of()
        );
        cache.putSchemaObjectDetail(1L, "view:PUBLIC.V1", detail);
        cache.putDetail(1L, "PUBLIC", "USERS",
                new ObjectDetail("PUBLIC", "USERS", "TABLE", List.of(), List.of(), List.of(), null));
        MetadataCacheService.SchemaObjectPageValue page = new MetadataCacheService.SchemaObjectPageValue(
                List.of(summary), 1, true, false
        );
        cache.putSchemaObjectPage(1L, "PUBLIC", "VIEW", "", 0, 100, page);

        // A plain refresh of one object's detail must not behave like
        // evictConnection: it should drop only that object's cached detail,
        // not every schema-object page listing or classic metadata cache
        // entry for the whole connection.
        cache.evictSchemaObjectDetail(1L, "view:PUBLIC.V1");

        assertThat(cache.schemaObjectDetail(1L, "view:PUBLIC.V1")).isEmpty();
        assertThat(cache.detail(1L, "PUBLIC", "USERS")).isPresent();
        assertThat(cache.schemaObjectPage(1L, "PUBLIC", "VIEW", "", 0, 100)).isPresent();
    }
}
