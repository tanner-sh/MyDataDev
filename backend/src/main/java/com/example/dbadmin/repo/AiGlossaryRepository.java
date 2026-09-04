package com.example.dbadmin.repo;

import com.example.dbadmin.service.ai.AiBusinessTerm;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class AiGlossaryRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AiGlossaryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<AiBusinessTerm> findByConnectionId(long connectionId) {
        return jdbc.query("""
                SELECT id, connection_id, term, aliases, object_names, description
                FROM ai_business_glossary
                WHERE connection_id = ?
                ORDER BY term, id
                """, (rs, ignored) -> new AiBusinessTerm(
                rs.getLong("id"),
                rs.getLong("connection_id"),
                rs.getString("term"),
                strings(rs.getString("aliases")),
                strings(rs.getString("object_names")),
                rs.getString("description")
        ), connectionId);
    }

    @Transactional
    public void replace(long connectionId, List<AiBusinessTerm> entries) {
        jdbc.update("DELETE FROM ai_business_glossary WHERE connection_id = ?", connectionId);
        for (AiBusinessTerm entry : entries) {
            jdbc.update("""
                    INSERT INTO ai_business_glossary(connection_id, term, aliases, object_names, description)
                    VALUES (?, ?, ?, ?, ?)
                    """, connectionId, entry.term(), json(entry.aliases()), json(entry.objectNames()), entry.description());
        }
    }

    private String json(List<String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("业务词典序列化失败", e);
        }
    }

    private List<String> strings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, STRINGS);
        } catch (Exception e) {
            throw new IllegalStateException("业务词典数据损坏", e);
        }
    }
}
