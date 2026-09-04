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
 */
public final class AiEvalScoring {
    private static final Pattern SQL_FENCE = Pattern.compile("(?is)```sql\\s*(.*?)```");

    private AiEvalScoring() {
    }

    public static Score score(AiEvalCase evalCase, String answer, boolean validated) {
        String sql = extractSql(answer);
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

        boolean passed = sql != null && validated && missing.isEmpty() && forbiddenHit.isEmpty();
        return new Score(evalCase.id(), sql, validated, matched, missing, forbiddenHit, extra, passed);
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

    public record Score(
            String caseId,
            String sql,
            boolean validated,
            Set<String> matchedTables,
            Set<String> missingTables,
            Set<String> forbiddenTables,
            Set<String> extraTables,
            boolean passed
    ) {
        public String reason() {
            if (passed) return "通过";
            if (sql == null) return "没有产出唯一的一条 SQL";
            if (!validated) return "未通过目标库编译校验";
            if (!forbiddenTables.isEmpty()) return "命中干扰表 " + String.join("、", forbiddenTables);
            return "漏掉 " + String.join("、", missingTables);
        }
    }
}
