package com.example.dbadmin.repo;

import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.SshTunnelSettings;
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
public class ConnectionRepository {
    private final JdbcTemplate jdbc;

    private final RowMapper<DbConnection> mapper = (rs, rowNum) -> new DbConnection(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("db_type"),
            rs.getString("jdbc_url"),
            rs.getString("username"),
            rs.getString("encrypted_password"),
            rs.getString("environment"),
            rs.getBoolean("readonly"),
            rs.getString("group_name"),
            rs.getString("tags"),
            rs.getString("default_schema"),
            rs.getString("init_sql"),
            rs.getString("description"),
            toSshTunnel(rs),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DbConnection> findAll() {
        return jdbc.query("SELECT * FROM db_connection ORDER BY id DESC", mapper);
    }

    public Optional<DbConnection> findById(long id) {
        List<DbConnection> rows = jdbc.query("SELECT * FROM db_connection WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    public long insert(DbConnection c) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO db_connection(name, db_type, jdbc_url, username, encrypted_password, environment, readonly,
                                              group_name, tags, default_schema, init_sql, description,
                                              ssh_enabled, ssh_host, ssh_port, ssh_username, ssh_auth_mode,
                                              ssh_encrypted_password, ssh_encrypted_private_key, ssh_encrypted_passphrase,
                                              ssh_server_fingerprint, ssh_skip_host_key_check)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.name());
            ps.setString(2, c.dbType());
            ps.setString(3, c.jdbcUrl());
            ps.setString(4, c.username());
            ps.setString(5, c.encryptedPassword());
            ps.setString(6, c.environment());
            ps.setBoolean(7, c.readonly());
            ps.setString(8, c.groupName());
            ps.setString(9, c.tags());
            ps.setString(10, c.defaultSchema());
            ps.setString(11, c.initSql());
            ps.setString(12, c.description());
            SshTunnelSettings ssh = tunnelOf(c);
            ps.setBoolean(13, ssh.enabled());
            ps.setString(14, ssh.host());
            ps.setInt(15, ssh.port());
            ps.setString(16, ssh.username());
            ps.setString(17, ssh.authMode());
            ps.setString(18, ssh.encryptedPassword());
            ps.setString(19, ssh.encryptedPrivateKey());
            ps.setString(20, ssh.encryptedPassphrase());
            ps.setString(21, ssh.serverFingerprint());
            ps.setBoolean(22, ssh.skipHostKeyCheck());
            return ps;
        }, keys);
        if (keys.getKeys() != null && keys.getKeys().get("id") instanceof Number id) {
            return id.longValue();
        }
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void update(long id, DbConnection c) {
        SshTunnelSettings ssh = tunnelOf(c);
        jdbc.update("""
                UPDATE db_connection
                SET name = ?, db_type = ?, jdbc_url = ?, username = ?, encrypted_password = ?, environment = ?, readonly = ?,
                    group_name = ?, tags = ?, default_schema = ?, init_sql = ?, description = ?,
                    ssh_enabled = ?, ssh_host = ?, ssh_port = ?, ssh_username = ?, ssh_auth_mode = ?,
                    ssh_encrypted_password = ?, ssh_encrypted_private_key = ?, ssh_encrypted_passphrase = ?,
                    ssh_server_fingerprint = ?, ssh_skip_host_key_check = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, c.name(), c.dbType(), c.jdbcUrl(), c.username(), c.encryptedPassword(), c.environment(), c.readonly(),
                c.groupName(), c.tags(), c.defaultSchema(), c.initSql(), c.description(),
                ssh.enabled(), ssh.host(), ssh.port(), ssh.username(), ssh.authMode(),
                ssh.encryptedPassword(), ssh.encryptedPrivateKey(), ssh.encryptedPassphrase(),
                ssh.serverFingerprint(), ssh.skipHostKeyCheck(), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM db_connection WHERE id = ?", id);
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** 未配置隧道的行落成关闭状态，调用方不用到处判空。 */
    private static SshTunnelSettings tunnelOf(DbConnection c) {
        return c.sshTunnel() == null ? SshTunnelSettings.disabled() : c.sshTunnel();
    }

    private static SshTunnelSettings toSshTunnel(java.sql.ResultSet rs) throws java.sql.SQLException {
        // wasNull() 说的是「上一次读取」，所以端口要读完立刻判空，不能挪到后面。
        int rawPort = rs.getInt("ssh_port");
        int port = rs.wasNull() || rawPort <= 0 ? SshTunnelSettings.DEFAULT_PORT : rawPort;
        return new SshTunnelSettings(
                rs.getBoolean("ssh_enabled"),
                rs.getString("ssh_host"),
                port,
                rs.getString("ssh_username"),
                rs.getString("ssh_auth_mode"),
                rs.getString("ssh_encrypted_password"),
                rs.getString("ssh_encrypted_private_key"),
                rs.getString("ssh_encrypted_passphrase"),
                rs.getString("ssh_server_fingerprint"),
                rs.getBoolean("ssh_skip_host_key_check")
        );
    }
}
