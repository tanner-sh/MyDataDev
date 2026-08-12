package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
