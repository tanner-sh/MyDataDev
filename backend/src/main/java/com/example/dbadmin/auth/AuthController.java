package com.example.dbadmin.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.dbadmin.repo.AuditRepository;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final WebAuthenticationService authenticationService;
    private final AuditRepository audit;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(WebAuthenticationService authenticationService, AuditRepository audit) {
        this.authenticationService = authenticationService;
        this.audit = audit;
    }

    @GetMapping("/status")
    public AuthStatus status(Authentication authentication, CsrfToken csrfToken) {
        boolean authenticated = !authenticationService.enabled()
                || authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal());
        WebIdentity identity = authenticated && authenticationService.enabled() ? identity(authentication) : null;
        return authStatus(authenticated, identity, csrfToken);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse,
            CsrfToken csrfToken
    ) {
        if (!authenticationService.enabled()) {
            return ResponseEntity.ok(authStatus(true, null, null));
        }
        WebAuthenticationService.LoginResult result = authenticationService.authenticate(
                servletRequest.getRemoteAddr(), request.username(), request.password()
        );
        if (result.locked()) {
            audit.global("anonymous", "AUTH_LOGIN_FAILED", "user:" + safeUsername(request.username()), "reason=locked");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(result.retryAfterSeconds()))
                    .body(new AuthError("AUTH_LOCKED", "登录失败次数过多，请稍后重试。"));
        }
        if (!result.authenticated()) {
            audit.global("anonymous", "AUTH_LOGIN_FAILED", "user:" + safeUsername(request.username()), "reason=invalid_credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthError("INVALID_CREDENTIALS", "用户名或密码错误。"));
        }

        HttpSession oldSession = servletRequest.getSession(false);
        if (oldSession != null) oldSession.invalidate();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                result.identity(), null, List.of(new SimpleGrantedAuthority("ROLE_" + result.identity().role()))
        ));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, servletRequest, servletResponse);
        CsrfToken freshToken = (CsrfToken) servletRequest.getAttribute(CsrfToken.class.getName());
        audit.global(result.identity().username(), "AUTH_LOGIN", "user:" + result.identity().username(),
                "provider=" + result.identity().provider() + ", role=" + result.identity().role());
        return ResponseEntity.ok(authStatus(true, result.identity(), freshToken == null ? csrfToken : freshToken));
    }

    @PostMapping("/logout")
    public AuthStatus logout(HttpServletRequest request, CsrfToken csrfToken, Authentication authentication) {
        if (!authenticationService.enabled()) return authStatus(true, null, null);
        WebIdentity current = identity(authentication);
        if (current != null) audit.global(current.username(), "AUTH_LOGOUT", "user:" + current.username(), null);
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return authStatus(false, null, csrfToken);
    }

    private AuthStatus authStatus(boolean authenticated, WebIdentity identity, CsrfToken csrfToken) {
        return new AuthStatus(
                authenticationService.enabled(), authenticated,
                identity == null ? null : identity.username(),
                identity == null ? null : identity.displayName(),
                identity == null ? null : identity.role(),
                authenticationService.providerId(), authenticationService.passwordLogin(), authenticationService.loginUrl(),
                csrfToken == null ? null : csrfToken.getToken(), csrfToken == null ? null : csrfToken.getHeaderName()
        );
    }

    private WebIdentity identity(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof WebIdentity identity ? identity : null;
    }

    private String safeUsername(String username) {
        String normalized = LocalDatabaseIdentityProvider.normalizeUsername(username);
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    public record LoginRequest(@NotBlank @Size(max = 120) String username, @NotBlank @Size(max = 1_000) String password) {}
    public record AuthStatus(
            boolean enabled,
            boolean authenticated,
            String username,
            String displayName,
            String role,
            String provider,
            boolean passwordLogin,
            String loginUrl,
            String csrfToken,
            String csrfHeaderName
    ) {}
    public record AuthError(boolean ok, String code, String message) {
        AuthError(String code, String message) { this(false, code, message); }
    }
}
