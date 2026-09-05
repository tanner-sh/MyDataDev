package com.example.dbadmin.repo;

import com.example.dbadmin.service.ai.AiGlossaryGap;
import com.example.dbadmin.service.ai.AiGlossaryGaps;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
public class AiGlossaryGapRepository {
    private final JdbcTemplate jdbc;

    public AiGlossaryGapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AiGlossaryGap> findByConnectionId(long connectionId) {
        return jdbc.query("""
                SELECT term, hits, last_seen_at
                FROM ai_glossary_gap
                WHERE connection_id = ?
                ORDER BY hits DESC, last_seen_at DESC
                """, (rs, ignored) -> {
            Timestamp seen = rs.getTimestamp("last_seen_at");
            return new AiGlossaryGap(rs.getString("term"), rs.getInt("hits"),
                    seen == null ? null : seen.toInstant());
        }, connectionId);
    }

    /**
     * 记一批搜空的词，已有的累加次数。
     *
     * <p>先 UPDATE 再 INSERT 而不是反过来：同一个词反复搜不到是常态，命中已有行才是主路径。
     * 两个 Agent 请求同时记同一个词时，唯一约束会让后到的那条 INSERT 失败，这里退回 UPDATE
     * 再补一次 —— 计数偏差一次远好过让一次 AI 请求因为记录缺口而报错。</p>
     */
    @Transactional
    public void record(long connectionId, Collection<String> terms) {
        Set<String> handled = new LinkedHashSet<>();
        for (String term : terms) {
            String normalized = AiGlossaryGaps.normalize(term);
            if (normalized.isEmpty() || !handled.add(normalized)) continue;
            if (touch(connectionId, normalized) > 0) continue;
            try {
                jdbc.update("""
                        INSERT INTO ai_glossary_gap(connection_id, term, normalized_term)
                        VALUES (?, ?, ?)
                        """, connectionId, clamp(term), clamp(normalized));
            } catch (DuplicateKeyException concurrent) {
                touch(connectionId, normalized);
            }
        }
        prune(connectionId);
    }

    @Transactional
    public int delete(long connectionId, Collection<String> terms) {
        int deleted = 0;
        for (String term : terms) {
            String normalized = AiGlossaryGaps.normalize(term);
            if (normalized.isEmpty()) continue;
            deleted += jdbc.update("DELETE FROM ai_glossary_gap WHERE connection_id = ? AND normalized_term = ?",
                    connectionId, clamp(normalized));
        }
        return deleted;
    }

    private int touch(long connectionId, String normalized) {
        return jdbc.update("""
                UPDATE ai_glossary_gap
                SET hits = hits + 1, last_seen_at = CURRENT_TIMESTAMP
                WHERE connection_id = ? AND normalized_term = ?
                """, connectionId, clamp(normalized));
    }

    /** 只留最值得补的那些：先按被搜空的次数，再按最近一次出现。 */
    private void prune(long connectionId) {
        jdbc.update("""
                DELETE FROM ai_glossary_gap
                WHERE connection_id = ? AND id NOT IN (
                    SELECT id FROM ai_glossary_gap
                    WHERE connection_id = ?
                    ORDER BY hits DESC, last_seen_at DESC
                    LIMIT ?
                )
                """, connectionId, connectionId, AiGlossaryGaps.MAX_TERMS_PER_CONNECTION);
    }

    private static String clamp(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120);
    }
}
