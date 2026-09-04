package com.example.dbadmin.service.ai;

import com.example.dbadmin.dto.AiDtos.AiGlossaryEntryRequest;
import com.example.dbadmin.dto.AiDtos.AiGlossaryEntryResponse;
import com.example.dbadmin.dto.AiDtos.AiGlossaryUpdateRequest;
import com.example.dbadmin.repo.AiGlossaryRepository;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.ConnectionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AiGlossaryService {
    private final AiGlossaryRepository repository;
    private final ConnectionService connections;
    private final AuditRepository audit;

    public AiGlossaryService(AiGlossaryRepository repository, ConnectionService connections, AuditRepository audit) {
        this.repository = repository;
        this.connections = connections;
        this.audit = audit;
    }

    public List<AiGlossaryEntryResponse> list(long connectionId) {
        connections.require(connectionId);
        return repository.findByConnectionId(connectionId).stream().map(AiGlossaryService::response).toList();
    }

    public List<AiGlossaryEntryResponse> replace(long connectionId, AiGlossaryUpdateRequest request, String actor) {
        connections.require(connectionId);
        List<AiBusinessTerm> entries = normalize(connectionId, request.entries());
        repository.replace(connectionId, entries);
        audit.onConnection(actor, "AI_GLOSSARY_UPDATE", connectionId, "entries=" + entries.size());
        return list(connectionId);
    }

    public List<AiBusinessTerm> terms(long connectionId) {
        return repository.findByConnectionId(connectionId);
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
