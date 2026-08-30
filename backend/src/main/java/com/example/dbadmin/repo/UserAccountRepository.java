package com.example.dbadmin.repo;

import com.example.dbadmin.auth.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.LinkedHashSet;

@Repository
public class UserAccountRepository {
    private static final String COLUMNS = "id, provider, subject, username, display_name, password_hash, role, enabled, auth_version, last_login_at, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public UserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 锁住当前所有启用管理员，调用方必须处于事务中。并发降权/删除会按同一行顺序串行执行，
     * 后到的事务会在拿到锁后看到最新管理员集合。
     */
    public long lockEnabledAdministrators() {
        return jdbc.queryForList("""
                SELECT id FROM app_user
                WHERE enabled = TRUE AND role = 'ADMIN'
                ORDER BY id
                FOR UPDATE
                """, Long.class).size();
    }

    public List<UserAccount> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user ORDER BY id", UserAccountRepository::map);
    }

    public Optional<UserAccount> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE id = ?", UserAccountRepository::map, id)
                .stream().findFirst();
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE username = ?", UserAccountRepository::map, username)
                .stream().findFirst();
    }

    public Optional<UserAccount> findByProviderSubject(String provider, String subject) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE provider = ? AND subject = ?",
                UserAccountRepository::map, provider, subject).stream().findFirst();
    }

    public long insert(String provider, String subject, String username, String displayName, String passwordHash, String role, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO app_user(provider, subject, username, display_name, password_hash, role, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, provider);
            statement.setString(2, subject);
            statement.setString(3, username);
            statement.setString(4, displayName);
            statement.setString(5, passwordHash);
            statement.setString(6, role);
            statement.setBoolean(7, enabled);
            return statement;
        }, keys);
        Number id = generatedId(keys);
        if (id == null) throw new IllegalStateException("无法获取新建用户 ID");
        return id.longValue();
    }

    public void update(long id, String subject, String username, String displayName, String role, boolean enabled, boolean invalidateSessions) {
        int changed = jdbc.update("""
                UPDATE app_user
                SET subject = ?, username = ?, display_name = ?, role = ?, enabled = ?,
                    auth_version = auth_version + ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, subject, username, displayName, role, enabled, invalidateSessions ? 1 : 0, id);
        if (changed == 0) throw new IllegalArgumentException("用户不存在");
    }

    public void updatePassword(long id, String passwordHash) {
        int changed = jdbc.update("""
                UPDATE app_user SET password_hash = ?, auth_version = auth_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, passwordHash, id);
        if (changed == 0) throw new IllegalArgumentException("用户不存在");
    }

    public void recordLogin(long id) {
        jdbc.update("UPDATE app_user SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    /** SSO 登录只同步身份提供器声明的资料和角色；管理员手工停用的账号不会被重新启用。 */
    public void updateExternalProfile(long id, String username, String displayName, String role) {
        jdbc.update("""
                UPDATE app_user
                SET username = ?, display_name = ?, role = ?,
                    auth_version = auth_version + CASE WHEN role <> ? THEN 1 ELSE 0 END,
                    last_login_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, username, displayName, role, role, id);
    }

    public boolean usernameBelongsToOther(String username, Long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ? AND (? IS NULL OR id <> ?)",
                Integer.class, username, userId, userId == null ? 0L : userId);
        return count != null && count > 0;
    }

    public long countOwnedSqlSnippets(long userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sql_snippet WHERE owner_user_id = ? AND visibility = 'PERSONAL'", Long.class, userId);
        return count == null ? 0 : count;
    }

    public void releaseSharedSqlSnippets(long userId) {
        jdbc.update("UPDATE sql_snippet SET owner_user_id = NULL WHERE owner_user_id = ? AND visibility = 'SHARED'", userId);
    }

    /** 用 source_provider 标记自动成员关系，SSO 重登时只替换自身同步的数据，保留管理员手工分组。 */
    public void syncExternalGroups(long userId, String provider, Collection<String> localGroupNames) {
        jdbc.update("DELETE FROM app_user_group_member WHERE user_id = ? AND source_provider = ?", userId, provider);
        for (String rawName : new LinkedHashSet<>(localGroupNames)) {
            String name = rawName == null ? "" : rawName.trim();
            if (name.isEmpty()) continue;
            List<Long> ids = jdbc.queryForList("SELECT id FROM app_user_group WHERE name = ?", Long.class, name);
            long groupId;
            if (ids.isEmpty()) {
                jdbc.update("INSERT INTO app_user_group(name, description) VALUES (?, ?)", name, "由 " + provider + " 自动同步");
                groupId = jdbc.queryForObject("SELECT id FROM app_user_group WHERE name = ?", Long.class, name);
            } else groupId = ids.get(0);
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM app_user_group_member WHERE group_id = ? AND user_id = ?",
                    Integer.class, groupId, userId);
            if (exists == null || exists == 0) {
                jdbc.update("INSERT INTO app_user_group_member(group_id, user_id, source_provider) VALUES (?, ?, ?)",
                        groupId, userId, provider);
            }
        }
    }

    public void delete(long id) {
        // grantee_id 是 USER/GROUP 共用的多态字段，无法用外键级联用户授权，需显式清理。
        jdbc.update("DELETE FROM connection_access_grant WHERE grantee_type = 'USER' AND grantee_id = ?", id);
        int changed = jdbc.update("DELETE FROM app_user WHERE id = ?", id);
        if (changed == 0) throw new IllegalArgumentException("用户不存在");
    }

    private static UserAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
                rs.getLong("id"), rs.getString("provider"), rs.getString("subject"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("enabled"), rs.getLong("auth_version"),
                instant(rs.getTimestamp("last_login_at")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))
        );
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Number generatedId(KeyHolder keys) {
        try {
            Number direct = keys.getKey();
            if (direct != null) return direct;
        } catch (org.springframework.dao.InvalidDataAccessApiUsageException ignored) {
            // H2 may return more than one generated column; fall back to the id entry below.
        }
        Map<String, Object> values = keys.getKeys();
        if (values == null) return null;
        return values.entrySet().stream()
                .filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .findFirst().orElse(null);
    }
}
