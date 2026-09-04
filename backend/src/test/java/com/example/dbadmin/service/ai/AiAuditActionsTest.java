package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AI 的审计动作码同样要有中文名。
 *
 * <p>{@code AuditActionLabelCoverageTest} 的正则只认写成字面量的动作码，而 AI 这一侧的审计
 * 调用传的是变量（几个入口共用一条写审计的路径），扫不到。这个测试补上那道网：动作码集中在
 * {@link AiAssistantService#AUDIT_ACTIONS} 里，漏了中文名会在这里失败，而不是等到有人打开
 * 审计抽屉看见一串英文常量。</p>
 */
class AiAuditActionsTest {
    @Test
    void everyAiAuditActionHasAChineseLabelInTheUi() throws IOException {
        Path labels = Path.of("..", "frontend", "src", "auditLog.ts");
        assumeTrue(Files.exists(labels), "前端源码不在预期位置，跳过跨模块检查");
        String source = Files.readString(labels, StandardCharsets.UTF_8);

        var untranslated = new TreeSet<String>();
        for (String action : AiAssistantService.AUDIT_ACTIONS) {
            if (!source.contains("  " + action + ":")) untranslated.add(action);
        }

        assertThat(untranslated)
                .as("这些 AI 审计动作码在 frontend/src/auditLog.ts 里没有中文名")
                .isEmpty();
        assertThat(AiAssistantService.AUDIT_ACTIONS).isNotEmpty();
    }
}
