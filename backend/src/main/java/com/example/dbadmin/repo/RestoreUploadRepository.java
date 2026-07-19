package com.example.dbadmin.repo;

import com.example.dbadmin.model.RestoreUpload;
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
public class RestoreUploadRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<RestoreUpload> mapper = (rs, ignored) -> new RestoreUpload(
            rs.getLong("id"), rs.getString("original_name"), rs.getString("file_path"), rs.getLong("file_size"),
            rs.getString("checksum_sha256"), rs.getString("file_format"), rs.getString("source_db_type"),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("expires_at"))
    );

    public RestoreUploadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(RestoreUpload upload) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO restore_upload(original_name, file_path, file_size, checksum_sha256, file_format, source_db_type, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, upload.originalName());
            ps.setString(2, upload.filePath());
            ps.setLong(3, upload.fileSize());
            ps.setString(4, upload.checksumSha256());
            ps.setString(5, upload.fileFormat());
            ps.setString(6, upload.sourceDbType());
            ps.setTimestamp(7, Timestamp.from(upload.expiresAt()));
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public Optional<RestoreUpload> findById(long id) {
        List<RestoreUpload> rows = jdbc.query("SELECT * FROM restore_upload WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    public List<RestoreUpload> findExpired(Instant now) {
        return jdbc.query("SELECT * FROM restore_upload WHERE expires_at < ?", mapper, Timestamp.from(now));
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM restore_upload WHERE id = ?", id);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
