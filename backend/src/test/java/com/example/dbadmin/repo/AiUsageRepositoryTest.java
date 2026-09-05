package com.example.dbadmin.repo;

import com.example.dbadmin.service.ai.AiUsageEntry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AiUsageRepositoryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);

    @Test
    void accumulatesRepeatedCallsIntoOneRowPerDayActorAndModel() {
        AiUsageRepository repository = repository("ai-usage-accumulate");

        repository.record(TODAY, "alice", "claude-opus-5", 500, 100, 400);
        repository.record(TODAY, "alice", "claude-opus-5", 300, 50, 200);

        assertThat(repository.between(TODAY, TODAY)).singleElement().satisfies(entry -> {
            assertThat(entry.requests()).isEqualTo(2);
            assertThat(entry.inputTokens()).isEqualTo(800);
            assertThat(entry.outputTokens()).isEqualTo(150);
            assertThat(entry.cacheReadTokens()).isEqualTo(600);
        });
        assertThat(repository.consumed(TODAY)).isEqualTo(950);
    }

    /** 换个模型是另一行：换模型往往正是「为什么今天贵了」的答案。 */
    @Test
    void keepsModelsApartOnTheSameDay() {
        AiUsageRepository repository = repository("ai-usage-models");

        repository.record(TODAY, "alice", "claude-opus-5", 100, 10, 0);
        repository.record(TODAY, "alice", "deepseek-v4", 200, 20, 0);

        assertThat(repository.between(TODAY, TODAY)).hasSize(2);
        assertThat(repository.consumed(TODAY, "alice")).isEqualTo(330);
    }

    @Test
    void countsOnlyTheAskedForDayAndActor() {
        AiUsageRepository repository = repository("ai-usage-scope");

        repository.record(TODAY, "alice", "m", 100, 0, 0);
        repository.record(TODAY, "bob", "m", 700, 0, 0);
        repository.record(TODAY.minusDays(1), "alice", "m", 9_000, 0, 0);

        assertThat(repository.consumed(TODAY)).isEqualTo(800);
        assertThat(repository.consumed(TODAY, "alice")).isEqualTo(100);
        assertThat(repository.consumed(TODAY.plusDays(1))).isZero();
    }

    @Test
    void ordersTheWindowNewestFirst() {
        AiUsageRepository repository = repository("ai-usage-window");

        repository.record(TODAY.minusDays(3), "alice", "m", 10, 0, 0);
        repository.record(TODAY, "alice", "m", 20, 0, 0);

        assertThat(repository.between(TODAY.minusDays(6), TODAY))
                .extracting(AiUsageEntry::day)
                .containsExactly(TODAY, TODAY.minusDays(3));
    }

    @Test
    void dropsRowsOlderThanTheRetentionWindow() {
        AiUsageRepository repository = repository("ai-usage-retention");

        repository.record(TODAY.minusDays(500), "alice", "m", 10, 0, 0);
        repository.record(TODAY, "alice", "m", 10, 0, 0);

        assertThat(repository.purgeExpired(TODAY)).isEqualTo(1);
        assertThat(repository.between(TODAY.minusDays(600), TODAY)).hasSize(1);
    }

    /** 用户名或模型名超长时截断入库，而不是让一次记账把整条请求带崩。 */
    @Test
    void clampsOverlongActorAndModelNames() {
        AiUsageRepository repository = repository("ai-usage-clamp");

        repository.record(TODAY, "a".repeat(300), "m".repeat(300), 10, 1, 0);

        assertThat(repository.between(TODAY, TODAY)).singleElement().satisfies(entry -> {
            assertThat(entry.actor()).hasSize(190);
            assertThat(entry.model()).hasSize(128);
        });
    }

    private static AiUsageRepository repository(String name) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_usage_daily(
                    usage_date DATE NOT NULL,
                    actor VARCHAR(190) NOT NULL,
                    model VARCHAR(128) NOT NULL,
                    requests INT NOT NULL DEFAULT 0,
                    input_tokens BIGINT NOT NULL DEFAULT 0,
                    output_tokens BIGINT NOT NULL DEFAULT 0,
                    cache_read_tokens BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (usage_date, actor, model)
                )
                """);
        return new AiUsageRepository(jdbc);
    }
}
