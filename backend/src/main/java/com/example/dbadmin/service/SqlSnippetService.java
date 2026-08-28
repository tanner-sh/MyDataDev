package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.ApiDtos.SqlSnippetRequest;
import com.example.dbadmin.dto.ApiDtos.SqlSnippetResponse;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlSnippetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 保存的 SQL 片段。
 *
 * <p>执行历史有保留期且会被定期清理，反复使用的对账查询、排查脚本、清理语句需要一个长期
 * 归宿；此前唯一的「模板」是前端硬编码的一条 SELECT 骨架。</p>
 */
@Service
public class SqlSnippetService {
    static final int MAX_SNIPPETS = 500;

    private final SqlSnippetRepository repository;
    private final AuditRepository audit;

    public SqlSnippetService(SqlSnippetRepository repository, AuditRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public List<SqlSnippetResponse> list(String keyword, String dbType) {
        return repository.findAll(keyword, dbType);
    }

    public SqlSnippetResponse create(SqlSnippetRequest request, String actor) {
        String name = requireName(request.name(), null);
        if (repository.findAll(null, null).size() >= MAX_SNIPPETS) {
            throw new IllegalStateException("最多保存 " + MAX_SNIPPETS + " 个 SQL 片段，请先清理不再使用的片段。");
        }
        long id = repository.insert(
                name, trimToNull(request.description()), request.sql(),
                normalizeDbType(request.dbType()), trimToNull(request.tags()), actor
        );
        audit.global(actor, "SQL_SNIPPET_CREATE", "snippet:" + id, name);
        return require(id);
    }

    public SqlSnippetResponse update(long id, SqlSnippetRequest request, String actor) {
        require(id);
        String name = requireName(request.name(), id);
        repository.update(
                id, name, trimToNull(request.description()), request.sql(),
                normalizeDbType(request.dbType()), trimToNull(request.tags())
        );
        audit.global(actor, "SQL_SNIPPET_UPDATE", "snippet:" + id, name);
        return require(id);
    }

    public void delete(long id, String actor) {
        SqlSnippetResponse snippet = require(id);
        repository.delete(id);
        audit.global(actor, "SQL_SNIPPET_DELETE", "snippet:" + id, snippet.name());
    }

    /** 插入到编辑器时调用，让常用片段自然排到前面。 */
    public SqlSnippetResponse recordUse(long id) {
        require(id);
        repository.recordUse(id);
        return require(id);
    }

    private SqlSnippetResponse require(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到 SQL 片段：" + id));
    }

    private String requireName(String value, Long excludingId) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("片段名称不能为空。");
        if (repository.nameTaken(name, excludingId)) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT, "SNIPPET_NAME_TAKEN", "已存在同名片段：" + name
            );
        }
        return name;
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
}
