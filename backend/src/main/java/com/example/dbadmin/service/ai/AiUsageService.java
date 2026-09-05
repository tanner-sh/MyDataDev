package com.example.dbadmin.service.ai;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.AiDtos.AiUsageActorResponse;
import com.example.dbadmin.dto.AiDtos.AiUsageDayResponse;
import com.example.dbadmin.dto.AiDtos.AiUsageResponse;
import com.example.dbadmin.repo.AiUsageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * token 用量的记账与预算闸门。
 *
 * <p>额度按**服务器本地自然日**计，跨零点清零。这条要说清楚：模型的账单周期不是这个，
 * 所以这里卡的是「今天别失控」，不是对账。</p>
 *
 * <p><b>闸门只在请求开始前看已经花掉的数</b>，因为一次请求要花多少 token 得等它跑完才知道。
 * 也就是说，最后那一次请求可以把额度冲过头 —— 上限是「超了就不再开新的」，不是硬性截断。
 * 想要严格不超，唯一的办法是预扣估算值，而估算一条 Agent 请求的 token 本身就不可靠，
 * 结果只会是把额度提前用掉。</p>
 *
 * <p>不依赖 {@link AiSettingsService}：预算值由调用方带进来，否则两者互相注入成环。</p>
 */
@Service
public class AiUsageService {
    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);
    /** 面板一次最多回看多少天。 */
    public static final int MAX_DAYS = 90;

    private final AiUsageRepository repository;
    private final Clock clock;

    @Autowired
    public AiUsageService(AiUsageRepository repository) {
        this(repository, Clock.systemDefaultZone());
    }

    /** 测试用：把「今天」钉死，跨零点的行为才测得了。 */
    AiUsageService(AiUsageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 预算闸门。两道额度都是 0 时直接放行，连库都不查 —— 没设预算的部署不该为这个功能付出
     * 每次请求一次查询的代价。
     */
    public void requireWithinBudget(String actor, AiSettings settings) {
        long total = settings.dailyTokenBudget();
        long perUser = settings.userDailyTokenBudget();
        if (total <= 0 && perUser <= 0) return;
        LocalDate today = LocalDate.now(clock);
        if (total > 0) {
            long used = repository.consumed(today);
            if (used >= total) throw exceeded("全站今日 AI token 额度已用完", used, total, actor);
        }
        if (perUser > 0 && actor != null && !actor.isBlank()) {
            long used = repository.consumed(today, actor);
            if (used >= perUser) throw exceeded("你今日的 AI token 额度已用完", used, perUser, actor);
        }
    }

    /**
     * 记一次调用的用量。
     *
     * <p>整段吞异常：记账失败不该让一次已经拿到回答的请求变成失败。代价是这种情况下额度会
     * 少算一点，比把回答吞掉划算。</p>
     */
    public void record(String actor, String model, long inputTokens, long outputTokens, long cacheReadTokens) {
        if (inputTokens <= 0 && outputTokens <= 0 && cacheReadTokens <= 0) return;
        try {
            repository.record(LocalDate.now(clock), actor, model, inputTokens, outputTokens, cacheReadTokens);
        } catch (RuntimeException e) {
            log.debug("记录 AI 用量失败：{}", e.toString());
        }
    }

    public AiUsageResponse report(int days, String actor, AiSettings settings) {
        int window = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDate today = LocalDate.now(clock);
        // 保留期裁剪挂在这儿：管理员打开面板本来就不频繁，不值得为它单开一个定时任务。
        repository.purgeExpired(today);
        List<AiUsageEntry> entries = repository.between(today.minusDays(window - 1L), today);
        AiUsageSummary.Result summary = AiUsageSummary.summarize(entries, today, actor);
        return new AiUsageResponse(
                settings.dailyTokenBudget(),
                settings.userDailyTokenBudget(),
                summary.usedToday(),
                summary.usedTodayByCaller(),
                window,
                summary.days().stream()
                        .map(day -> new AiUsageDayResponse(day.day().toString(), day.requests(),
                                day.inputTokens(), day.outputTokens(), day.cacheReadTokens()))
                        .toList(),
                summary.actors().stream()
                        .map(item -> new AiUsageActorResponse(item.actor(), item.requests(), item.tokens()))
                        .toList());
    }

    private ApiProblemException exceeded(String message, long used, long limit, String actor) {
        return new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUDGET_EXCEEDED",
                message + "（已用 " + used + " / " + limit + " token，明天零点重置）。如需继续，请联系管理员调整预算。",
                Map.of("used", used, "limit", limit, "actor", actor == null ? "" : actor));
    }
}
