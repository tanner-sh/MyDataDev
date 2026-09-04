package com.example.dbadmin.service.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从整库的表清单里挑出与问题相关的那几张。
 *
 * <p>「把结构发给模型」在几十张表的库上不是问题，在几千张表的库上是致命的：上下文塞不下，
 * 塞得下也贵，而且噪声会把真正相关的表淹掉。这里做的就是选表。</p>
 *
 * <p>打分只用三件确定的事：表名是否被问题原文提到、表名的词块是否与问题里的词重合、
 * 名字长短（同分时短名优先，因为 {@code order} 比 {@code order_sync_temp_20231001} 更可能是
 * 用户说的那张表）。刻意不做语义相似度 —— 那需要再调一次模型，成本和延迟都翻倍，而选错表的
 * 代价只是模型少看一张表、多看一张无关表。</p>
 */
public final class TableSelector {
    /** 一次最多选多少张表。上下文预算与噪声的折中。 */
    public static final int MAX_TABLES = 8;
    /** 少于这个分数的表算不相关，宁可一张都不给，也不要拿八张无关表把上下文塞满。 */
    private static final int MIN_SCORE = 1;
    /** 拆词时忽略的连接词：它们出现在几乎每张表名里，对区分没有帮助。 */
    private static final Set<String> STOP_WORDS = Set.of("tbl", "table", "tab", "data", "info", "t");

    private TableSelector() {
    }

    /**
     * @param question 用户的自然语言问题
     * @param tables   候选表名（通常来自元数据目录）
     * @return 最多 {@link #MAX_TABLES} 张表，按相关度从高到低
     */
    public static List<String> select(String question, List<String> tables) {
        if (question == null || question.isBlank() || tables == null || tables.isEmpty()) return List.of();
        String normalizedQuestion = normalize(question);
        Set<String> questionTokens = tokenize(normalizedQuestion);

        List<Scored> scored = new ArrayList<>();
        for (String table : tables) {
            if (table == null || table.isBlank()) continue;
            int score = score(table, normalizedQuestion, questionTokens);
            if (score >= MIN_SCORE) scored.add(new Scored(table, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparingInt((Scored item) -> item.table().length())
                .thenComparing(Scored::table));
        return scored.stream().limit(MAX_TABLES).map(Scored::table).toList();
    }

    private static int score(String table, String question, Set<String> questionTokens) {
        String normalizedTable = normalize(table);
        // 问题里直接出现了完整表名，这是最强的信号。
        if (!normalizedTable.isBlank() && question.contains(normalizedTable)) return 10;
        Set<String> tableTokens = tokenize(normalizedTable);
        int overlap = 0;
        for (String token : tableTokens) {
            if (questionTokens.contains(token)) overlap += 2;
            else if (questionTokens.stream().anyMatch(word -> word.length() >= 3 && token.contains(word))) overlap += 1;
        }
        return overlap;
    }

    /**
     * 归一化：转小写并去掉重音。
     *
     * <p>中文按整串匹配，不分词 —— 引一个中文分词器只为选表不划算，而中文表名（「订单明细」）
     * 通常会被问题原文整段包含。</p>
     */
    private static String normalize(String value) {
        String lower = value.toLowerCase(Locale.ROOT).trim();
        return Normalizer.normalize(lower, Normalizer.Form.NFKC);
    }

    /** 按下划线、连字符、空白与大小写边界拆词。 */
    private static Set<String> tokenize(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : value.split("[^\\p{L}\\p{N}]+")) {
            if (part.isBlank()) continue;
            for (String token : part.split("(?<=\\p{Ll})(?=\\p{Lu})")) {
                String normalized = singular(token.toLowerCase(Locale.ROOT));
                if (normalized.length() < 2 || STOP_WORDS.contains(normalized)) continue;
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    /**
     * 粗暴地去掉复数尾巴。
     *
     * <p>表名叫 {@code orders} 而用户说「order」是最常见的一种错配，不处理的话短名反而会输给
     * 恰好包含单数词的长名（{@code order_sync_temp_20231001}）。只砍一个尾字母 s，不做词形还原：
     * 更聪明的处理需要词典，而收益只是多认几张表。</p>
     */
    private static String singular(String token) {
        return token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")
                ? token.substring(0, token.length() - 1)
                : token;
    }

    private record Scored(String table, int score) {
    }
}
