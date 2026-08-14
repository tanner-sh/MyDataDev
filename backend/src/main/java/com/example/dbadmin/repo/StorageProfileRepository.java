package com.example.dbadmin.repo;

import com.example.dbadmin.model.StorageProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class StorageProfileRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<StorageProfile> mapper = (rs, ignored) -> new StorageProfile(
            rs.getLong("id"), rs.getString("name"), rs.getString("type"), rs.getString("host"), rs.getInt("port"),
            rs.getString("base_path"), rs.getString("username"), rs.getString("encrypted_password"),
            rs.getString("smb_share"), rs.getString("smb_domain"), rs.getString("nfs_export_path"),
            rs.getObject("nfs_uid", Integer.class), rs.getObject("nfs_gid", Integer.class), rs.getString("nfs_groups"),
            rs.getString("ftp_tls_mode"), rs.getString("sftp_auth_mode"), rs.getString("encrypted_private_key"),
            rs.getString("encrypted_private_key_passphrase"), rs.getString("server_fingerprint"),
            rs.getBoolean("skip_server_verification"), rs.getBoolean("enabled"), rs.getString("last_test_status"),
            rs.getString("last_test_message"), instant(rs.getTimestamp("last_tested_at")),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))
    );

    public StorageProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StorageProfile> findAll() {
        return jdbc.query("SELECT * FROM storage_profile ORDER BY name, id", mapper);
    }

    public Optional<StorageProfile> findById(long id) {
        return jdbc.query("SELECT * FROM storage_profile WHERE id = ?", mapper, id).stream().findFirst();
    }

    public long insert(StorageProfile profile) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO storage_profile(name, type, host, port, base_path, username, encrypted_password,
                      smb_share, smb_domain, nfs_export_path, nfs_uid, nfs_gid, nfs_groups, ftp_tls_mode,
                      sftp_auth_mode, encrypted_private_key, encrypted_private_key_passphrase, server_fingerprint,
                      skip_server_verification, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bind(statement, profile);
            return statement;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void update(long id, StorageProfile profile) {
        jdbc.update("""
                UPDATE storage_profile SET name = ?, type = ?, host = ?, port = ?, base_path = ?, username = ?,
                  encrypted_password = ?, smb_share = ?, smb_domain = ?, nfs_export_path = ?, nfs_uid = ?,
                  nfs_gid = ?, nfs_groups = ?, ftp_tls_mode = ?, sftp_auth_mode = ?, encrypted_private_key = ?,
                  encrypted_private_key_passphrase = ?, server_fingerprint = ?, skip_server_verification = ?,
                  enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, profile.name(), profile.type(), profile.host(), profile.port(), profile.basePath(), profile.username(),
                profile.encryptedPassword(), profile.smbShare(), profile.smbDomain(), profile.nfsExportPath(),
                profile.nfsUid(), profile.nfsGid(), profile.nfsGroups(), profile.ftpTlsMode(), profile.sftpAuthMode(),
                profile.encryptedPrivateKey(), profile.encryptedPrivateKeyPassphrase(), profile.serverFingerprint(),
                profile.skipServerVerification(), profile.enabled(), id);
    }

    public void updateTest(long id, boolean success, String message) {
        jdbc.update("UPDATE storage_profile SET last_test_status = ?, last_test_message = ?, last_tested_at = CURRENT_TIMESTAMP WHERE id = ?",
                success ? "SUCCESS" : "FAILED", message, id);
    }

    public int taskReferences(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM backup_task WHERE storage_profile_id = ? OR last_storage_profile_id = ?", Integer.class, id, id);
        return count == null ? 0 : count;
    }

    public int enabledScheduledTaskReferences(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM backup_task WHERE storage_profile_id = ? AND enabled = TRUE AND cron IS NOT NULL", Integer.class, id);
        return count == null ? 0 : count;
    }

    public int historyReferences(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM backup_history WHERE storage_profile_id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM storage_profile WHERE id = ?", id);
    }

    private void bind(PreparedStatement statement, StorageProfile profile) throws java.sql.SQLException {
        statement.setString(1, profile.name());
        statement.setString(2, profile.type());
        statement.setString(3, profile.host());
        statement.setInt(4, profile.port());
        statement.setString(5, profile.basePath());
        statement.setString(6, profile.username());
        statement.setString(7, profile.encryptedPassword());
        statement.setString(8, profile.smbShare());
        statement.setString(9, profile.smbDomain());
        statement.setString(10, profile.nfsExportPath());
        statement.setObject(11, profile.nfsUid());
        statement.setObject(12, profile.nfsGid());
        statement.setString(13, profile.nfsGroups());
        statement.setString(14, profile.ftpTlsMode());
        statement.setString(15, profile.sftpAuthMode());
        statement.setString(16, profile.encryptedPrivateKey());
        statement.setString(17, profile.encryptedPrivateKeyPassphrase());
        statement.setString(18, profile.serverFingerprint());
        statement.setBoolean(19, profile.skipServerVerification());
        statement.setBoolean(20, profile.enabled());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
