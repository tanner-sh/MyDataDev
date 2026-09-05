package com.example.dbadmin.service.ai.eval;

import com.example.dbadmin.service.ai.SqlTableReferences;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 给一次 Agent 产出打分。纯逻辑，和模型、数据库都无关，所以可以单独测。
 *
 * <p>判「通过」的三个条件：期望的表一张不少、禁用的表一张没有、SQL 通过了目标库的编译校验。
 * 多出来的表只记录不扣分 —— 多带一张字典表往往仍是对的答案，而漏一张核心表一定是错的。</p>
 *
 * <p>反问用例反过来：正确答案是问一句，给出 SQL 就算错。少了这一类，打分只奖励「猜出一条
 * SQL」，模型永远不会选择问。</p>
 */
public final class AiEvalScoring {
    private static final Pattern SQL_FENCE = Pattern.compile("(?is)```sql\\s*(.*?)```");

    private AiEvalScoring() {
    }

    public static Score score(AiEvalCase evalCase, String answer, boolean validated) {
        return score(evalCase, answer, validated, false);
    }

    /** @param clarified 这一轮以反问收尾（Agent 审计里的 {@code outcome=clarified}） */
    public static Score score(AiEvalCase evalCase, String answer, boolean validated, boolean clarified) {
        String sql = extractSql(answer);
        if (evalCase.expectsClarification()) {
            return new Score(evalCase.id(), sql, validated, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    clarified, true);
        }
        Set<String> actual = tables(sql);
        Set<String> expected = normalize(evalCase.expectedTables());
        Set<String> forbidden = normalize(evalCase.forbiddenTables());

        Set<String> matched = new TreeSet<>(expected);
        matched.retainAll(actual);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> forbiddenHit = new TreeSet<>(forbidden);
        forbiddenHit.retainAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        extra.removeAll(forbidden);
        Set<String> missingTokens = new TreeSet<>();
        for (String token : evalCase.expectedTokens()) {
            if (!identifierAppears(sql, token)) missingTokens.add(token.toUpperCase(Locale.ROOT));
        }

        boolean passed = sql != null && validated && missing.isEmpty()
                && forbiddenHit.isEmpty() && missingTokens.isEmpty();
        return new Score(evalCase.id(), sql, validated, matched, missing, forbiddenHit, extra, missingTokens,
                passed, false);
    }

    /**
     * 取回答里唯一的 sql 代码块。
     *
     * <p>有两块就当没有：Agent 的约定是只给一条 SQL，给了两条说明它没照做，这时候挑一条来打分
     * 等于替它圆场。</p>
     */
    public static String extractSql(String answer) {
        if (answer == null) return null;
        Matcher matcher = SQL_FENCE.matcher(answer);
        if (!matcher.find()) return null;
        String sql = matcher.group(1).trim();
        if (matcher.find()) return null;
        return sql.isEmpty() ? null : sql;
    }

    /** 标识符是否作为完整的词出现；子串匹配会让 AMT 被 TOTAL_AMOUNT 蒙混过关。 */
    static boolean identifierAppears(String sql, String identifier) {
        if (sql == null || identifier == null || identifier.isBlank()) return false;
        return Pattern.compile("(?i)(?<![\\p{L}\\p{N}_$])" + Pattern.quote(identifier)
                + "(?![\\p{L}\\p{N}_$])").matcher(sql).find();
    }

    /** SQL 里引用到的表名，去掉库名/模式名前缀和引号后统一成大写。 */
    public static Set<String> tables(String sql) {
        Set<String> result = new LinkedHashSet<>();
        if (sql == null) return result;
        for (String reference : SqlTableReferences.extract(sql)) {
            String name = SqlTableReferences.split(reference)[1];
            if (!name.isBlank()) result.add(name.toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private static Set<String> normalize(Set<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) result.add(name.toUpperCase(Locale.ROOT));
        return result;
    }

    /** @param clarificationCase 这是一条反问用例：通过的条件是它问了，而不是它写出了什么 */
    public record Score(
            String caseId,
            String sql,
            boolean validated,
            Set<String> matchedTables,
            Set<String> missingTables,
            Set<String> forbiddenTables,
            Set<String> extraTables,
            Set<String> missingTokens,
            boolean passed,
            boolean clarificationCase
    ) {
        public String reason() {
            if (clarificationCase) return passed ? "通过（问了）" : "该问却直接猜了";
            if (passed) return "通过";
            if (sql == null) return "没有产出唯一的一条 SQL";
            if (!validated) return "未通过目标库编译校验";
            if (!forbiddenTables.isEmpty()) return "命中干扰表 " + String.join("、", forbiddenTables);
            if (!missingTables.isEmpty()) return "漏掉 " + String.join("、", missingTables);
            return "口径不对，没有用到 " + String.join("、", missingTokens);
        }
    }
}
