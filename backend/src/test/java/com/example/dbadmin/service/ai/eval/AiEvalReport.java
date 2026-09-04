package com.example.dbadmin.service.ai.eval;

import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * 把一轮评测渲染成 Markdown 报告。
 *
 * <p>和模型、数据库都无关，所以能单独测 —— 一次真评测要花掉几十次模型调用，报告在最后一步
 * 抛异常的代价太高了。</p>
 */
public final class AiEvalReport {
    private AiEvalReport() {
    }

    public record Row(AiEvalCase evalCase, AiAgentHarness.Run run, AiEvalScoring.Score score) {
    }

    public static String render(String model, List<Row> rows) {
        StringBuilder out = new StringBuilder();
        long passed = rows.stream().filter(row -> row.score().passed()).count();
        out.append("# AI SQL Agent 评测报告\n\n");
        out.append("- 模型：`").append(model).append("`\n");
        out.append("- 用例：").append(rows.size()).append(" 条，通过 ").append(passed)
                .append("（").append(percent(passed, rows.size())).append("）\n");
        out.append("- 平均轮次：").append(average(rows, row -> row.run().number("rounds"))).append('\n');
        out.append("- 平均工具调用：").append(average(rows, row -> row.run().number("tools"))).append('\n');
        out.append("- 平均输入 token：").append(average(rows, row -> row.run().number("inputTokens"))).append('\n');
        out.append("- 平均输出 token：").append(average(rows, row -> row.run().number("outputTokens"))).append('\n');
        out.append("- 平均缓存读 token：").append(average(rows, row -> row.run().number("cacheReadTokens")))
                .append("（0 有两种可能：前缀被写脏了，或者这家网关根本不报缓存用量）\n");
        out.append("- 平均耗时：").append(String.format(Locale.ROOT, "%.1f 秒",
                        rows.stream().mapToLong(row -> row.run().elapsed().toMillis()).average().orElse(0) / 1000))
                .append("\n\n");

        out.append("| 用例 | 结果 | 说明 | 轮次 | 工具 | 输入 token | 输出 token | 缓存读 | 秒 |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row row : rows) {
            out.append("| ").append(row.evalCase().id())
                    .append(" | ").append(row.score().passed() ? "通过" : "**未通过**")
                    .append(" | ").append(row.score().reason())
                    .append(" | ").append(row.run().number("rounds"))
                    .append(" | ").append(row.run().number("tools"))
                    .append(" | ").append(row.run().number("inputTokens"))
                    .append(" | ").append(row.run().number("outputTokens"))
                    .append(" | ").append(row.run().number("cacheReadTokens"))
                    .append(" | ").append(row.run().elapsed().toMillis() / 1000)
                    .append(" |\n");
        }

        out.append("\n## 未通过的用例\n\n");
        boolean anyFailure = false;
        for (Row row : rows) {
            if (row.score().passed()) continue;
            anyFailure = true;
            out.append("### ").append(row.evalCase().id()).append(" — ").append(row.score().reason()).append("\n\n");
            out.append("问题：").append(row.evalCase().question()).append("\n\n");
            out.append("考察点：").append(row.evalCase().note()).append("\n\n");
            out.append("期望的表：").append(String.join("、", row.evalCase().expectedTables())).append("\n\n");
            if (!row.evalCase().expectedTokens().isEmpty()) {
                out.append("期望用到的字段：").append(String.join("、", row.evalCase().expectedTokens())).append("\n\n");
            }
            out.append("```sql\n")
                    .append(row.score().sql() == null ? "（没有产出 SQL）" : row.score().sql())
                    .append("\n```\n\n");
        }
        if (!anyFailure) out.append("全部通过。\n");
        return out.toString();
    }

    private static String percent(long part, long total) {
        return total == 0 ? "0%" : String.format(Locale.ROOT, "%.0f%%", 100.0 * part / total);
    }

    private static String average(List<Row> rows, ToIntFunction<Row> value) {
        return String.format(Locale.ROOT, "%.1f", rows.stream().mapToInt(value).average().orElse(0));
    }
}
