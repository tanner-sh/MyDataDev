package com.example.dbadmin.repo;

import com.example.dbadmin.access.ConnectionAccessPolicy;
import com.example.dbadmin.access.ConnectionAccessPolicy.Grant;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.access.UserGroup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class ConnectionAccessRepository {
    /** 管理员手工维护的成员关系；其余取值是身份提供器 ID（目前只有 OIDC），由 SSO 登录同步。 */
    private static final String LOCAL_SOURCE = "LOCAL";
    private static final String MODE_RESTRICTED = "RESTRICTED";

    private final JdbcTemplate jdbc;

    public ConnectionAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasAccess(long connectionId, long userId, ConnectionPermission permission) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM db_connection connection
                LEFT JOIN connection_access_policy policy ON policy.connection_id = connection.id
                WHERE connection.id = ? AND (
                    policy.connection_id IS NULL OR policy.access_mode = 'SHARED' OR policy.owner_user_id = ?
                    OR EXISTS (
                        SELECT 1 FROM connection_access_grant grant_row
                        WHERE grant_row.connection_id = connection.id
                          AND grant_row.permission IN (?, 'CONNECTION_ADMIN')
                          AND (
                              (grant_row.grantee_type = 'USER' AND grant_row.grantee_id = ?)
                              OR (grant_row.grantee_type = 'GROUP' AND EXISTS (
                                  SELECT 1 FROM app_user_group_member member
                                  WHERE member.group_id = grant_row.grantee_id AND member.user_id = ?
                              ))
                          )
                    )
                )
                """, Integer.class, connectionId, userId, permission.name(), userId, userId);
        return count != null && count > 0;
    }

    public boolean hasAnyAccess(long connectionId, long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM db_connection connection
                LEFT JOIN connection_access_policy policy ON policy.connection_id = connection.id
                WHERE connection.id = ? AND (
                    policy.connection_id IS NULL OR policy.access_mode = 'SHARED' OR policy.owner_user_id = ?
                    OR EXISTS (
                        SELECT 1 FROM connection_access_grant grant_row
                        WHERE grant_row.connection_id = connection.id
                          AND (
                              (grant_row.grantee_type = 'USER' AND grant_row.grantee_id = ?)
                              OR (grant_row.grantee_type = 'GROUP' AND EXISTS (
                                  SELECT 1 FROM app_user_group_member member
                                  WHERE member.group_id = grant_row.grantee_id AND member.user_id = ?
                              ))
                          )
                    )
                )
                """, Integer.class, connectionId, userId, userId, userId);
        return count != null && count > 0;
    }

    public Set<Long> allowedConnectionIds(long userId, ConnectionPermission permission) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT connection.id
                FROM db_connection connection
                LEFT JOIN connection_access_policy policy ON policy.connection_id = connection.id
                WHERE policy.connection_id IS NULL OR policy.access_mode = 'SHARED' OR policy.owner_user_id = ?
                   OR EXISTS (
                       SELECT 1 FROM connection_access_grant grant_row
                       WHERE grant_row.connection_id = connection.id
                         AND grant_row.permission IN (?, 'CONNECTION_ADMIN')
                         AND (
                             (grant_row.grantee_type = 'USER' AND grant_row.grantee_id = ?)
                             OR (grant_row.grantee_type = 'GROUP' AND EXISTS (
                                 SELECT 1 FROM app_user_group_member member
                                 WHERE member.group_id = grant_row.grantee_id AND member.user_id = ?
                             ))
                         )
                   )
                ORDER BY connection.id
                """, Long.class, userId, permission.name(), userId, userId));
    }

    public Set<Long> allowedConnectionIds(long userId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT connection.id
                FROM db_connection connection
                LEFT JOIN connection_access_policy policy ON policy.connection_id = connection.id
                WHERE policy.connection_id IS NULL OR policy.access_mode = 'SHARED' OR policy.owner_user_id = ?
                   OR EXISTS (
                       SELECT 1 FROM connection_access_grant grant_row
                       WHERE grant_row.connection_id = connection.id
                         AND (
                             (grant_row.grantee_type = 'USER' AND grant_row.grantee_id = ?)
                             OR (grant_row.grantee_type = 'GROUP' AND EXISTS (
                                 SELECT 1 FROM app_user_group_member member
                                 WHERE member.group_id = grant_row.grantee_id AND member.user_id = ?
                             ))
                         )
                   )
                ORDER BY connection.id
                """, Long.class, userId, userId, userId));
    }

    /**
     * 一次查出该用户在所有连接上的权限集合。
     *
     * <p>原来是每条连接先 connectionExists，再对 7 个权限各跑一次 {@link #hasAccess}（每次都是
     * 一个三层相关子查询）—— 50 条连接就是 400 条 SQL，而前端每次刷新连接列表都要调一遍。</p>
     *
     * <p>这里把授权行一次性 join 出来，共享/所有者/CONNECTION_ADMIN 的推导放在内存里做，
     * 判定规则与 {@code hasAccess} 保持一致。</p>
     */
    public Map<Long, Set<ConnectionPermission>> permissionsByConnection(long userId) {
        Set<ConnectionPermission> all = new LinkedHashSet<>(List.of(ConnectionPermission.values()));
        Map<Long, Set<ConnectionPermission>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT connection.id AS connection_id,
                       COALESCE(policy.access_mode, 'SHARED') AS access_mode,
                       policy.owner_user_id,
                       grant_row.permission AS permission
                FROM db_connection connection
                LEFT JOIN connection_access_policy policy ON policy.connection_id = connection.id
                LEFT JOIN connection_access_grant grant_row
                       ON grant_row.connection_id = connection.id
                      AND (
                          (grant_row.grantee_type = 'USER' AND grant_row.grantee_id = ?)
                          OR (grant_row.grantee_type = 'GROUP' AND EXISTS (
                              SELECT 1 FROM app_user_group_member member
                              WHERE member.group_id = grant_row.grantee_id AND member.user_id = ?
                          ))
                      )
                ORDER BY connection.id
                """, rs -> {
            long connectionId = rs.getLong("connection_id");
            Set<ConnectionPermission> permissions =
                    result.computeIfAbsent(connectionId, ignored -> new LinkedHashSet<>());
            Long owner = rs.getObject("owner_user_id", Long.class);
            if (!MODE_RESTRICTED.equals(rs.getString("access_mode")) || (owner != null && owner == userId)) {
                permissions.addAll(all);
                return;
            }
            String permission = rs.getString("permission");
            if (permission == null) return;
            ConnectionPermission parsed = parsePermission(permission);
            if (parsed == null) return;
            if (parsed == ConnectionPermission.CONNECTION_ADMIN) permissions.addAll(all);
            else permissions.add(parsed);
        }, userId, userId);
        result.values().removeIf(Set::isEmpty);
        return result;
    }

    /** 一次筛出实际存在的连接，省掉逐条 connectionExists。 */
    public Set<Long> existingConnectionIds(Collection<Long> ids) {
        if (ids.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT id FROM db_connection WHERE id IN (" + placeholders + ")", Long.class, ids.toArray()));
    }

    private static ConnectionPermission parsePermission(String value) {
        try {
            return ConnectionPermission.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            // 旧版本写入的权限码在这一版可能已经不存在，忽略比让整个列表报错好。
            return null;
        }
    }

    public Optional<ConnectionAccessPolicy> findPolicy(long connectionId) {
        List<PolicyRow> rows = jdbc.query("""
                SELECT connection.id AS connection_id,
                       COALESCE(policy.access_mode, 'SHARED') AS access_mode,
                       policy.owner_user_id
                FROM db_connection connection
                LEFT JOIN connection_access_policy policy ON policy.connection_id = connection.id
                WHERE connection.id = ?
                """, (rs, ignored) -> new PolicyRow(
                rs.getLong("connection_id"), rs.getString("access_mode"), rs.getObject("owner_user_id", Long.class)
        ), connectionId);
        if (rows.isEmpty()) return Optional.empty();
        PolicyRow row = rows.get(0);
        List<Grant> grants = jdbc.query("""
                SELECT grantee_type, grantee_id, permission FROM connection_access_grant
                WHERE connection_id = ? ORDER BY grantee_type, grantee_id, permission
                """, (rs, ignored) -> new Grant(
                rs.getString("grantee_type"), rs.getLong("grantee_id"), ConnectionPermission.valueOf(rs.getString("permission"))
        ), connectionId);
        return Optional.of(new ConnectionAccessPolicy(row.connectionId(), row.accessMode(), row.ownerUserId(), grants));
    }

    @Transactional
    public void initializeNewConnection(long connectionId, Long ownerUserId) {
        jdbc.update("""
                MERGE INTO connection_access_policy(connection_id, access_mode, owner_user_id, updated_at)
                KEY(connection_id) VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, connectionId, ownerUserId == null ? "SHARED" : "RESTRICTED", ownerUserId);
    }

    @Transactional
    public void replacePolicy(long connectionId, String accessMode, Long ownerUserId, List<Grant> grants) {
        int changed = jdbc.update("""
                MERGE INTO connection_access_policy(connection_id, access_mode, owner_user_id, updated_at)
                KEY(connection_id) VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, connectionId, accessMode, ownerUserId);
        if (changed == 0) throw new IllegalArgumentException("连接不存在");
        jdbc.update("DELETE FROM connection_access_grant WHERE connection_id = ?", connectionId);
        for (Grant grant : grants) {
            jdbc.update("""
                    INSERT INTO connection_access_grant(connection_id, grantee_type, grantee_id, permission)
                    VALUES (?, ?, ?, ?)
                    """, connectionId, grant.granteeType(), grant.granteeId(), grant.permission().name());
        }
    }

    public List<UserGroup> findGroups() {
        List<GroupRow> groups = jdbc.query("""
                SELECT id, name, description, created_at, updated_at FROM app_user_group ORDER BY name, id
                """, (rs, ignored) -> new GroupRow(
                rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))
        ));
        Map<Long, List<Long>> members = new LinkedHashMap<>();
        Map<Long, List<Long>> external = new LinkedHashMap<>();
        jdbc.query("SELECT group_id, user_id, source_provider FROM app_user_group_member ORDER BY group_id, user_id", rs -> {
            long groupId = rs.getLong("group_id");
            long userId = rs.getLong("user_id");
            members.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(userId);
            // 身份提供器同步来的成员关系归它管：界面得能把这部分标出来，管理员保存时也不该动它。
            if (!LOCAL_SOURCE.equals(rs.getString("source_provider"))) {
                external.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(userId);
            }
        });
        return groups.stream().map(group -> new UserGroup(
                group.id(), group.name(), group.description(), List.copyOf(members.getOrDefault(group.id(), List.of())),
                List.copyOf(external.getOrDefault(group.id(), List.of())),
                group.createdAt(), group.updatedAt()
        )).toList();
    }

    public Optional<UserGroup> findGroup(long id) {
        return findGroups().stream().filter(group -> group.id() == id).findFirst();
    }

    @Transactional
    public long insertGroup(String name, String description, List<Long> memberUserIds) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO app_user_group(name, description) VALUES (?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, description);
            return statement;
        }, keys);
        Number id = generatedId(keys);
        if (id == null) throw new IllegalStateException("无法获取新建用户组 ID");
        replaceMembers(id.longValue(), memberUserIds);
        return id.longValue();
    }

    @Transactional
    public void updateGroup(long id, String name, String description, List<Long> memberUserIds) {
        int changed = jdbc.update("""
                UPDATE app_user_group SET name = ?, description = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, name, description, id);
        if (changed == 0) throw new IllegalArgumentException("用户组不存在");
        replaceMembers(id, memberUserIds);
    }

    @Transactional
    public void deleteGroup(long id) {
        jdbc.update("DELETE FROM connection_access_grant WHERE grantee_type = 'GROUP' AND grantee_id = ?", id);
        int changed = jdbc.update("DELETE FROM app_user_group WHERE id = ?", id);
        if (changed == 0) throw new IllegalArgumentException("用户组不存在");
    }

    public boolean userExists(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    public boolean groupExists(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user_group WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    public boolean connectionExists(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM db_connection WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /**
     * 用管理员提交的名单替换<b>手工</b>成员关系。
     *
     * <p>{@code source_provider} 决定这条成员关系归谁管：SSO 每次登录只删除并重建自己那个
     * provider 的行。这里如果照旧全删重插，同步来的行就会变成 LOCAL —— IdP 之后把用户移出组，
     * 下次登录的清理再也匹配不到它，用户会永久保留这个组和它挂着的全部连接授权。</p>
     *
     * <p>所以：只删 LOCAL 行；提交名单里已经被某个 provider 持有的用户跳过（保留它原本的归属，
     * 也避免撞主键）。名单里少掉的外部成员不会被删 —— 那要去 IdP 里改，在这里删掉也会在下次
     * 登录时被同步回来。</p>
     */
    private void replaceMembers(long groupId, List<Long> memberUserIds) {
        jdbc.update("DELETE FROM app_user_group_member WHERE group_id = ? AND source_provider = ?", groupId, LOCAL_SOURCE);
        Set<Long> external = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT user_id FROM app_user_group_member WHERE group_id = ? AND source_provider <> ?",
                Long.class, groupId, LOCAL_SOURCE));
        for (Long userId : new LinkedHashSet<>(memberUserIds)) {
            if (external.contains(userId)) continue;
            jdbc.update("INSERT INTO app_user_group_member(group_id, user_id, source_provider) VALUES (?, ?, ?)",
                    groupId, userId, LOCAL_SOURCE);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Number generatedId(GeneratedKeyHolder keys) {
        try {
            Number direct = keys.getKey();
            if (direct != null) return direct;
        } catch (org.springframework.dao.InvalidDataAccessApiUsageException ignored) {
            // H2 会返回多个生成列，下面按名称取 id。
        }
        Map<String, Object> values = keys.getKeys();
        if (values == null) return null;
        return values.entrySet().stream()
                .filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).filter(Number.class::isInstance).map(Number.class::cast)
                .findFirst().orElse(null);
    }

    private record PolicyRow(long connectionId, String accessMode, Long ownerUserId) {}
    private record GroupRow(long id, String name, String description, Instant createdAt, Instant updatedAt) {}
}
