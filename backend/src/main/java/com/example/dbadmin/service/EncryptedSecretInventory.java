package com.example.dbadmin.service;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 盘点元数据库里所有由 {@link CryptoService} 加密的值。
 *
 * <p>主密钥文件不存在时，只有确认这里为空才能为新安装生成密钥；已有密文时误生成一把新钥匙
 * 会让历史凭据看起来像是全部损坏，因此必须先停下来要求管理员接管旧密钥。</p>
 */
@Component
@DependsOnDatabaseInitialization
public class EncryptedSecretInventory {
    private final JdbcTemplate jdbc;

    public EncryptedSecretInventory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EncryptedSecret> all() {
        List<EncryptedSecret> values = new ArrayList<>();
        jdbc.query("""
                SELECT id, encrypted_password, ssh_encrypted_password,
                       ssh_encrypted_private_key, ssh_encrypted_passphrase
                FROM db_connection
                """, rs -> {
            long id = rs.getLong("id");
            add(values, "db_connection[" + id + "].encrypted_password", rs.getString("encrypted_password"));
            add(values, "db_connection[" + id + "].ssh_encrypted_password", rs.getString("ssh_encrypted_password"));
            add(values, "db_connection[" + id + "].ssh_encrypted_private_key", rs.getString("ssh_encrypted_private_key"));
            add(values, "db_connection[" + id + "].ssh_encrypted_passphrase", rs.getString("ssh_encrypted_passphrase"));
        });
        jdbc.query("""
                SELECT id, encrypted_password, encrypted_private_key,
                       encrypted_private_key_passphrase
                FROM storage_profile
                """, rs -> {
            long id = rs.getLong("id");
            add(values, "storage_profile[" + id + "].encrypted_password", rs.getString("encrypted_password"));
            add(values, "storage_profile[" + id + "].encrypted_private_key", rs.getString("encrypted_private_key"));
            add(values, "storage_profile[" + id + "].encrypted_private_key_passphrase", rs.getString("encrypted_private_key_passphrase"));
        });
        jdbc.query("SELECT id, api_key_cipher FROM ai_settings", rs -> {
            long id = rs.getLong("id");
            add(values, "ai_settings[" + id + "].api_key_cipher", rs.getString("api_key_cipher"));
        });
        return values;
    }

    private static void add(List<EncryptedSecret> values, String location, String ciphertext) {
        if (ciphertext != null && !ciphertext.isBlank()) {
            values.add(new EncryptedSecret(location, ciphertext));
        }
    }

    public record EncryptedSecret(String location, String ciphertext) {
    }
}
