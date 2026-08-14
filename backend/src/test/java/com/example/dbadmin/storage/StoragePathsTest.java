package com.example.dbadmin.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoragePathsTest {
    @Test
    void buildsProtocolSpecificPathsInsideConfiguredBase() {
        StorageConnection connection = connection("team/backups");

        assertThat(StoragePaths.remotePath(connection, "connection-1/task-2/file.sql", "/"))
                .isEqualTo("/team/backups/connection-1/task-2/file.sql");
        assertThat(StoragePaths.remotePath(connection, "connection-1/task-2/file.sql", "\\"))
                .isEqualTo("team\\backups\\connection-1\\task-2\\file.sql");
        assertThat(StoragePaths.parentDirectories("/team/backups/file.sql"))
                .containsExactly("team", "team/backups");
    }

    @Test
    void rejectsParentTraversalInBaseOrObjectKey() {
        assertThatThrownBy(() -> StoragePaths.remotePath(connection("../outside"), "file.sql", "/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoragePaths.remotePath(connection("backups"), "../file.sql", "/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private StorageConnection connection(String basePath) {
        return new StorageConnection(1, "SFTP", "files.internal", 22, basePath, "backup", "secret",
                null, null, null, null, null, List.of(), null, "PASSWORD", null, null,
                "SHA256:host-key", false);
    }
}
