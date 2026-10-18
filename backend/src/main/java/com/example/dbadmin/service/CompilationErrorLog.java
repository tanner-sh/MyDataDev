package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.CompilationError;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一份脚本执行过程中的编译错误账本，按对象记。
 *
 * <p>部署脚本里常见「先建、发现错了、后面再 CREATE OR REPLACE 一次」：按对象覆盖，最后一次编译通过的对象
 * 就从账本里消失，不会因为中途某一版是坏的而在结果里被报成 INVALID。</p>
 */
final class CompilationErrorLog {
    private final int maxLines;
    private final Map<String, Entry> byObject = new LinkedHashMap<>();

    private record Entry(String label, List<String> lines) { }

    CompilationErrorLog(int maxLines) {
        this.maxLines = maxLines;
    }

    /** @return 账本是否因此变化（需要落库） */
    boolean record(CompilationDiagnostics.Target target, List<CompilationError> errors) {
        String key = target.type() + "\u0000" + (target.owner() == null ? "" : target.owner().toUpperCase(Locale.ROOT))
                + "\u0000" + target.name().toUpperCase(Locale.ROOT);
        if (errors.isEmpty()) return byObject.remove(key) != null;
        byObject.put(key, new Entry(target.label(), errors.stream()
                .map(error -> CompilationDiagnostics.describe(target, error).replaceAll("[\\r\\n]+", " ")).toList()));
        return true;
    }

    List<String> lines() {
        return byObject.values().stream().flatMap(entry -> entry.lines().stream()).limit(maxLines).toList();
    }

    String summary() {
        if (byObject.isEmpty()) return "";
        List<String> labels = byObject.values().stream().map(Entry::label).toList();
        return "其中 " + labels.size() + " 个对象编译失败（INVALID）：" + String.join("、", labels) + "；明细见 compilationErrors。";
    }
}
