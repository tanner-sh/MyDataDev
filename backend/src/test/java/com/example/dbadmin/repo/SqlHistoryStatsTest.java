package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.SqlHistoryGroup;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryStats;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 历史的统计视角。
 *
 * <p>耗时、状态、执行人本来就都记着，只是从来没被聚合过 ——「哪条最慢」「哪条一直在失败」
 * 此前只能靠人一页页翻列表。</p>
 */
class SqlHistoryStatsTest {
    @Test
    void summarizesTotalsFailuresAndDurations() {
        SqlHistoryRepository repository = repository(history -> {
            history.record(1, "select 1", "SUCCESS", 10, "alice", 1L, 1);
            history.record(1, "select 2", "SUCCESS", 500, "alice", 1L, 1);
            history.record(1, "select bad", "FAILED", 5, "bob", 2L, 1);
        });

        SqlHistoryStats stats = repository.stats(1, 7, null, 10);

        assertThat(stats.days()).isEqualTo(7);
        assertThat(stats.summary().total()).isEqualTo(3);
        assertThat(stats.summary().failed()).isEqualTo(1);
        assertThat(stats.summary().slowestMs()).isEqualTo(500);
        assertThat(stats.summary().averageMs()).isEqualTo(172);
    }

    /** 最慢排行不该被失败的语句占满：跑挂的语句耗时短得毫无意义。 */
    @Test
    void ranksSlowestSuccessfulQueriesOnly() {
        SqlHistoryRepository repository = repository(history -> {
            history.record(1, "select fast", "SUCCESS", 5, "alice", 1L, 1);
            history.record(1, "select slow", "SUCCESS", 900, "alice", 1L, 1);
            history.record(1, "select broken", "FAILED", 9_000, "alice", 1L, 1);
        });

        assertThat(repository.stats(1, 7, null, 10).slowest())
                .extracting(SqlHistoryResponse::sql)
                .containsExactly("select slow", "select fast");
    }

    @Test
    void groupsFailuresByStatementAndCountsThem() {
        SqlHistoryRepository repository = repository(history -> {
            history.record(1, "select missing_col", "FAILED", 3, "alice", 1L, 1);
            history.record(1, "select missing_col", "FAILED", 4, "bob", 2L, 1);
            history.record(1, "select other", "FAILED", 3, "bob", 2L, 1);
        });

        assertThat(repository.stats(1, 7, null, 10).failures())
                .extracting(SqlHistoryGroup::text, SqlHistoryGroup::hits)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("select missing_col", 2),
                        org.assertj.core.groups.Tuple.tuple("select other", 1));
    }

    @Test
    void ranksBusiestActors() {
        SqlHistoryRepository repository = repository(history -> {
            history.record(1, "select 1", "SUCCESS", 1, "alice", 1L, 1);
            history.record(1, "select 2", "SUCCESS", 1, "alice", 1L, 1);
            history.record(1, "select 3", "SUCCESS", 1, "bob", 2L, 1);
        });

        assertThat(repository.stats(1, 7, null, 10).busiest())
                .extracting(SqlHistoryGroup::text, SqlHistoryGroup::hits)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("alice", 2),
                        org.assertj.core.groups.Tuple.tuple("bob", 1));
    }

    /** 窗口之外的记录不参与统计，否则「最近 7 天」这个说法就是假的。 */
    @Test
    void ignoresRowsOutsideTheWindowAndOtherConnections() {
        SqlHistoryRepository repository = repository(history -> {
            history.record(1, "select recent", "SUCCESS", 1, "alice", 1L, 1);
            history.record(1, "select old", "SUCCESS", 1, "alice", 1L, 30);
            history.record(2, "select elsewhere", "SUCCESS", 1, "alice", 1L, 1);
        });

        assertThat(repository.stats(1, 7, null, 10).summary().total()).isEqualTo(1);
    }

    /** 只看自己的那一档：范围判定必须和历史列表一致，统计不该松一档。 */
    @Test
    void narrowsToOneActorWhenAsked() {
        SqlHistoryRepository repository = repository(history -> {
            history.record(1, "select mine", "SUCCESS", 1, "alice", 1L, 1);
            history.record(1, "select theirs", "SUCCESS", 1, "bob", 2L, 1);
        });

        assertThat(repository.stats(1, 7, 1L, 10).summary().total()).isEqualTo(1);
    }

    private interface Seeder {
        void seed(Fixture fixture);
    }

    private record Fixture(JdbcTemplate jdbc) {
        void record(long connectionId, String sql, String status, long elapsed, String actor, Long actorUserId, int daysAgo) {
            jdbc.update("INSERT INTO sql_history(connection_id, sql_text, sql_type, status, elapsed_ms, actor, actor_user_id, created_at)"
                            + " VALUES (?, ?, 'EXECUTE', ?, ?, ?, ?, ?)",
                    connectionId, sql, status, elapsed, actor, actorUserId,
                    Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo)).plus(Duration.ofMinutes(1))));
        }
    }

    private static SqlHistoryRepository repository(Seeder seeder) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:sql-history-stats-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE sql_history(
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    connection_id BIGINT NOT NULL,
                    sql_text CLOB NOT NULL,
                    sql_type VARCHAR(40) NOT NULL,
                    status VARCHAR(40) NOT NULL,
                    elapsed_ms BIGINT DEFAULT 0,
                    error_message CLOB,
                    actor VARCHAR(120),
                    actor_user_id BIGINT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        seeder.seed(new Fixture(jdbc));
        return new SqlHistoryRepository(jdbc);
    }
}
