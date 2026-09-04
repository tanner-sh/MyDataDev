package com.example.dbadmin.service.ai;

import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.example.dbadmin.service.SqlStatementClassifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从这条连接跑过的 SQL 里找出「别人是怎么查这些表的」。
 *
 * <p>注释和外键说明的是表之间可以怎么关联，历史说明的是实际上怎么关联 —— 哪张是主表、习惯
 * 用哪个字段过滤状态、金额到底取明细还是订单头。这类知识库结构里没有，只有跑过的语句里有。</p>
 *
 * <p>三道处理决定了它能落在「只发结构」这一档：只取执行成功的只读查询（失败的语句本身就是
 * 错的写法）、用 {@link AiSqlShape} 抹掉全部字面量、按形状去重。抹掉之后剩下的是查询骨架，
 * 里面没有任何业务值，所以不需要用户额外授权发送样本数据。</p>
 */
@Service
public class AiQueryHistoryService {
    /** 从历史里回捞多少条来排序。再多就只是把更久以前、和当前问题无关的查询翻出来。 */
    static final int SCAN_LIMIT = 200;
    /** 单条返回给模型的 SQL 长度上限。 */
    static final int MAX_SQL_CHARS = 1_200;

    private final SqlHistoryRepository history;
    private final SqlStatementClassifier classifier;

    public AiQueryHistoryService(SqlHistoryRepository history, SqlStatementClassifier classifier) {
        this.history = history;
        this.classifier = classifier;
    }

    public List<HistoryQuery> search(long connectionId, Set<String> tables, String keywords, int limit) {
        return rank(history.findRecent(connectionId, SCAN_LIMIT), classifier, tables, keywords, limit);
    }

    /**
     * 打分并去重。纯逻辑，和数据库无关。
     *
     * <p>命中表的权重远高于关键词：找相似写法时，「用到了同样这几张表」几乎总比「文本里出现过
     * 同一个词」更说明问题 —— 后者很容易只是碰巧撞上一个列名。</p>
     */
    static List<HistoryQuery> rank(
            List<SqlHistoryResponse> rows,
            SqlStatementClassifier classifier,
            Set<String> tables,
            String keywords,
            int limit
    ) {
        Set<String> wanted = upperCase(tables);
        Set<String> terms = terms(keywords);
        Map<String, Candidate> byShape = new LinkedHashMap<>();

        for (SqlHistoryResponse row : rows) {
            if (!"SUCCESS".equalsIgnoreCase(row.status())) continue;
            String sql = row.sql();
            if (sql == null || sql.isBlank() || !classifier.isSelectQuery(sql)) continue;
            String masked = AiSqlShape.mask(sql);
            if (masked.isBlank()) continue;
            Candidate candidate = byShape.computeIfAbsent(AiSqlShape.fingerprint(sql), ignored ->
                    new Candidate(clamp(masked), queryTables(masked), row.createdAt()));
            candidate.runs++;
        }

        List<HistoryQuery> result = new ArrayList<>();
        for (Candidate candidate : byShape.values()) {
            int score = score(candidate, wanted, terms);
            if (score <= 0) continue;
            result.add(new HistoryQuery(candidate.sql, List.copyOf(candidate.tables),
                    candidate.runs, candidate.lastRunAt, score));
        }
        result.sort(Comparator.comparingInt(HistoryQuery::score).reversed()
                .thenComparing(Comparator.comparingInt(HistoryQuery::runs).reversed())
                .thenComparing(HistoryQuery::sql));
        return result.size() <= limit ? List.copyOf(result) : List.copyOf(result.subList(0, limit));
    }

    private static int score(Candidate candidate, Set<String> wanted, Set<String> terms) {
        int score = 0;
        int matchedTables = 0;
        for (String table : candidate.tables) if (wanted.contains(table)) matchedTables++;
        score += matchedTables * 20;
        // 请求的表全部命中：这条历史大概率就是同一个业务问题的既有写法。
        if (!wanted.isEmpty() && matchedTables == wanted.size()) score += 30;
        String lower = candidate.sql.toLowerCase(Locale.ROOT);
        for (String term : terms) if (lower.contains(term)) score += 5;
        // 没给任何检索条件时，退化成「最近常跑的查询」，仍然有参考价值。
        if (wanted.isEmpty() && terms.isEmpty()) score += 1;
        return score;
    }

    private static Set<String> queryTables(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        for (String reference : SqlTableReferences.extract(sql)) {
            String name = SqlTableReferences.split(reference)[1];
            if (!name.isBlank()) tables.add(name.toUpperCase(Locale.ROOT));
        }
        return tables;
    }

    private static Set<String> upperCase(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            if (value != null && !value.isBlank()) result.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private static Set<String> terms(String keywords) {
        Set<String> terms = new LinkedHashSet<>();
        if (keywords == null || keywords.isBlank()) return terms;
        for (String token : keywords.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (token.length() >= 2) terms.add(token);
        }
        return terms;
    }

    private static String clamp(String sql) {
        return sql.length() <= MAX_SQL_CHARS ? sql : sql.substring(0, MAX_SQL_CHARS) + "…";
    }

    private static final class Candidate {
        private final String sql;
        private final Set<String> tables;
        private final String lastRunAt;
        private int runs;

        private Candidate(String sql, Set<String> tables, String lastRunAt) {
            this.sql = sql;
            this.tables = tables;
            this.lastRunAt = lastRunAt;
        }
    }

    /**
     * @param sql   已经抹掉字面量的查询骨架
     * @param runs  这个形状在扫描窗口里跑过多少次；跑得多的写法更可能是这个库的惯例
     */
    public record HistoryQuery(String sql, List<String> tables, int runs, String lastRunAt, int score) {
    }
}
