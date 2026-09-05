package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiUsageSummaryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);

    @Test
    void mergesEveryModelOfTheSameDayIntoOneRow() {
        var result = AiUsageSummary.summarize(List.of(
                new AiUsageEntry(TODAY, "alice", "claude-opus-5", 2, 500, 100, 400),
                new AiUsageEntry(TODAY, "alice", "deepseek-v4", 1, 300, 50, 0)), TODAY, "alice");

        assertThat(result.days()).singleElement().satisfies(day -> {
            assertThat(day.requests()).isEqualTo(3);
            assertThat(day.inputTokens()).isEqualTo(800);
            assertThat(day.cacheReadTokens()).isEqualTo(400);
        });
        assertThat(result.usedToday()).isEqualTo(950);
    }

    @Test
    void sortsDaysNewestFirstAndActorsByConsumption() {
        var result = AiUsageSummary.summarize(List.of(
                new AiUsageEntry(TODAY.minusDays(2), "bob", "m", 1, 10, 5, 0),
                new AiUsageEntry(TODAY, "alice", "m", 1, 900, 100, 0),
                new AiUsageEntry(TODAY.minusDays(1), "bob", "m", 5, 5_000, 500, 0)), TODAY, "alice");

        assertThat(result.days()).extracting(AiUsageSummary.DayUsage::day)
                .containsExactly(TODAY, TODAY.minusDays(1), TODAY.minusDays(2));
        assertThat(result.actors()).extracting(AiUsageSummary.ActorUsage::actor).containsExactly("bob", "alice");
    }

    /** 面板要能回答「我自己还剩多少」，而全站用量回答不了这个。 */
    @Test
    void separatesTheCallersOwnConsumptionFromEveryoneElses() {
        var result = AiUsageSummary.summarize(List.of(
                new AiUsageEntry(TODAY, "alice", "m", 1, 100, 10, 0),
                new AiUsageEntry(TODAY, "bob", "m", 1, 700, 90, 0)), TODAY, "alice");

        assertThat(result.usedToday()).isEqualTo(900);
        assertThat(result.usedTodayByCaller()).isEqualTo(110);
    }

    @Test
    void keepsOnlyTheTopActorsBecauseUsageIsALongTail() {
        List<AiUsageEntry> entries = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            entries.add(new AiUsageEntry(TODAY, "user" + index, "m", 1, index * 10L, 0, 0));
        }

        assertThat(AiUsageSummary.summarize(entries, TODAY, "user1").actors())
                .hasSize(AiUsageSummary.MAX_ACTORS);
    }

    @Test
    void handlesAnEmptyWindowWithoutInventingRows() {
        var result = AiUsageSummary.summarize(List.of(), TODAY, "alice");

        assertThat(result.days()).isEmpty();
        assertThat(result.actors()).isEmpty();
        assertThat(result.usedToday()).isZero();
    }
}
