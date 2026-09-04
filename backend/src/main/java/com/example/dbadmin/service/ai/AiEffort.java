package com.example.dbadmin.service.ai;

import java.util.Locale;

/**
 * 思考深度档位。
 *
 * <p>对应 Claude 的 {@code output_config.effort}；OpenAI 兼容端点没有等价参数，那一侧
 * 直接忽略。默认 {@code HIGH}：生成 SQL 和诊断报错都属于「错了要人来收拾」的场景，
 * 省 token 不值得拿正确率换。</p>
 */
public enum AiEffort {
    LOW, MEDIUM, HIGH, XHIGH, MAX;

    public static AiEffort parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return HIGH;
        for (AiEffort effort : values()) {
            if (effort.name().equals(normalized)) return effort;
        }
        throw new IllegalArgumentException("不支持的思考深度档位：" + value);
    }
}
