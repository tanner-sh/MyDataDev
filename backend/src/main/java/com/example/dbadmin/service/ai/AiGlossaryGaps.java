package com.example.dbadmin.service.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「AI 搜过但这个库里什么都没搜到」的检索词该怎么筛。
 *
 * <p>M11 从表注释推候选，能推出来的都是注释里已经有的词 —— 而词典真正不可替代的是用户嘴里
 * 那些不在任何注释里的说法。这里补的正是那一半：{@code search_schema} 一无所获的检索词，
 * 就是用户的说法和这个库的命名之间对不上的现场采样。信号本来每天都在产生，此前只用来决定
 * 要不要重试，用完就丢了。</p>
 *
 * <p>只留下「像个名字」的词。这条规则和 {@link AiGlossarySuggestions} 是同一条：超过十二个
 * 汉字的就不是名字而是描述，词典里混进一句话比少一条词条更糟。模型的检索词偶尔会写成
 * 「每个客户本月的成交总金额」，那种整句留下来只会把这张清单变成噪音。</p>
 */
public final class AiGlossaryGaps {
    /** 一次 Agent 请求最多记几个词。多轮换着同义词搜是常态，但十个以上就不是「没搜到」而是乱搜了。 */
    public static final int MAX_TERMS_PER_RUN = 10;
    /** 一条连接上最多留几条待补词条。再多就不是「该补什么」而是另一份要通读的清单。 */
    public static final int MAX_TERMS_PER_CONNECTION = 200;
    /** 显示宽度上限：汉字算两格，也就是十二个汉字或二十四个字母。 */
    private static final int MAX_WIDTH = 24;

    private AiGlossaryGaps() {
    }

    /** 从一次请求里搜空的检索词中挑出值得记的，按首次出现的顺序去重。 */
    public static List<String> candidates(Collection<String> queries) {
        if (queries == null || queries.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String query : queries) {
            String term = clean(query);
            if (term.isEmpty() || !looksLikeName(term)) continue;
            if (!seen.add(normalize(term))) continue;
            result.add(term);
            if (result.size() >= MAX_TERMS_PER_RUN) break;
        }
        return List.copyOf(result);
    }

    /** 去重和「这个词是不是已经进词典了」的比较口径，两处必须用同一个。 */
    public static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    /** 词典里已有的词（含别名）都不再是缺口，保存词典时据此销账。 */
    public static Set<String> covered(Collection<AiBusinessTerm> terms) {
        Set<String> result = new LinkedHashSet<>();
        if (terms == null) return result;
        for (AiBusinessTerm term : terms) {
            result.add(normalize(term.term()));
            for (String alias : term.aliases()) result.add(normalize(alias));
        }
        result.remove("");
        return result;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }

    /**
     * 名字与描述的分界。除了长度，还要求至少有一个字母或汉字 —— 纯数字和纯符号的检索词
     * （模型偶尔会把一个 ID 当关键词搜）记下来也没人补得了。
     */
    private static boolean looksLikeName(String term) {
        int width = 0;
        boolean hasLetter = false;
        for (int index = 0; index < term.length(); index++) {
            char ch = term.charAt(index);
            width += ch > 0x2E80 ? 2 : 1;
            if (Character.isLetter(ch)) hasLetter = true;
        }
        return hasLetter && width <= MAX_WIDTH;
    }
}
