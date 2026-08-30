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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class ConnectionAccessRepository {
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
        jdbc.query("SELECT group_id, user_id FROM app_user_group_member ORDER BY group_id, user_id", rs -> {
            members.computeIfAbsent(rs.getLong("group_id"), ignored -> new ArrayList<>()).add(rs.getLong("user_id"));
        });
        return groups.stream().map(group -> new UserGroup(
                group.id(), group.name(), group.description(), List.copyOf(members.getOrDefault(group.id(), List.of())),
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

    private void replaceMembers(long groupId, List<Long> memberUserIds) {
        jdbc.update("DELETE FROM app_user_group_member WHERE group_id = ?", groupId);
        for (Long userId : new LinkedHashSet<>(memberUserIds)) {
            jdbc.update("INSERT INTO app_user_group_member(group_id, user_id) VALUES (?, ?)", groupId, userId);
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
