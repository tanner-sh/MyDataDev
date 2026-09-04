package com.example.dbadmin.service.ai;

import com.example.dbadmin.service.ai.AiSchemaTools.CatalogObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从表注释里推出业务词典的候选词条。纯逻辑，和数据库无关。
 *
 * <p>存在的理由很直接：词典功能做完了，但**没人会对着空表格从零手填一百条**。功能存在等于
 * 不存在。而库里其实已经有一份现成的语料 —— 表注释。把它整理成「业务词 → 真实对象」的候选，
 * 管理员只需要勾选、改名、补别名。</p>
 *
 * <p>要说清楚这件事能做到哪一步：注释里的词本来就能被 search_schema 搜到，所以自动生成的词条
 * 本身不是新信息。**真正的价值在于把「从零填写」变成「审阅和补别名」** —— 用户嘴里的「会员」
 * 「买家」不会出现在任何注释里，那才是词典不可替代的部分，而那部分只能由人补。没有注释的表
 * 更是一点线索都没有，只能单独列出来提醒人去补。</p>
 */
public final class AiGlossarySuggestions {
    /**
     * 注释里的名字最长多少字。
     *
     * <p>业务实体的名字通常是二到八个字（客户、销售订单、支付流水）。放宽到二十几个字，
     * 「这张表按天汇总每个渠道的成交金额」这种描述性句子就会被当成业务词条塞进词典 ——
     * 词典里混进一句话，比少一条词条更糟。</p>
     */
    static final int MAX_TERM_SOURCE_CHARS = 12;
    /** 注释里到此为止就是名字，后面是补充说明。 */
    private static final String SEPARATORS = "。．.，,;；:：（(【[\n\r\t";
    /**
     * 通用后缀。「客户主档」的业务词是「客户」，带上「主档」反而搜不到用户嘴里说的那个词。
     * 按长度倒序剥，先剥「信息表」再剥「表」。
     */
    private static final List<String> SUFFIXES = List.of(
            "记录表", "信息表", "明细表", "数据表", "字典表", "配置表", "关系表", "主表",
            "主档", "档案", "记录", "信息", "明细", "数据", "字典", "配置", "列表", "清单", "定义", "表");

    private AiGlossarySuggestions() {
    }

    /**
     * @param existingTerms 已有词条（含别名），大小写与首尾空白无关；命中的不再重复建议
     * @param usage 每张表在执行历史里被查过多少次，用来排序
     */
    public static Result suggest(
            List<CatalogObject> objects,
            Set<String> existingTerms,
            Map<String, Integer> usage,
            int limit
    ) {
        Set<String> taken = normalizedSet(existingTerms);
        Map<String, Integer> usageByName = normalizedUsage(usage);
        Map<String, Draft> drafts = new LinkedHashMap<>();
        List<String> uncommented = new ArrayList<>();

        for (CatalogObject object : objects) {
            String comment = object.comment() == null ? "" : object.comment().trim();
            if (comment.isEmpty()) {
                uncommented.add(object.name());
                continue;
            }
            String source = headline(comment);
            if (source.isEmpty() || source.length() > MAX_TERM_SOURCE_CHARS) continue;
            String term = stripSuffixes(source);
            if (term.length() < 2 || taken.contains(normalize(term))) continue;
            // 同一个业务词命中多张表是常态（销售订单和订单明细都是「订单」），合成一条词条。
            Draft draft = drafts.computeIfAbsent(normalize(term), ignored -> new Draft(term));
            draft.objectNames.add(object.name());
            if (!normalize(source).equals(normalize(term))) draft.aliases.add(source);
            if (draft.description == null) draft.description = comment;
            draft.usage += usageByName.getOrDefault(normalize(object.name()), 0);
        }

        List<Suggestion> suggestions = drafts.values().stream()
                .map(Draft::toSuggestion)
                // 先看历史里被查得多的：没人查过的表，先给它写词条没有意义。
                .sorted(Comparator.comparingInt(Suggestion::usageCount).reversed()
                        .thenComparing(Suggestion::term))
                .limit(Math.max(0, limit))
                .toList();
        return new Result(suggestions, List.copyOf(uncommented));
    }

    /** 注释里第一个分隔符之前的部分，也就是名字本身。 */
    static String headline(String comment) {
        int cut = comment.length();
        for (int index = 0; index < comment.length(); index++) {
            if (SEPARATORS.indexOf(comment.charAt(index)) >= 0) {
                cut = index;
                break;
            }
        }
        return comment.substring(0, cut).trim();
    }

    /** 反复剥掉通用后缀，直到剥不动或者只剩一个字。 */
    static String stripSuffixes(String source) {
        String value = source;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String suffix : SUFFIXES) {
                if (value.length() > suffix.length() + 1 && value.endsWith(suffix)) {
                    value = value.substring(0, value.length() - suffix.length());
                    stripped = true;
                    break;
                }
            }
        }
        return value;
    }

    private static Set<String> normalizedSet(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            if (value != null && !value.isBlank()) result.add(normalize(value));
        }
        return result;
    }

    private static Map<String, Integer> normalizedUsage(Map<String, Integer> usage) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (usage == null) return result;
        usage.forEach((name, count) -> {
            if (name != null && !name.isBlank()) result.merge(normalize(name), count, Integer::sum);
        });
        return result;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @param uncommented 连注释都没有的对象。给不出候选词，但正是 AI 最找不到的那批 ——
     *                    单独列出来让人知道该去补什么
     */
    public record Result(List<Suggestion> suggestions, List<String> uncommented) {
    }

    public record Suggestion(
            String term,
            List<String> aliases,
            List<String> objectNames,
            String description,
            int usageCount
    ) {
    }

    private static final class Draft {
        private final String term;
        private final Set<String> aliases = new LinkedHashSet<>();
        private final Set<String> objectNames = new LinkedHashSet<>();
        private String description;
        private int usage;

        private Draft(String term) {
            this.term = term;
        }

        private Suggestion toSuggestion() {
            return new Suggestion(term, List.copyOf(aliases), List.copyOf(objectNames), description, usage);
        }
    }
}
