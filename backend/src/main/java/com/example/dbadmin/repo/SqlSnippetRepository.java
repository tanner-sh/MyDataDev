package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.SqlSnippetResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class SqlSnippetRepository {
    private final JdbcTemplate jdbc;

    public SqlSnippetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按「常用优先」列出片段。
     *
     * <p>{@code dbType} 非空时只返回通用片段与同类型片段：一条 Oracle 的 ROWNUM 查询出现在
     * MySQL 连接的候选里只会添乱。</p>
     */
    public List<SqlSnippetResponse> findAll(String keyword, String dbType) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
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
        return jdbc.query(
                "SELECT id, name, description, sql_text, db_type, tags, use_count, last_used_at, actor, updated_at"
                        + " FROM sql_snippet" + where + " ORDER BY use_count DESC, id DESC",
                SqlSnippetRepository::mapRow,
                parameters.toArray()
        );
    }

    public Optional<SqlSnippetResponse> findById(long id) {
        return jdbc.query(
                "SELECT id, name, description, sql_text, db_type, tags, use_count, last_used_at, actor, updated_at"
                        + " FROM sql_snippet WHERE id = ?",
                SqlSnippetRepository::mapRow,
                id
        ).stream().findFirst();
    }

    public long insert(String name, String description, String sql, String dbType, String tags, String actor) {
        jdbc.update("""
                INSERT INTO sql_snippet(name, description, sql_text, db_type, tags, actor)
                VALUES (?, ?, ?, ?, ?, ?)
                """, name, description, sql, dbType, tags, actor);
        Long id = jdbc.queryForObject("SELECT id FROM sql_snippet WHERE name = ?", Long.class, name);
        return id == null ? 0 : id;
    }

    public void update(long id, String name, String description, String sql, String dbType, String tags) {
        jdbc.update("""
                UPDATE sql_snippet
                SET name = ?, description = ?, sql_text = ?, db_type = ?, tags = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, name, description, sql, dbType, tags, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM sql_snippet WHERE id = ?", id);
    }

    /** 插入时记一次使用，让常用片段自然浮到前面。 */
    public void recordUse(long id) {
        jdbc.update("UPDATE sql_snippet SET use_count = use_count + 1, last_used_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public boolean nameTaken(String name, Long excludingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sql_snippet WHERE name = ? AND (? IS NULL OR id <> ?)",
                Integer.class, name, excludingId, excludingId == null ? 0L : excludingId
        );
        return count != null && count > 0;
    }

    private static SqlSnippetResponse mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new SqlSnippetResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("sql_text"),
                rs.getString("db_type"),
                rs.getString("tags"),
                rs.getLong("use_count"),
                lastUsed == null ? null : lastUsed.toInstant().toString(),
                rs.getString("actor"),
                updated == null ? null : updated.toInstant().toString()
        );
    }

    private String likePattern(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
