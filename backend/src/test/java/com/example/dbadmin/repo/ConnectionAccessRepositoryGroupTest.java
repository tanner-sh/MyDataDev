package com.example.dbadmin.repo;

import com.example.dbadmin.access.ConnectionAccessPolicy;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.access.UserGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 用户组成员关系的归属：管理员编辑不能把 SSO 同步来的成员据为己有。 */
class ConnectionAccessRepositoryGroupTest {
    private JdbcTemplate jdbc;
    private ConnectionAccessRepository repository;
    private UserAccountRepository users;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:groups-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new ConnectionAccessRepository(jdbc);
        users = new UserAccountRepository(jdbc);
    }

    @Test
    void editingAGroupKeepsExternalMembershipRevocable() {
        long synced = users.insert("OIDC", "sub-1", "synced", "Synced", null, "OPERATOR", true);
        long manual = users.insert("LOCAL", "manual", "manual", "Manual", null, "OPERATOR", true);
        long groupId = repository.insertGroup("analysts", null, List.of(manual));
        users.syncExternalGroups(synced, "OIDC", List.of("analysts"));
        assertThat(members(groupId)).containsExactlyInAnyOrder(manual, synced);

        // 管理员打开分组照原样保存 —— 名单里带着那个 SSO 成员。
        repository.updateGroup(groupId, "analysts", "只读分析", List.of(manual, synced));

        assertThat(sourceOf(groupId, synced)).isEqualTo("OIDC");
        assertThat(sourceOf(groupId, manual)).isEqualTo("LOCAL");

        // IdP 把用户移出该组之后，下一次登录的同步必须还能清掉这条成员关系。
        users.syncExternalGroups(synced, "OIDC", List.of());
        assertThat(members(groupId)).containsExactly(manual);
    }

    @Test
    void externalMembersAreReportedSeparatelyAndSurviveRemovalFromTheForm() {
        long synced = users.insert("OIDC", "sub-2", "synced", "Synced", null, "OPERATOR", true);
        long manual = users.insert("LOCAL", "manual", "manual", "Manual", null, "OPERATOR", true);
        long groupId = repository.insertGroup("ops", null, List.of(manual));
        users.syncExternalGroups(synced, "OIDC", List.of("ops"));

        UserGroup group = repository.findGroup(groupId).orElseThrow();
        assertThat(group.memberUserIds()).containsExactlyInAnyOrder(manual, synced);
        assertThat(group.externalMemberUserIds()).containsExactly(synced);

        // 提交名单里去掉 SSO 成员不生效：那条关系归 IdP，下次登录也会被同步回来。
        repository.updateGroup(groupId, "ops", null, List.of(manual));
        assertThat(members(groupId)).containsExactlyInAnyOrder(manual, synced);
        // 手工成员照常可以移除。
        repository.updateGroup(groupId, "ops", null, List.of());
        assertThat(members(groupId)).containsExactly(synced);
    }

    /**
     * /access/me 一次查完所有连接的权限。以前是每条连接 8 条 SQL（存在性检查 + 7 个权限各一个
     * 三层相关子查询），前端每次刷新连接列表都要付这个代价。
     */
    @Test
    void permissionsForEveryConnectionComeFromOneQuery() {
        long reader = users.insert("LOCAL", "reader", "reader", "Reader", null, "OPERATOR", true);
        long owner = users.insert("LOCAL", "owner", "owner", "Owner", null, "OPERATOR", true);
        long shared = connection("shared-dev");
        long restricted = connection("restricted-prod");
        long owned = connection("owned-stage");
        long invisible = connection("invisible");
        repository.replacePolicy(shared, "SHARED", null, List.of());
        repository.replacePolicy(owned, "RESTRICTED", owner, List.of());
        repository.replacePolicy(invisible, "RESTRICTED", owner, List.of());
        repository.replacePolicy(restricted, "RESTRICTED", owner, List.of(
                new ConnectionAccessPolicy.Grant("USER", reader, ConnectionPermission.QUERY),
                new ConnectionAccessPolicy.Grant("USER", reader, ConnectionPermission.EXPORT)));

        var permissions = repository.permissionsByConnection(reader);

        assertThat(permissions.get(shared)).containsExactlyInAnyOrder(ConnectionPermission.values());
        assertThat(permissions.get(restricted))
                .containsExactlyInAnyOrder(ConnectionPermission.QUERY, ConnectionPermission.EXPORT);
        assertThat(permissions).doesNotContainKeys(owned, invisible);
        // 结果必须和逐条 hasAccess 判定完全一致。
        for (long connectionId : List.of(shared, restricted, owned, invisible)) {
            for (ConnectionPermission permission : ConnectionPermission.values()) {
                assertThat(permissions.getOrDefault(connectionId, java.util.Set.of()).contains(permission))
                        .as("connection %s / %s", connectionId, permission)
                        .isEqualTo(repository.hasAccess(connectionId, reader, permission));
            }
        }
    }

    @Test
    void connectionAdminGrantImpliesEveryPermission() {
        long operator = users.insert("LOCAL", "op", "op", "Op", null, "OPERATOR", true);
        long connectionId = connection("managed");
        repository.replacePolicy(connectionId, "RESTRICTED", null, List.of(
                new ConnectionAccessPolicy.Grant("USER", operator, ConnectionPermission.CONNECTION_ADMIN)));

        assertThat(repository.permissionsByConnection(operator).get(connectionId))
                .containsExactlyInAnyOrder(ConnectionPermission.values());
    }

    @Test
    void existingConnectionIdsFiltersOutUnknownIds() {
        long connectionId = connection("real");

        assertThat(repository.existingConnectionIds(List.of(connectionId, 9_999L))).containsExactly(connectionId);
        assertThat(repository.existingConnectionIds(List.of())).isEmpty();
    }

    private long connection(String name) {
        jdbc.update("""
                INSERT INTO db_connection(name, db_type, jdbc_url, username, encrypted_password, environment, readonly)
                VALUES (?, 'H2', 'jdbc:h2:mem:x', 'sa', '', 'dev', FALSE)
                """, name);
        return jdbc.queryForObject("SELECT id FROM db_connection WHERE name = ?", Long.class, name);
    }

    private List<Long> members(long groupId) {
        return jdbc.queryForList("SELECT user_id FROM app_user_group_member WHERE group_id = ?", Long.class, groupId);
    }

    private String sourceOf(long groupId, long userId) {
        return jdbc.queryForObject(
                "SELECT source_provider FROM app_user_group_member WHERE group_id = ? AND user_id = ?",
                String.class, groupId, userId);
    }
}
