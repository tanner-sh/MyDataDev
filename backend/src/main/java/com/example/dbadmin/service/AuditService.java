package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.AuditEventPage;
import com.example.dbadmin.dto.ApiDtos.AuditFacets;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.AuditRepository.AuditQuery;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 审计记录的读取入口。
 *
 * <p>审计写入分散在十多个服务里，读取只有这一处，所以把参数归一化和边界都收在这里：
 * 页大小上限、时间区间解析、空串一律当成「未过滤」。</p>
 */
@Service
public class AuditService {
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    public AuditEventPage list(
            String actor,
            String action,
            Long connectionId,
            String keyword,
            String from,
            String to,
            Integer page,
            Integer pageSize
    ) {
        return repository.findPage(new AuditQuery(
                trimToNull(actor),
                trimToNull(action),
                connectionId,
                trimToNull(keyword),
                parseInstant(from, "起始时间"),
                parseInstant(to, "结束时间"),
                Math.max(page == null ? 0 : page, 0),
                Math.min(Math.max(pageSize == null ? DEFAULT_PAGE_SIZE : pageSize, 1), MAX_PAGE_SIZE)
        ));
    }

    public AuditFacets facets() {
        return repository.facets();
    }

    public String detail(long id) {
        return Optional.ofNullable(repository.detail(id).orElse(null))
                .orElseThrow(() -> new IllegalArgumentException("未找到审计记录：" + id));
    }

    private static Instant parseInstant(String value, String label) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(label + "格式无效，请使用 ISO-8601（例如 2026-08-22T00:00:00Z）。");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
