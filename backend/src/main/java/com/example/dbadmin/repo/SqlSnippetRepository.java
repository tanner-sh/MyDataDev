package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.SqlSnippetResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class SqlSnippetRepository {
    private static final String COLUMNS = "id, name, description, sql_text, db_type, tags, use_count, last_used_at, actor, updated_at, visibility, owner_user_id";
    private final JdbcTemplate jdbc;

    public SqlSnippetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<SqlSnippetResponse> findAll(String keyword, String dbType, Long userId, boolean administrator) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (!administrator) {
            if (userId == null) where.append(" AND visibility = 'SHARED'");
            else {
                where.append(" AND (visibility = 'SHARED' OR owner_user_id = ?)");
                parameters.add(userId);
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOWER(name) LIKE ? ESCAPE '!' OR LOWER(description) LIKE ? ESCAPE '!'")
                    .append(" OR LOWER(sql_text) LIKE ? ESCAPE '!' OR LOWER(tags) LIKE ? ESCAPE '!')");
            String pattern = likePattern(keyword.trim().toLowerCase(Locale.ROOT));
            for (int index = 0; index < 4; index++) parameters.add(pattern);
        }
        if (dbType != null && !dbType.isBlank()) {
            where.append(" AND (db_type IS NULL OR db_type = '' OR db_type = ?)");
            parameters.add(dbType.trim().toLowerCase(Locale.ROOT));
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM sql_snippet" + where
                        + " ORDER BY CASE WHEN visibility = 'PERSONAL' THEN 0 ELSE 1 END, use_count DESC, id DESC",
                (rs, row) -> mapRow(rs, userId, administrator), parameters.toArray());
    }

    public Optional<SqlSnippetResponse> findById(long id, Long userId, boolean administrator) {
        String predicate = administrator ? "" : userId == null
                ? " AND visibility = 'SHARED'" : " AND (visibility = 'SHARED' OR owner_user_id = ?)";
        Object[] parameters = administrator || userId == null ? new Object[]{id} : new Object[]{id, userId};
        return jdbc.query("SELECT " + COLUMNS + " FROM sql_snippet WHERE id = ?" + predicate,
                (rs, row) -> mapRow(rs, userId, administrator), parameters).stream().findFirst();
    }

    public Optional<SqlSnippetResponse> findAnyById(long id, Long userId, boolean administrator) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sql_snippet WHERE id = ?",
                (rs, row) -> mapRow(rs, userId, administrator), id).stream().findFirst();
    }

    public long countVisible(Long userId, boolean administrator) {
        Long count;
        if (administrator) count = jdbc.queryForObject("SELECT COUNT(*) FROM sql_snippet", Long.class);
        else if (userId == null) count = jdbc.queryForObject("SELECT COUNT(*) FROM sql_snippet WHERE visibility = 'SHARED'", Long.class);
        else count = jdbc.queryForObject("SELECT COUNT(*) FROM sql_snippet WHERE visibility = 'SHARED' OR owner_user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    public long insert(String name, String description, String sql, String dbType, String tags, String actor,
                       String visibility, Long ownerUserId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO sql_snippet(name, description, sql_text, db_type, tags, actor, visibility, owner_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setString(3, sql);
            statement.setString(4, dbType);
            statement.setString(5, tags);
            statement.setString(6, actor);
            statement.setString(7, visibility);
            statement.setObject(8, ownerUserId);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("SQL 片段已保存，但数据库未返回主键");
        return id.longValue();
    }

    public void update(long id, String name, String description, String sql, String dbType, String tags,
                       String visibility, Long ownerUserId) {
        jdbc.update("""
                UPDATE sql_snippet
                SET name = ?, description = ?, sql_text = ?, db_type = ?, tags = ?,
                    visibility = ?, owner_user_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, name, description, sql, dbType, tags, visibility, ownerUserId, id);
    }

    public void delete(long id) { jdbc.update("DELETE FROM sql_snippet WHERE id = ?", id); }

    public void recordUse(long id) {
        jdbc.update("UPDATE sql_snippet SET use_count = use_count + 1, last_used_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public boolean nameTaken(String name, Long excludingId, String visibility, Long ownerUserId) {
        Integer count = "SHARED".equals(visibility) ? jdbc.queryForObject("""
                SELECT COUNT(*) FROM sql_snippet
                WHERE name = ? AND visibility = 'SHARED' AND (? IS NULL OR id <> ?)
                """, Integer.class, name, excludingId, excludingId == null ? 0L : excludingId) : jdbc.queryForObject("""
                SELECT COUNT(*) FROM sql_snippet
                WHERE name = ? AND visibility = ?
                  AND ((owner_user_id IS NULL AND ? IS NULL) OR owner_user_id = ?)
                  AND (? IS NULL OR id <> ?)
                """, Integer.class, name, visibility, ownerUserId, ownerUserId,
                excludingId, excludingId == null ? 0L : excludingId);
        return count != null && count > 0;
    }

    private static SqlSnippetResponse mapRow(java.sql.ResultSet rs, Long userId, boolean administrator)
            throws java.sql.SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        Long ownerUserId = rs.getObject("owner_user_id", Long.class);
        String visibility = rs.getString("visibility");
        boolean editable = administrator || userId != null && userId.equals(ownerUserId)
                || userId == null && "SHARED".equals(visibility);
        return new SqlSnippetResponse(
                rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getString("sql_text"),
                rs.getString("db_type"), rs.getString("tags"), rs.getLong("use_count"),
                lastUsed == null ? null : lastUsed.toInstant().toString(), rs.getString("actor"),
                updated == null ? null : updated.toInstant().toString(), visibility, ownerUserId, editable
        );
    }

    private String likePattern(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
