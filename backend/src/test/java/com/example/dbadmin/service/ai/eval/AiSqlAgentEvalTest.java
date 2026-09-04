package com.example.dbadmin.service.ai.eval;

import com.example.dbadmin.service.ai.AiEffort;
import com.example.dbadmin.service.ai.AiProvider;
import com.example.dbadmin.service.ai.AiSettings;
import com.example.dbadmin.service.ai.llm.AnthropicLlmClient;
import com.example.dbadmin.service.ai.llm.LlmClient;
import com.example.dbadmin.service.ai.llm.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 真模型评测：固定的库、固定的问题、固定的期望表集合，量的是「这套 Agent 现在有多准、多贵」。
 *
 * <p>默认不跑。要跑就设 {@code AI_EVAL_API_KEY}：</p>
 *
 * <pre>{@code
 * AI_EVAL_API_KEY=sk-... mvn test -Dtest=AiSqlAgentEvalTest
 * AI_EVAL_API_KEY=... AI_EVAL_MODEL=... AI_EVAL_BASE_URL=https://自建网关/v1 mvn test -Dtest=AiSqlAgentEvalTest
 * }</pre>
 *
 * <p>用单独的环境变量而不是复用 {@code ANTHROPIC_API_KEY}，是因为后者很可能只是开发机上给别的
 * 工具配的 —— 不该有人跑一次 {@code mvn test} 就意外花掉几十次模型调用。</p>
 *
 * <p>报告写到 {@code target/ai-eval-report.md}。通过率之外更值得盯的是每次的工具调用次数和
 * cacheReadTokens：前者说明模型要摸索多久才敢下笔，后者说明 prompt cache 有没有真的命中。</p>
 */
@EnabledIfEnvironmentVariable(named = "AI_EVAL_API_KEY", matches = ".+")
class AiSqlAgentEvalTest {
    @Test
    void runsTheFixedCaseSetAgainstTheConfiguredModel() throws Exception {
        String apiKey = System.getenv("AI_EVAL_API_KEY");
        String baseUrl = System.getenv("AI_EVAL_BASE_URL");
        AiProvider provider = baseUrl == null || baseUrl.isBlank()
                ? AiProvider.ANTHROPIC : AiProvider.OPENAI_COMPATIBLE;
        String model = System.getenv().getOrDefault("AI_EVAL_MODEL", provider.defaultModel());
        AiSettings settings = new AiSettings(true, provider, baseUrl, model, null, AiEffort.HIGH);
        LlmClient client = provider == AiProvider.ANTHROPIC
                ? new AnthropicLlmClient(settings, apiKey)
                : new OpenAiCompatibleLlmClient(settings, apiKey);

        List<AiEvalReport.Row> rows = new ArrayList<>();
        try (AiAgentHarness harness = new AiAgentHarness(
                client, AiEvalCases.glossary(AiAgentHarness.CONNECTION_ID), model)) {
            for (AiEvalCase evalCase : AiEvalCases.all()) {
                AiAgentHarness.Run run = harness.ask(evalCase.question());
                rows.add(new AiEvalReport.Row(evalCase, run,
                        AiEvalScoring.score(evalCase, run.answer(), run.validated())));
            }
        }

        String report = AiEvalReport.render(model, rows);
        Path target = Path.of("target", "ai-eval-report.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("完整报告：" + target.toAbsolutePath());
    }

}
