package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.RoutineInvokeResponse;
import com.example.dbadmin.dto.ApiDtos.RoutineOutParameter;
import com.example.dbadmin.dto.ApiDtos.RoutineResultItem;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutineCommandTextTest {
    @Test
    void writesReturnValueOutParametersAndResultSetsAsCommandText() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) rows.add(Arrays.asList(i, i == 2 ? null : "r" + i));
        var result = new SqlResult(List.of(new ResultColumn("c1", "ID", "INT"), new ResultColumn("c2", "NAME", "VARCHAR")),
                rows, 0, 3, true, 500, false);
        var response = new RoutineInvokeResponse("SUCCESS", 1234, 42,
                List.of(new RoutineOutParameter("P_COUNT", "NUMBER", 7), new RoutineOutParameter("P_NOTE", "VARCHAR2", null)),
                List.of(new RoutineResultItem("RESULT_SET", result, null), new RoutineResultItem("UPDATE_COUNT", null, 3),
                        new RoutineResultItem("UPDATE_COUNT", null, 0)),
                false, List.of("步骤一完成"));

        var described = RoutineCommandText.describe(response);

        assertThat(described.text()).startsWith("调用完成，耗时 1234 ms。\n返回值：42\n输出参数：");
        assertThat(described.text()).contains("P_COUNT NUMBER   7", "P_NOTE  VARCHAR2 NULL");
        assertThat(described.text()).contains("结果集 1：\nID NAME", "2  NULL", "已选择 共 25 行，仅显示前 20 行。", "影响 3 行。");
        assertThat(described.text()).doesNotContain("r21", "影响 0 行");
        assertThat(described.output()).containsExactly("步骤一完成");
        assertThat(described.queryRows()).isEqualTo(25);
    }

    /** 服务端只读了前 500 行：读满时只能说「至少」。 */
    @Test
    void truncatedResultSetsSayAtLeast() {
        var result = new SqlResult(List.of(new ResultColumn("c1", "ID", "INT")), List.of(List.<Object>of(1)), 0, 1, true, 500, true);
        var response = new RoutineInvokeResponse("SUCCESS", 1, null, List.of(), List.of(new RoutineResultItem("RESULT_SET", result, null)), true, List.of());
        assertThat(RoutineCommandText.describe(response).text()).contains("已选择 至少 1 行");
    }

    @Test
    void timeoutDurationsReadNaturally() {
        assertThat(RoutineCommandText.duration(7200)).isEqualTo("2 小时");
        assertThat(RoutineCommandText.duration(60)).isEqualTo("1 分钟");
        assertThat(RoutineCommandText.duration(90)).isEqualTo("90 秒");
    }
}
