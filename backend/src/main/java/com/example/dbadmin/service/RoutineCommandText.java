package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.RoutineInvokeResponse;
import com.example.dbadmin.dto.ApiDtos.RoutineOutParameter;
import com.example.dbadmin.dto.ApiDtos.RoutineResultItem;
import com.example.dbadmin.dto.ApiDtos.SqlResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台调用例程的结果写成命令日志里的文字：返回值、OUT 参数、每个结果集一张文本表。
 *
 * <p>与命令窗口打查询结果同一种样子（{@link SqlCommandOutput}），只做文字，不碰执行。</p>
 */
final class RoutineCommandText {
    static final int PREVIEW_ROWS = 20;

    private RoutineCommandText() {
    }

    static SqlFileExecutionService.CommandUnitResult describe(RoutineInvokeResponse response) {
        List<String> parts = new ArrayList<>();
        long rows = 0;
        if (response.returnValue() != null) parts.add("返回值：" + text(response.returnValue()));
        if (!response.outParameters().isEmpty()) {
            List<List<String>> table = new ArrayList<>();
            for (RoutineOutParameter parameter : response.outParameters()) {
                table.add(List.of(parameter.name() == null ? "" : parameter.name(),
                        parameter.typeName() == null ? "" : parameter.typeName(), text(parameter.value())));
            }
            parts.add("输出参数：\n" + SqlCommandOutput.table(List.of("参数", "类型", "值"), table));
        }
        int resultSets = 0;
        for (RoutineResultItem item : response.results()) {
            if (item.result() != null) {
                resultSets++;
                SqlResult result = item.result();
                rows += result.rows().size();
                parts.add("结果集 " + resultSets + "：\n" + resultTable(result));
            } else if (item.updateCount() != null && item.updateCount() > 0) {
                parts.add("影响 " + item.updateCount() + " 行。");
            }
        }
        String summary = "调用完成，耗时 " + response.elapsedMs() + " ms。";
        String text = parts.isEmpty() ? summary : summary + "\n" + String.join("\n", parts);
        return new SqlFileExecutionService.CommandUnitResult(text, response.messages(), rows);
    }

    private static String resultTable(SqlResult result) {
        List<String> labels = result.columns().stream().map(ResultColumn::label).toList();
        List<List<String>> preview = new ArrayList<>();
        for (List<Object> row : result.rows()) {
            if (preview.size() >= PREVIEW_ROWS) break;
            List<String> cells = new ArrayList<>(row.size());
            for (Object value : row) cells.add(text(value));
            preview.add(cells);
        }
        // 例程结果在服务端最多读 500 行：读满了就是「至少」，不能说成共有这么多。
        return SqlCommandOutput.table(labels, preview) + "\n"
                + SqlCommandOutput.rowCountText(result.rows().size(), preview.size(), result.truncated());
    }

    private static String text(Object value) {
        return value == null ? "NULL" : String.valueOf(value);
    }

    /** 秒数说成人话：7200 → 2 小时，90 → 90 秒。超时提示里用。 */
    static String duration(int seconds) {
        if (seconds >= 3600 && seconds % 3600 == 0) return seconds / 3600 + " 小时";
        if (seconds >= 60 && seconds % 60 == 0) return seconds / 60 + " 分钟";
        return seconds + " 秒";
    }
}
