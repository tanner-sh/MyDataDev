package com.example.dbadmin.repo;

import com.example.dbadmin.service.ai.AiConnectionPolicy;
import com.example.dbadmin.service.ai.AiEffort;
import com.example.dbadmin.service.ai.AiProvider;
import com.example.dbadmin.service.ai.AiSchemaSharing;
import com.example.dbadmin.service.ai.AiSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AiSettingsRepository {
    private final JdbcTemplate jdbc;

    public AiSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AiSettings> findSettings() {
        List<AiSettings> rows = jdbc.query("SELECT * FROM ai_settings WHERE id = 1", (rs, ignored) -> new AiSettings(
                rs.getBoolean("enabled"),
                AiProvider.parse(rs.getString("provider")),
                rs.getString("base_url"),
                rs.getString("model"),
                rs.getString("api_key_cipher"),
                AiEffort.parse(rs.getString("effort")),
                rs.getLong("daily_token_budget"),
                rs.getLong("user_daily_token_budget")
        ));
        return rows.stream().findFirst();
    }

    public void insertSettings(AiSettings settings) {
        jdbc.update("""
                INSERT INTO ai_settings(id, enabled, provider, base_url, model, api_key_cipher, effort,
                                        daily_token_budget, user_daily_token_budget)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                settings.enabled(), settings.provider().name(), settings.baseUrl(),
                settings.model(), settings.apiKeyCipher(), settings.effort().name(),
                settings.dailyTokenBudget(), settings.userDailyTokenBudget());
    }

    public void updateSettings(AiSettings settings) {
        jdbc.update("""
                UPDATE ai_settings SET
                    enabled = ?, provider = ?, base_url = ?, model = ?, api_key_cipher = ?,
                    effort = ?, daily_token_budget = ?, user_daily_token_budget = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = 1
                """,
                settings.enabled(), settings.provider().name(), settings.baseUrl(),
                settings.model(), settings.apiKeyCipher(), settings.effort().name(),
                settings.dailyTokenBudget(), settings.userDailyTokenBudget());
    }

    public List<AiConnectionPolicy> findPolicies() {
        return jdbc.query("SELECT * FROM ai_connection_policy", (rs, ignored) -> new AiConnectionPolicy(
                rs.getLong("connection_id"),
                AiSchemaSharing.parse(rs.getString("schema_sharing")),
                rs.getInt("sample_row_limit")
        ));
    }

    public Optional<AiConnectionPolicy> findPolicy(long connectionId) {
        List<AiConnectionPolicy> rows = jdbc.query(
                "SELECT * FROM ai_connection_policy WHERE connection_id = ?",
                (rs, ignored) -> new AiConnectionPolicy(
                        rs.getLong("connection_id"),
                        AiSchemaSharing.parse(rs.getString("schema_sharing")),
                        rs.getInt("sample_row_limit")
                ),
                connectionId);
        return rows.stream().findFirst();
    }

    /**
     * 写入策略。
     *
     * <p>H2 的 MERGE 在这里够用，且策略表只有主键一个唯一约束；先 UPDATE 后 INSERT 的写法
     * 在并发下会留下重复行。</p>
     */
    public void upsertPolicy(AiConnectionPolicy policy) {
        jdbc.update("""
                MERGE INTO ai_connection_policy(connection_id, schema_sharing, sample_row_limit, updated_at)
                KEY(connection_id) VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                policy.connectionId(), policy.sharing().name(), policy.sampleRowLimit());
    }
}
