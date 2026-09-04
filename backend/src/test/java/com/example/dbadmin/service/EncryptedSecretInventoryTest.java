package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedSecretInventoryTest {
    @Test
    void inventoriesEveryPersistedCiphertextFieldThatDependsOnTheMasterKey() {
        String url = "jdbc:h2:mem:key-inventory-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("schema.sql"),
                new ClassPathResource("connection-ssh-schema.sql"),
                new ClassPathResource("ai-schema.sql")
        ).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO db_connection(name, db_type, jdbc_url, encrypted_password,
                  ssh_encrypted_password, ssh_encrypted_private_key, ssh_encrypted_passphrase)
                VALUES ('db', 'H2', 'jdbc:h2:mem:target', 'db-password', 'ssh-password', 'ssh-key', 'ssh-passphrase')
                """);
        jdbc.update("""
                INSERT INTO storage_profile(name, type, host, port, encrypted_password,
                  encrypted_private_key, encrypted_private_key_passphrase)
                VALUES ('storage', 'SFTP', 'localhost', 22, 'storage-password', 'storage-key', 'storage-passphrase')
                """);
        jdbc.update("""
                INSERT INTO ai_settings(id, enabled, provider, model, api_key_cipher, effort)
                VALUES (1, TRUE, 'ANTHROPIC', 'claude-opus-5', 'ai-api-key', 'HIGH')
                """);

        assertThat(new EncryptedSecretInventory(jdbc).all())
                .extracting(EncryptedSecretInventory.EncryptedSecret::ciphertext)
                .containsExactlyInAnyOrder(
                        "db-password", "ssh-password", "ssh-key", "ssh-passphrase",
                        "storage-password", "storage-key", "storage-passphrase",
                        "ai-api-key"
                );
    }
}
