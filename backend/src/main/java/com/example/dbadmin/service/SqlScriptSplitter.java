package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SqlScriptSplitter {
    public List<StatementSegment> split(String script) { return split(script, "generic"); }
    public List<StatementSegment> split(String script, String dbType) {
        return parse(script, dbType, SqlScriptDelimiter.AUTO).segments();
    }

    /** 带分隔方式的拆分，同时带回解析时的说明（被忽略的 SQL*Plus 指令、PROMPT 文本等）。 */
    public Split parse(String script, String dbType, SqlScriptDelimiter delimiter) {
        return parse(script, dbType, delimiter, null);
    }

    private Split parse(String script, String dbType, SqlScriptDelimiter delimiter, SqlScriptParser.DirectiveListener directives) {
        List<StatementSegment> result = new ArrayList<>();
        if (script == null || script.isBlank()) return new Split(result, List.of());
        try {
            var summary = SqlScriptParser.read(new StringReader(script), dbType, Math.max(1, script.length()), delimiter, unit ->
                    result.add(new StatementSegment(unit.sql(), Math.toIntExact(unit.startOffset()), Math.toIntExact(unit.endOffset()))),
                    directives);
            return new Split(result, summary.notices());
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }

    /**
     * 闸门（权限、无 WHERE 写操作确认）用的切分：按连接的真实库类型切，SQL*Plus 指令（含 WHENEVER
     * SQLERROR CONTINUE）在这里只是被跳过 —— 某个入口允不允许这条指令由执行那一步的解析判定，闸门
     * 只关心「会发给数据库的语句是哪几条」。按 "generic" 切的话，Oracle 连接上的 PROMPT / WHENEVER
     * 会在闸门这一步就被当成不支持的指令拒掉，命令窗口的 SQL*Plus 支持从接口进来根本走不到。
     */
    public List<StatementSegment> gateUnits(String script, String dbType, SqlScriptDelimiter delimiter) {
        SqlScriptDelimiter mode = delimiter == null ? SqlScriptDelimiter.AUTO : delimiter;
        return classificationUnits(parse(script, dbType, mode, ignored -> { }).segments(), dbType, mode);
    }
    /**
     * 交互式执行入口用的拆分：分隔方式是「自动」而脚本里又有独占一行的分隔符（DBeaver 的 !）时，
     * 不悄悄换一种切法，而是抛 409 SCRIPT_DELIMITER_DETECTED 让用户确认 —— 同一份脚本切法不同，
     * 执行的就是不同的语句。用户选择保持自动时带 delimiterChecked=true 再来。
     */
    public Split parseInteractive(String script, String dbType, SqlScriptDelimiter delimiter, boolean delimiterChecked) {
        SqlScriptDelimiter mode = delimiter == null ? SqlScriptDelimiter.AUTO : delimiter;
        requireDelimiterDecision(script, dbType, mode, delimiterChecked);
        Split split = parse(script, dbType, mode);
        if (mode.isAuto()) return split;
        List<String> notices = new ArrayList<>();
        notices.add("按「" + mode.label() + "」分段，共 " + split.segments().size() + " 条");
        notices.addAll(split.notices());
        return new Split(split.segments(), notices);
    }

    /**
     * 自定义分隔符下一段里可能写了好几条语句（分号不再分段）。权限、只读与生产闸门按语句种类判定，
     * 只看一段的开头会把「SELECT 1; DROP TABLE t」判成查询 —— 所以再按默认规则细分一次。细分不了的
     * （只会是字符串或注释没闭合）原样返回：那样的一段发给数据库本身就会报语法错误。
     */
    public List<StatementSegment> classificationUnits(List<StatementSegment> segments, String dbType, SqlScriptDelimiter delimiter) {
        if (delimiter == null || delimiter.isAuto()) return segments;
        List<StatementSegment> units = new ArrayList<>();
        for (StatementSegment segment : segments) {
            try {
                List<StatementSegment> parts = split(segment.sql(), dbType);
                units.addAll(parts.isEmpty() ? List.of(segment) : parts);
            } catch (IllegalArgumentException unsplittable) {
                units.add(segment);
            }
        }
        return units;
    }

    /** 只做分隔符识别那一步：自动模式下发现独占行分隔符且用户还没表态时抛 409。 */
    public void requireDelimiterDecision(String script, String dbType, SqlScriptDelimiter delimiter, boolean delimiterChecked) {
        SqlScriptDelimiter mode = delimiter == null ? SqlScriptDelimiter.AUTO : delimiter;
        if (mode.isAuto() && !delimiterChecked) {
            var detected = SqlScriptDelimiter.detect(script, dbType);
            if (detected.isPresent()) {
                int count;
                try {
                    count = parse(script, dbType, SqlScriptDelimiter.custom(detected.get())).segments().size();
                } catch (IllegalArgumentException ignored) {
                    count = -1;
                }
                if (count > 0) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, "SCRIPT_DELIMITER_DETECTED",
                            "脚本里有独占一行的「" + detected.get() + "」，像是 DBeaver 等工具的自定义语句分隔符。按它分段共 " + count + " 条语句。",
                            Map.of("delimiter", detected.get(), "statementCount", count));
                }
            }
        }
    }
    public record StatementSegment(String sql, int startOffset, int endOffset) { }
    public record Split(List<StatementSegment> segments, List<String> notices) {
        public Split {
            segments = List.copyOf(segments);
            notices = List.copyOf(notices);
        }
    }
}
