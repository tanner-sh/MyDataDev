package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoKeyStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndReusesAStrongKeyForAFreshInstallation() throws Exception {
        Path keyFile = temporaryDirectory.resolve("secrets/master.key");
        AppProperties properties = properties(keyFile);
        CryptoKeyStore store = new CryptoKeyStore();

        CryptoKeyStore.LoadedKey created = store.load(properties, new MockEnvironment(), false);
        CryptoKeyStore.LoadedKey reused = store.load(properties, new MockEnvironment(), true);

        assertThat(created.material()).hasSizeGreaterThanOrEqualTo(43).isEqualTo(reused.material());
        assertThat(keyFile).exists();
        if (Files.getFileAttributeView(keyFile, PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(keyFile)).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        }
    }

    @Test
    void neverGeneratesAReplacementWhenHistoricalSecretsExist() {
        Path keyFile = temporaryDirectory.resolve("master.key");

        assertThatThrownBy(() -> new CryptoKeyStore().load(properties(keyFile), new MockEnvironment(), true))
                .hasMessageContaining("历史加密凭据")
                .hasMessageContaining("没有生成新密钥");
        assertThat(keyFile).doesNotExist();
    }

    @Test
    void readsAPlatformMountedKeyWithoutTryingToRewriteIt() throws Exception {
        Path keyFile = temporaryDirectory.resolve("mounted.key");
        Files.writeString(keyFile, "platform-managed-key\n");
        keyFile.toFile().setReadOnly();

        CryptoKeyStore.LoadedKey loaded = new CryptoKeyStore().load(properties(keyFile), new MockEnvironment(), true);

        assertThat(loaded.material()).isEqualTo("platform-managed-key");
        assertThat(Files.readString(keyFile)).isEqualTo("platform-managed-key\n");
    }

    @Test
    void rejectsEmptyOrMultilineKeyFiles() throws Exception {
        Path empty = temporaryDirectory.resolve("empty.key");
        Files.writeString(empty, "\n");
        Path multiline = temporaryDirectory.resolve("multiline.key");
        Files.writeString(multiline, "one\ntwo\n");

        assertThatThrownBy(() -> new CryptoKeyStore().load(properties(empty), new MockEnvironment(), false))
                .hasMessageContaining("为空或包含多行");
        assertThatThrownBy(() -> new CryptoKeyStore().load(properties(multiline), new MockEnvironment(), false))
                .hasMessageContaining("为空或包含多行");
    }

    @Test
    void readsTheDesktopKeyOnceFromStandardInput() {
        AppProperties properties = new AppProperties();
        properties.setCryptoKeySource("STDIN");
        CryptoKeyStore store = new CryptoKeyStore(new ByteArrayInputStream(
                "desktop-safe-storage-key\nignored-second-line\n".getBytes(StandardCharsets.UTF_8)
        ));

        CryptoKeyStore.LoadedKey loaded = store.load(properties, new MockEnvironment(), true);

        assertThat(loaded.material()).isEqualTo("desktop-safe-storage-key");
        assertThat(loaded.finalPath()).isNull();
    }

    private AppProperties properties(Path keyFile) {
        AppProperties properties = new AppProperties();
        properties.setCryptoKeySource("FILE");
        properties.setCryptoKeyFile(keyFile.toString());
        return properties;
    }
}
