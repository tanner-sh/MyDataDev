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
    void columnRequestsCoalesceAndDirectoryRefreshRejectsOldGeneration() throws Exception {
        var cache = new MetadataCacheService();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        var value = new com.example.dbadmin.dto.ApiDtos.ObjectColumns("APP", "USERS", "TABLE", "用户", List.of());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            MetadataCacheService.ColumnsLoader loader = () -> {
                calls.incrementAndGet();
                ready.countDown();
                if (!finish.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("等待测试释放超时");
                return value;
            };
            var first = executor.submit(() -> cache.columns(1, "APP", "USERS", loader));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> cache.columns(1, "APP", "USERS", loader));
            finish.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)).isSameAs(value);
            assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS)).isSameAs(value);
            assertThat(calls.get()).isEqualTo(1);
            cache.evictMetadataDirectory(1);
            cache.columns(1, "APP", "USERS", loader);
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
        cache.evictMetadataDirectory(1);
        cache.columns(1, "APP", "USERS", () -> { cache.evictMetadataDirectory(1); return value; });
        var fresh = new com.example.dbadmin.dto.ApiDtos.ObjectColumns("APP", "USERS", "TABLE", "新备注", List.of());
        assertThat(cache.columns(1, "APP", "USERS", () -> fresh)).isSameAs(fresh);
    }

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
