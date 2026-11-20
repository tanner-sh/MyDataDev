package com.example.dbadmin.repo;

import com.example.dbadmin.model.MigrationArtifact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MigrationArtifactRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<MigrationArtifact> mapper = (rs, ignored) -> new MigrationArtifact(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("file_path"),
            rs.getObject("storage_profile_id", Long.class),
            rs.getString("remote_path"),
            rs.getLong("file_size"),
            rs.getString("checksum"),
            rs.getString("source_db_type"),
            rs.getString("source_version"),
            rs.getString("source_schema"),
            rs.getInt("format_version"),
            rs.getInt("table_count"),
            rs.getLong("total_rows"),
            rs.getLong("total_bytes"),
            rs.getString("manifest_json"),
            rs.getObject("owner_user_id", Long.class),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("expires_at"))
    );

    public MigrationArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(MigrationArtifact artifact) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO migration_artifact(
                        name, file_path, storage_profile_id, remote_path, file_size, checksum,
                        source_db_type, source_version, source_schema, format_version,
                        table_count, total_rows, total_bytes, manifest_json, owner_user_id, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, artifact.name());
            ps.setString(2, artifact.filePath());
            ps.setObject(3, artifact.storageProfileId());
            ps.setString(4, artifact.remotePath());
            ps.setLong(5, artifact.fileSize());
            ps.setString(6, artifact.checksum());
            ps.setString(7, artifact.sourceDbType());
            ps.setString(8, artifact.sourceVersion());
            ps.setString(9, artifact.sourceSchema());
            ps.setInt(10, artifact.formatVersion());
            ps.setInt(11, artifact.tableCount());
            ps.setLong(12, artifact.totalRows());
            ps.setLong(13, artifact.totalBytes());
            ps.setString(14, artifact.manifestJson());
            ps.setObject(15, artifact.ownerUserId());
            ps.setTimestamp(16, timestamp(artifact.expiresAt()));
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public Optional<MigrationArtifact> findById(long id) {
        List<MigrationArtifact> rows = jdbc.query("SELECT * FROM migration_artifact WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    public List<MigrationArtifact> findAll(Long ownerUserId, int limit, long offset) {
        if (ownerUserId == null) {
            return jdbc.query("SELECT * FROM migration_artifact ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                    mapper, limit, offset);
        }
        return jdbc.query("""
                SELECT * FROM migration_artifact
                WHERE owner_user_id IS NULL OR owner_user_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, mapper, ownerUserId, limit, offset);
    }

    public void updateRemotePath(long id, Long storageProfileId, String remotePath) {
        jdbc.update("UPDATE migration_artifact SET storage_profile_id = ?, remote_path = ? WHERE id = ?",
                storageProfileId, remotePath, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM migration_artifact WHERE id = ?", id);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
