package com.example.dbadmin.service.ai;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.repo.AiUsageRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiUsageServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);

    private final AiUsageRepository repository = mock(AiUsageRepository.class);
    private final AiUsageService service = new AiUsageService(repository, FIXED);

    /** 没设预算的部署不该为这个功能付出每次请求一次查询的代价。 */
    @Test
    void doesNotEvenQueryWhenNoBudgetIsConfigured() {
        service.requireWithinBudget("alice", settings(0, 0));

        verifyNoInteractions(repository);
    }

    @Test
    void allowsRequestsWhileTheGlobalBudgetStillHasRoom() {
        when(repository.consumed(TODAY)).thenReturn(9_999L);

        service.requireWithinBudget("alice", settings(10_000, 0));

        verify(repository, never()).consumed(any(), anyString());
    }

    @Test
    void refusesOnceTheGlobalBudgetIsUsedUp() {
        when(repository.consumed(TODAY)).thenReturn(10_000L);

        assertThatThrownBy(() -> service.requireWithinBudget("alice", settings(10_000, 0)))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code()).isEqualTo("AI_BUDGET_EXCEEDED"))
                .hasMessageContaining("全站");
    }

    /** 全站额度挡不住一个人把大家的额度用完，所以每人每日额度要单独卡。 */
    @Test
    void refusesTheOneUserWhoBurnedThroughTheirOwnShare() {
        when(repository.consumed(TODAY)).thenReturn(100L);
        when(repository.consumed(TODAY, "alice")).thenReturn(500L);

        assertThatThrownBy(() -> service.requireWithinBudget("alice", settings(10_000, 500)))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("你今日");
    }

    @Test
    void countsInputPlusOutputButNotCacheReadsAgainstTheBudget() {
        service.record("alice", "claude-opus-5", 800, 200, 700);

        verify(repository).record(TODAY, "alice", "claude-opus-5", 800, 200, 700);
    }

    /** 记账失败不该让一次已经拿到回答的请求变成失败。 */
    @Test
    void swallowsBookkeepingFailures() {
        org.mockito.Mockito.doThrow(new IllegalStateException("H2 挂了"))
                .when(repository).record(any(), anyString(), anyString(), anyLong(), anyLong(), anyLong());

        service.record("alice", "m", 1, 1, 0);
    }

    @Test
    void skipsEmptyUsageInsteadOfWritingZeroRows() {
        service.record("alice", "m", 0, 0, 0);

        verifyNoInteractions(repository);
    }

    @Test
    void reportsTodayAndTheCallersOwnShare() {
        when(repository.between(TODAY.minusDays(13), TODAY)).thenReturn(List.of(
                new AiUsageEntry(TODAY, "alice", "claude-opus-5", 3, 900, 100, 700),
                new AiUsageEntry(TODAY, "bob", "claude-opus-5", 1, 400, 100, 0),
                new AiUsageEntry(TODAY.minusDays(1), "alice", "claude-opus-5", 2, 200, 50, 0)));

        var report = service.report(14, "alice", settings(10_000, 2_000));

        assertThat(report.usedToday()).isEqualTo(1_500);
        assertThat(report.usedTodayByCaller()).isEqualTo(1_000);
        assertThat(report.dailyTokenBudget()).isEqualTo(10_000);
        assertThat(report.daily()).hasSize(2);
        assertThat(report.daily().get(0).day()).isEqualTo(TODAY.toString());
        assertThat(report.actors()).first()
                .satisfies(actor -> assertThat(actor.actor()).isEqualTo("alice"));
        // 保留期裁剪跟着面板走，不单开定时任务。
        verify(repository).purgeExpired(TODAY);
    }

    private static AiSettings settings(long daily, long perUser) {
        return new AiSettings(true, AiProvider.ANTHROPIC, null, "claude-opus-5", "cipher",
                AiEffort.HIGH, daily, perUser);
    }
}
