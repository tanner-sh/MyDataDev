package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.repo.UserAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * OIDC 模式下角色完全由 admin-groups 决定，而且每次登录都会覆写 app_user.role。组为空时
 * 谁都当不上管理员，管理、审计与 MCP 页面全部无人可进 —— 这种配置只能拦在启动阶段。
 */
class OidcIdentityProviderStartupTest {
    @Test
    void refusesOidcModeWithoutAdminGroups() {
        assertThatThrownBy(() -> provider("OIDC", List.of()).requireReachableAdministrator())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin-groups");
    }

    @Test
    void refusesOidcModeWhenAdminGroupsAreAllBlank() {
        assertThatThrownBy(() -> provider("OIDC", List.of("  ")).requireReachableAdministrator())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin-groups");
    }

    @Test
    void acceptsOidcModeWithAnAdminGroup() {
        assertThatCode(() -> provider("OIDC", List.of("db-admins")).requireReachableAdministrator())
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresTheCheckWhenOidcIsNotTheActiveMode() {
        assertThatCode(() -> provider("LOCAL", List.of()).requireReachableAdministrator())
                .doesNotThrowAnyException();
        assertThatCode(() -> provider("DISABLED", List.of()).requireReachableAdministrator())
                .doesNotThrowAnyException();
    }

    private OidcIdentityProvider provider(String mode, List<String> adminGroups) {
        AppProperties properties = new AppProperties();
        properties.getAuth().setMode(mode);
        properties.getAuth().getOidc().setAdminGroups(adminGroups);
        return new OidcIdentityProvider(mock(UserAccountRepository.class), properties);
    }
}
