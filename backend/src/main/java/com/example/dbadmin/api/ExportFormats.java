package com.example.dbadmin.api;

import org.springframework.http.MediaType;

import java.util.Locale;
import java.util.Set;

/**
 * 导出格式的白名单、扩展名与 MIME 类型。
 *
 * <p>两个控制器都要用（查询导出与表数据导出），放在一处是因为白名单必须与 ExportService 里
 * 那份保持一致 —— 分成两份之后，新增一种格式时漏改一边只会在运行时才发现。</p>
 */
final class ExportFormats {
    private ExportFormats() {
    }

    static String normalize(String format) {
        String normalized = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (Set.of("csv", "json", "sql", "xml", "markdown", "xlsx").contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("不支持的导出格式：" + format);
    }

    /** Markdown 的惯例扩展名是 .md；写成 .markdown 会让不少工具认不出来。 */
    static String extension(String format) {
        return "markdown".equals(format) ? "md" : format;
    }

    static MediaType contentType(String format) {
        return switch (format) {
            case "json" -> MediaType.APPLICATION_JSON;
            case "xml" -> MediaType.APPLICATION_XML;
            case "sql" -> MediaType.TEXT_PLAIN;
            case "markdown" -> MediaType.parseMediaType("text/markdown");
            case "xlsx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> MediaType.parseMediaType("text/csv");
        };
    }
}
