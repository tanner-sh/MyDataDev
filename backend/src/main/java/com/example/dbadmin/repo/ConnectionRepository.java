package com.example.dbadmin.repo;

import com.example.dbadmin.model.DbConnection;
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
                    INSERT INTO db_connection(name, db_type, jdbc_url, username, encrypted_password, environment, readonly)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.name());
            ps.setString(2, c.dbType());
            ps.setString(3, c.jdbcUrl());
            ps.setString(4, c.username());
            ps.setString(5, c.encryptedPassword());
            ps.setString(6, c.environment());
            ps.setBoolean(7, c.readonly());
            return ps;
        }, keys);
        if (keys.getKeys() != null && keys.getKeys().get("id") instanceof Number id) {
            return id.longValue();
        }
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void update(long id, DbConnection c) {
        jdbc.update("""
                UPDATE db_connection
                SET name = ?, db_type = ?, jdbc_url = ?, username = ?, encrypted_password = ?, environment = ?, readonly = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, c.name(), c.dbType(), c.jdbcUrl(), c.username(), c.encryptedPassword(), c.environment(), c.readonly(), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM db_connection WHERE id = ?", id);
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
