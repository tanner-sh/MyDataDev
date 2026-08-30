package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.AuditEventPage;
import com.example.dbadmin.dto.ApiDtos.AuditEventResponse;
import com.example.dbadmin.dto.ApiDtos.AuditFacets;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.AuditRepository.AuditQuery;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    public byte[] exportCsv(
            String actor,
            String action,
            Long connectionId,
            String keyword,
            String from,
            String to,
            String exportActor
    ) {
        List<AuditEventResponse> events = new ArrayList<>();
        int page = 0;
        while (events.size() < 10_000) {
            AuditEventPage batch = list(actor, action, connectionId, keyword, from, to, page, MAX_PAGE_SIZE);
            events.addAll(batch.items());
            if (!batch.hasMore()) break;
            page++;
        }
        StringBuilder csv = new StringBuilder("\uFEFFid,createdAt,actor,action,target,detail,remoteAddress,forwardedFor,userAgent,requestId\r\n");
        for (AuditEventResponse event : events) {
            csv.append(event.id()).append(',')
                    .append(csv(event.createdAt())).append(',')
                    .append(csv(event.actor())).append(',')
                    .append(csv(event.action())).append(',')
                    .append(csv(event.target())).append(',')
                    .append(csv(event.detail())).append(',')
                    .append(csv(event.remoteAddress())).append(',')
                    .append(csv(event.forwardedFor())).append(',')
                    .append(csv(event.userAgent())).append(',')
                    .append(csv(event.requestId())).append("\r\n");
        }
        repository.global(exportActor, "AUDIT_EXPORT", "audit",
                "rows=" + events.size() + ", capped=" + (events.size() >= 10_000));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
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

    private static String csv(String value) {
        if (value == null) return "";
        String safe = value;
        String leadingTrimmed = safe.stripLeading();
        if (!leadingTrimmed.isEmpty() && "=+-@".indexOf(leadingTrimmed.charAt(0)) >= 0) safe = "'" + safe;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
