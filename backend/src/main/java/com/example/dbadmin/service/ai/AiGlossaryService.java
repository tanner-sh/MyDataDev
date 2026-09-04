package com.example.dbadmin.service.ai;

import com.example.dbadmin.dto.AiDtos.AiGlossaryEntryRequest;
import com.example.dbadmin.dto.AiDtos.AiGlossaryEntryResponse;
import com.example.dbadmin.dto.AiDtos.AiGlossarySuggestionResponse;
import com.example.dbadmin.dto.AiDtos.AiGlossarySuggestionsResponse;
import com.example.dbadmin.dto.AiDtos.AiGlossaryUpdateRequest;
import com.example.dbadmin.repo.AiGlossaryRepository;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.ConnectionService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AiGlossaryService {
    /** 一次最多给多少条候选。再多就不是「审阅」而是另一份要通读的清单了。 */
    public static final int MAX_SUGGESTIONS = 50;

    private final AiGlossaryRepository repository;
    private final ConnectionService connections;
    private final AuditRepository audit;
    private final AiSchemaTools tools;
    private final AiQueryHistoryService queryHistory;
    /** Agent 一轮里 search_schema 会被调好几次，词典每次都回源等于白打 H2。 */
    private final Cache<Long, List<AiBusinessTerm>> cached = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public AiGlossaryService(
            AiGlossaryRepository repository,
            ConnectionService connections,
            AuditRepository audit,
            @org.springframework.context.annotation.Lazy AiSchemaTools tools,
            AiQueryHistoryService queryHistory
    ) {
        this.repository = repository;
        this.connections = connections;
        this.audit = audit;
        // AiSchemaTools 也依赖本服务（search_schema 要读词典），@Lazy 打断这个环。
        this.tools = tools;
        this.queryHistory = queryHistory;
    }

    /**
     * 从表注释推出候选词条，让管理员从「对着空表格填一百条」变成「审阅和补别名」。
     *
     * <p>能做到哪一步要说清楚：注释里的词本身就能被 search_schema 搜到，所以自动生成的词条
     * 不是新信息。真正不可替代的是用户嘴里的「会员」「买家」—— 那些不会出现在任何注释里，
     * 只能由人补。这里做的是把剩下那部分工作变便宜。</p>
     */
    public AiGlossarySuggestionsResponse suggest(long connectionId, String schemaName, int limit) throws Exception {
        connections.require(connectionId);
        Set<String> existing = new LinkedHashSet<>();
        for (AiBusinessTerm term : terms(connectionId)) {
            existing.add(term.term());
            existing.addAll(term.aliases());
        }
        AiGlossarySuggestions.Result result = AiGlossarySuggestions.suggest(
                tools.objects(connectionId, schemaName), existing,
                queryHistory.tableUsage(connectionId), Math.min(Math.max(limit, 1), MAX_SUGGESTIONS));
        return new AiGlossarySuggestionsResponse(
                result.suggestions().stream()
                        .map(item -> new AiGlossarySuggestionResponse(item.term(), item.aliases(),
                                item.objectNames(), item.description(), item.usageCount()))
                        .toList(),
                result.uncommented());
    }

    public List<AiGlossaryEntryResponse> list(long connectionId) {
        connections.require(connectionId);
        return repository.findByConnectionId(connectionId).stream().map(AiGlossaryService::response).toList();
    }

    public List<AiGlossaryEntryResponse> replace(long connectionId, AiGlossaryUpdateRequest request, String actor) {
        connections.require(connectionId);
        List<AiBusinessTerm> entries = normalize(connectionId, request.entries());
        repository.replace(connectionId, entries);
        cached.invalidate(connectionId);
        audit.onConnection(actor, "AI_GLOSSARY_UPDATE", connectionId, "entries=" + entries.size());
        return list(connectionId);
    }

    public List<AiBusinessTerm> terms(long connectionId) {
        return cached.get(connectionId, repository::findByConnectionId);
    }

    private static List<AiBusinessTerm> normalize(long connectionId, List<AiGlossaryEntryRequest> input) {
        List<AiBusinessTerm> result = new ArrayList<>();
        Set<String> terms = new LinkedHashSet<>();
        for (AiGlossaryEntryRequest entry : input) {
            String term = entry.term().trim();
            if (!terms.add(term.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("业务词不能重复：" + term);
            }
            result.add(new AiBusinessTerm(0, connectionId, term,
                    clean(entry.aliases(), 120), clean(entry.objectNames(), 200), cleanText(entry.description())));
        }
        return List.copyOf(result);
    }

    private static List<String> clean(List<String> values, int maxLength) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String cleaned = value == null ? "" : value.trim();
            if (!cleaned.isBlank()) result.add(cleaned.substring(0, Math.min(cleaned.length(), maxLength)));
        }
        return List.copyOf(result);
    }

    private static String cleanText(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.trim();
        return cleaned.substring(0, Math.min(cleaned.length(), 1_000));
    }

    private static AiGlossaryEntryResponse response(AiBusinessTerm entry) {
        return new AiGlossaryEntryResponse(entry.id(), entry.term(), entry.aliases(), entry.objectNames(), entry.description());
    }
}
