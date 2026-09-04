package com.example.dbadmin.service.ai;

import java.util.Locale;

/**
 * 一条连接允许发给模型的内容范围。
 *
 * <p>默认 {@link #NONE}：没有显式授权的连接，AI 侧连表名都拿不到。这条默认值是整个功能
 * 的安全底线 —— 新建连接不会因为管理员忘了配置而悄悄参与 AI。</p>
 */
public enum AiSchemaSharing {
    /** 不参与 AI：任何 AI 接口都取不到这条连接的结构。 */
    NONE,
    /** 只发结构：表名、列名、类型、可空、主外键、索引、注释。 */
    STRUCTURE,
    /** 结构加少量样本行；生产连接禁用这一档。 */
    STRUCTURE_AND_SAMPLE;

    public boolean allowsStructure() {
        return this != NONE;
    }

    public boolean allowsSample() {
        return this == STRUCTURE_AND_SAMPLE;
    }

    public static AiSchemaSharing parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return NONE;
        for (AiSchemaSharing sharing : values()) {
            if (sharing.name().equals(normalized)) return sharing;
        }
        throw new IllegalArgumentException("不支持的 AI 共享档位：" + value);
    }
}
