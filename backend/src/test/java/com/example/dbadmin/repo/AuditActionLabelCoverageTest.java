package com.example.dbadmin.repo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 后端写入的每个审计动作码，前端都要有中文名。
 *
 * <p>审计是出事之后回溯「谁在哪条连接上做了什么」的唯一凭据。一屏里混着
 * {@code SQL_TRANSACTION_ROLLBACK} 这类原始枚举，等于把翻译成本转嫁给读日志的人 —— 而这恰恰
 * 发生在最需要快速看懂的时候。</p>
 *
 * <p>检查放在这一侧是因为动作码的源头在后端：新增一处 {@code audit.log} 时，这个测试会立刻
 * 指出前端漏了哪一条，而不是等到有人打开审计抽屉才发现。</p>
 */
class AuditActionLabelCoverageTest {
    /** 结尾必须紧跟逗号：拼接出来的动作码（"OBJECT_" + operation）没有完整字面量可校验。 */
    private static final Pattern AUDIT_CALL = Pattern.compile("audit\\.log\\(\\s*[^,]+,\\s*\"([A-Z][A-Z_]+)\"\\s*,");

    @Test
    void everyAuditActionHasAChineseLabelInTheUi() throws IOException {
        Path labels = Path.of("..", "frontend", "src", "auditLog.ts");
        assumeTrue(Files.exists(labels), "前端源码不在预期位置，跳过跨模块检查");
        String source = Files.readString(labels, StandardCharsets.UTF_8);

        Set<String> actions = collectActionCodes(Path.of("src", "main", "java"));
        assertThat(actions).hasSizeGreaterThan(20);

        Set<String> untranslated = new TreeSet<>();
        for (String action : actions) {
            if (!source.contains("  " + action + ":")) untranslated.add(action);
        }
        assertThat(untranslated)
                .as("这些审计动作码在 frontend/src/auditLog.ts 里没有中文名")
                .isEmpty();
    }

    private static Set<String> collectActionCodes(Path root) throws IOException {
        Set<String> actions = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                Matcher matcher = AUDIT_CALL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) actions.add(matcher.group(1));
            }
        }
        return actions;
    }
}
