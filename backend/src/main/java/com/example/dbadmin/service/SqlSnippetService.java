package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.dto.ApiDtos.SqlSnippetRequest;
import com.example.dbadmin.dto.ApiDtos.SqlSnippetResponse;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlSnippetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class SqlSnippetService {
    static final int MAX_SNIPPETS = 500;
    private final SqlSnippetRepository repository;
    private final AuditRepository audit;

    public SqlSnippetService(SqlSnippetRepository repository, AuditRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public List<SqlSnippetResponse> list(String keyword, String dbType, WebIdentity identity) {
        return repository.findAll(keyword, dbType, userId(identity), administrator(identity));
    }

    /** 兼容桌面模式和聚焦单测：没有 Web 身份时片段仍是共享资产。 */
    public List<SqlSnippetResponse> list(String keyword, String dbType) { return list(keyword, dbType, null); }

    public SqlSnippetResponse create(SqlSnippetRequest request, WebIdentity identity, String fallbackActor) {
        String visibility = normalizeVisibility(request.visibility(), identity);
        Long ownerUserId = identity == null ? null : identity.userId();
        String name = requireName(request.name(), null, visibility, ownerUserId);
        if (repository.countVisible(userId(identity), administrator(identity)) >= MAX_SNIPPETS) {
            throw new IllegalStateException("最多保存 " + MAX_SNIPPETS + " 个 SQL 片段，请先清理不再使用的片段。");
        }
        String actor = actor(identity, fallbackActor);
        long id = repository.insert(name, trimToNull(request.description()), request.sql(),
                normalizeDbType(request.dbType()), trimToNull(request.tags()), actor, visibility, ownerUserId);
        audit.global(actor, "SQL_SNIPPET_CREATE", "snippet:" + id, name + ", visibility=" + visibility);
        return requireVisible(id, identity);
    }

    public SqlSnippetResponse create(SqlSnippetRequest request, String actor) {
        return create(request, null, actor);
    }

    public SqlSnippetResponse update(long id, SqlSnippetRequest request, WebIdentity identity, String fallbackActor) {
        SqlSnippetResponse existing = requireAny(id, identity);
        requireEditable(existing, identity);
        String visibility = normalizeVisibility(request.visibility() == null ? existing.visibility() : request.visibility(), identity);
        Long ownerUserId = existing.ownerUserId();
        if (ownerUserId == null && "PERSONAL".equals(visibility)) ownerUserId = requireUser(identity);
        String name = requireName(request.name(), id, visibility, ownerUserId);
        repository.update(id, name, trimToNull(request.description()), request.sql(),
                normalizeDbType(request.dbType()), trimToNull(request.tags()), visibility, ownerUserId);
        String actor = actor(identity, fallbackActor);
        audit.global(actor, "SQL_SNIPPET_UPDATE", "snippet:" + id, name + ", visibility=" + visibility);
        return requireVisible(id, identity);
    }

    public SqlSnippetResponse update(long id, SqlSnippetRequest request, String actor) {
        return update(id, request, null, actor);
    }

    public void delete(long id, WebIdentity identity, String fallbackActor) {
        SqlSnippetResponse snippet = requireAny(id, identity);
        requireEditable(snippet, identity);
        repository.delete(id);
        audit.global(actor(identity, fallbackActor), "SQL_SNIPPET_DELETE", "snippet:" + id, snippet.name());
    }

    public void delete(long id, String actor) { delete(id, null, actor); }

    public SqlSnippetResponse recordUse(long id, WebIdentity identity) {
        requireVisible(id, identity);
        repository.recordUse(id);
        return requireVisible(id, identity);
    }

    public SqlSnippetResponse recordUse(long id) { return recordUse(id, null); }

    private SqlSnippetResponse requireVisible(long id, WebIdentity identity) {
        return repository.findById(id, userId(identity), administrator(identity))
                .orElseThrow(() -> new IllegalArgumentException("未找到 SQL 片段：" + id));
    }

    private SqlSnippetResponse requireAny(long id, WebIdentity identity) {
        SqlSnippetResponse result = repository.findAnyById(id, userId(identity), administrator(identity))
                .orElseThrow(() -> new IllegalArgumentException("未找到 SQL 片段：" + id));
        if (!administrator(identity) && "PERSONAL".equals(result.visibility())
                && (identity == null || !Long.valueOf(identity.userId()).equals(result.ownerUserId()))) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "SNIPPET_ACCESS_DENIED", "只能管理自己创建的 SQL 片段。");
        }
        return result;
    }

    private void requireEditable(SqlSnippetResponse snippet, WebIdentity identity) {
        if (identity == null && "SHARED".equals(snippet.visibility())) return;
        if (!snippet.editable()) throw new ApiProblemException(HttpStatus.FORBIDDEN, "SNIPPET_ACCESS_DENIED", "只能管理自己创建的 SQL 片段。");
    }

    private String requireName(String value, Long excludingId, String visibility, Long ownerUserId) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("片段名称不能为空。");
        if (repository.nameTaken(name, excludingId, visibility, ownerUserId)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "SNIPPET_NAME_TAKEN", "当前范围已存在同名片段：" + name);
        }
        return name;
    }

    private static String normalizeVisibility(String value, WebIdentity identity) {
        String normalized = value == null || value.isBlank() ? (identity == null ? "SHARED" : "PERSONAL")
                : value.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("PERSONAL", "SHARED").contains(normalized)) throw new IllegalArgumentException("片段范围无效");
        if (identity == null && "PERSONAL".equals(normalized)) return "SHARED";
        return normalized;
    }

    private static String normalizeDbType(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long userId(WebIdentity identity) { return identity == null ? null : identity.userId(); }
    private static long requireUser(WebIdentity identity) {
        if (identity == null) throw new IllegalArgumentException("个人片段需要登录用户");
        return identity.userId();
    }
    private static boolean administrator(WebIdentity identity) { return identity != null && "ADMIN".equals(identity.role()); }
    private static String actor(WebIdentity identity, String fallback) { return identity == null ? fallback : identity.username(); }
}
