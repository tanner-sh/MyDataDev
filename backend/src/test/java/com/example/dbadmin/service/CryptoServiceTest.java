package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CryptoServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesTheExistingCipherFormatForAnAdoptedKey() throws Exception {
        CryptoService legacy = new CryptoService("the-existing-master-key");
        String ciphertext = legacy.encrypt("database-password");
        Path keyFile = temporaryDirectory.resolve("master.key");
        CryptoKeyStore store = new CryptoKeyStore();
        store.stageAdoption(keyFile, "the-existing-master-key");

        EncryptedSecretInventory inventory = mock(EncryptedSecretInventory.class);
        when(inventory.all()).thenReturn(List.of(
                new EncryptedSecretInventory.EncryptedSecret("db_connection[1].encrypted_password", ciphertext)
        ));

        CryptoService migrated = new CryptoService(properties(keyFile), new MockEnvironment(), store, inventory);

        assertThat(migrated.decrypt(ciphertext)).isEqualTo("database-password");
        assertThat(keyFile).exists();
        assertThat(CryptoKeyStore.pendingPath(keyFile)).doesNotExist();
    }

    @Test
    void refusesAnIncorrectPendingKeyWithoutPromotingIt() throws Exception {
        CryptoService legacy = new CryptoService("correct-key");
        String ciphertext = legacy.encrypt("database-password");
        Path keyFile = temporaryDirectory.resolve("master.key");
        CryptoKeyStore store = new CryptoKeyStore();
        store.stageAdoption(keyFile, "incorrect-key");
        EncryptedSecretInventory inventory = mock(EncryptedSecretInventory.class);
        when(inventory.all()).thenReturn(List.of(
                new EncryptedSecretInventory.EncryptedSecret("db_connection[1].encrypted_password", ciphertext)
        ));

        assertThatThrownBy(() -> new CryptoService(properties(keyFile), new MockEnvironment(), store, inventory))
                .hasMessageContaining("无法解密已有凭据")
                .hasMessageContaining("数据库未被修改");
        assertThat(keyFile).doesNotExist();
        assertThat(CryptoKeyStore.pendingPath(keyFile)).exists();
    }

    @Test
    void rejectsLegacyRuntimeConfigurationInsteadOfSilentlyUsingIt() {
        EncryptedSecretInventory inventory = mock(EncryptedSecretInventory.class);
        when(inventory.all()).thenReturn(List.of());

        assertThatThrownBy(() -> new CryptoService(
                properties(temporaryDirectory.resolve("master.key")),
                new MockEnvironment().withProperty("DB_ADMIN_CRYPTO_KEY", "legacy-key"),
                new CryptoKeyStore(),
                inventory
        )).hasMessageContaining("已停止支持").hasMessageContaining("crypto-key adopt");
    }

    @Test
    void encryptionUsesRandomIvsAndTheSameKeyCanDecryptBothValues() throws Exception {
        CryptoService crypto = new CryptoService("stable-test-key");
        String first = crypto.encrypt("same-value");
        String second = crypto.encrypt("same-value");

        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo("same-value");
        assertThat(crypto.decrypt(second)).isEqualTo("same-value");
        assertThat(new CryptoService("another-key").sign("cursor"))
                .isNotEqualTo(crypto.sign("cursor"));
    }

    private AppProperties properties(Path keyFile) {
        AppProperties properties = new AppProperties();
        properties.setCryptoKeySource("FILE");
        properties.setCryptoKeyFile(keyFile.toString());
        return properties;
    }
}
