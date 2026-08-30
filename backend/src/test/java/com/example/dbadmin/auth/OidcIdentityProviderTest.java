package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.repo.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OidcIdentityProviderTest {
    @Test
    void provisionsBySubjectAndMapsRoleAndLocalGroups() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:oidc-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UserAccountRepository users = new UserAccountRepository(jdbc);
        AppProperties properties = new AppProperties();
        properties.getAuth().getOidc().setAdminGroups(java.util.List.of("db-admins"));
        properties.getAuth().getOidc().setGroupMappings(Map.of("finance", "财务组"));
        OidcIdentityProvider provider = new OidcIdentityProvider(users, properties);
        OidcUser principal = mock(OidcUser.class);
        when(principal.getSubject()).thenReturn("stable-subject-7");
        when(principal.getClaims()).thenReturn(Map.of(
                "preferred_username", "Alice", "name", "Alice Chen", "groups", java.util.List.of("db-admins", "finance")));

        WebIdentity first = provider.login(principal).orElseThrow();
        WebIdentity second = provider.login(principal).orElseThrow();

        assertThat(first.userId()).isEqualTo(second.userId());
        assertThat(second.role()).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_user_group_member member
                JOIN app_user_group user_group ON user_group.id = member.group_id
                WHERE member.user_id = ? AND user_group.name = '财务组' AND member.source_provider = 'OIDC'
                """, Integer.class, first.userId())).isEqualTo(1);
    }
}
