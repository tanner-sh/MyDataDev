package com.example.dbadmin.auth;

import com.example.dbadmin.dto.UserAdminDtos.UserCreateRequest;
import com.example.dbadmin.dto.UserAdminDtos.UserResponse;
import com.example.dbadmin.dto.UserAdminDtos.UserUpdateRequest;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.UserAccountRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class UserAccountService {
    private static final Set<String> ROLES = Set.of("ADMIN", "OPERATOR");
    private final UserAccountRepository repository;
    private final LocalDatabaseIdentityProvider localIdentityProvider;
    private final AuditRepository audit;

    public UserAccountService(
            UserAccountRepository repository,
            LocalDatabaseIdentityProvider localIdentityProvider,
            AuditRepository audit
    ) {
        this.repository = repository;
        this.localIdentityProvider = localIdentityProvider;
        this.audit = audit;
    }

    public List<UserResponse> list() {
        return repository.findAll().stream().map(this::response).toList();
    }

    @Transactional
    public UserResponse create(UserCreateRequest request, WebIdentity actor) {
        String username = LocalDatabaseIdentityProvider.normalizeUsername(request.username());
        String displayName = normalizeDisplayName(request.displayName());
        String role = normalizeRole(request.role());
        try {
            long id = repository.insert(
                    LocalDatabaseIdentityProvider.PROVIDER, username, username, displayName,
                    localIdentityProvider.encodePassword(request.password()), role, request.enabled()
            );
            audit.global(actor.username(), "USER_CREATE", "user:" + username, "role=" + role + ", enabled=" + request.enabled());
            return response(require(id));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("用户名已存在：" + username);
        }
    }

    @Transactional
    public UserResponse update(long id, UserUpdateRequest request, WebIdentity actor) {
        UserAccount existing = require(id);
        if (!LocalDatabaseIdentityProvider.PROVIDER.equals(existing.provider())) {
            return updateExternal(existing, request, actor);
        }
        String username = LocalDatabaseIdentityProvider.normalizeUsername(request.username());
        String displayName = normalizeDisplayName(request.displayName());
        String role = normalizeRole(request.role());
        boolean self = actor.userId() == id;
        if (self && !existing.username().equals(username)) throw new IllegalArgumentException("不能修改当前登录账号的用户名");
        if (self && (!request.enabled() || !existing.role().equals(role))) {
            throw new IllegalArgumentException("不能停用当前账号或修改自己的角色");
        }
        protectLastAdministrator(existing, role, request.enabled());
        try {
            boolean invalidateSessions = !existing.username().equals(username)
                    || !existing.role().equals(role) || existing.enabled() != request.enabled();
            repository.update(id, username, username, displayName, role, request.enabled(), invalidateSessions);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("用户名已存在：" + username);
        }
        if (request.password() != null && !request.password().isBlank()) {
            repository.updatePassword(id, localIdentityProvider.encodePassword(request.password()));
            audit.global(actor.username(), "USER_PASSWORD_RESET", "user:" + username, "self=" + self);
        }
        if (existing.enabled() != request.enabled()) {
            audit.global(actor.username(), request.enabled() ? "USER_ENABLE" : "USER_DISABLE", "user:" + username, null);
        }
        if (!existing.role().equals(role)) {
            audit.global(actor.username(), "USER_ROLE_CHANGE", "user:" + username,
                    "from=" + existing.role() + ", to=" + role);
        }
        audit.global(actor.username(), "USER_UPDATE", "user:" + username,
                "role=" + role + ", enabled=" + request.enabled() + ", passwordReset=" + (request.password() != null && !request.password().isBlank()));
        return response(require(id));
    }

    /**
     * SSO 账号只能启用/停用。
     *
     * <p>用户名、显示名和角色每次登录都会被身份提供器同步覆写，这里改了也留不住。以前是把这三个
     * 字段直接忽略再原样存回去，接口照样返回成功 —— 界面上它们是禁用的，但直接调接口的人会以为
     * 改动生效了。宁可明确报错。</p>
     */
    private UserResponse updateExternal(UserAccount existing, UserUpdateRequest request, WebIdentity actor) {
        if (actor.userId() == existing.id() && !request.enabled()) throw new IllegalArgumentException("不能停用当前账号");
        if (request.password() != null && !request.password().isBlank()) throw new IllegalArgumentException("SSO 账号不支持本地密码");
        requireUnchangedExternalProfile(existing, request);
        protectLastAdministrator(existing, existing.role(), request.enabled());
        repository.update(existing.id(), existing.subject(), existing.username(), existing.displayName(),
                existing.role(), request.enabled(), existing.enabled() != request.enabled());
        if (existing.enabled() != request.enabled()) {
            audit.global(actor.username(), request.enabled() ? "USER_ENABLE" : "USER_DISABLE",
                    "user:" + existing.username(), "provider=" + existing.provider());
        }
        return response(require(existing.id()));
    }

    @Transactional
    public void delete(long id, WebIdentity actor) {
        UserAccount existing = require(id);
        if (actor.userId() == id) throw new IllegalArgumentException("不能删除当前登录账号");
        if (!LocalDatabaseIdentityProvider.PROVIDER.equals(existing.provider())) {
            throw new IllegalArgumentException("SSO 账号请停用而不要删除，避免下次登录重新创建");
        }
        if (repository.countOwnedSqlSnippets(id) > 0) {
            throw new IllegalArgumentException("该用户仍拥有 SQL 片段，请先由管理员转为团队共享或删除片段");
        }
        protectLastAdministrator(existing, null, false);
        repository.releaseSharedSqlSnippets(id);
        repository.delete(id);
        audit.global(actor.username(), "USER_DELETE", "user:" + existing.username(), "role=" + existing.role());
    }

    private void protectLastAdministrator(UserAccount existing, String newRole, boolean enabled) {
        boolean removesEnabledAdmin = existing.enabled() && "ADMIN".equals(existing.role())
                && (!enabled || !"ADMIN".equals(newRole));
        if (removesEnabledAdmin && repository.countEnabledAdmins() <= 1) {
            throw new IllegalArgumentException("系统至少需要保留一个启用的管理员账号");
        }
    }

    private UserAccount require(long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private void requireUnchangedExternalProfile(UserAccount existing, UserUpdateRequest request) {
        String username = LocalDatabaseIdentityProvider.normalizeUsername(request.username());
        if (!existing.username().equals(username)
                || !existing.displayName().equals(normalizeDisplayName(request.displayName()))
                || !existing.role().equals(normalizeRole(request.role()))) {
            throw new IllegalArgumentException(
                    "SSO 账号的用户名、显示名与角色由身份提供器同步，请到身份提供器里修改；这里只能启用或停用账号。");
        }
    }

    private String normalizeRole(String value) {
        String role = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) throw new IllegalArgumentException("角色只支持 ADMIN 或 OPERATOR");
        return role;
    }

    private String normalizeDisplayName(String value) {
        String displayName = value == null ? "" : value.trim();
        if (displayName.isBlank()) throw new IllegalArgumentException("显示名称不能为空");
        return displayName;
    }

    private UserResponse response(UserAccount account) {
        return new UserResponse(
                account.id(), account.provider(), account.username(), account.displayName(), account.role(), account.enabled(),
                account.lastLoginAt(), account.createdAt(), account.updatedAt()
        );
    }
}
