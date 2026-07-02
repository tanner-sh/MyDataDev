package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportServiceTest {
    private final SqlService sqlService = mock(SqlService.class);
    private final ExportService exportService = new ExportService(sqlService, new ObjectMapper());

    @Test
    void exportsCsvWithEscapedValues() throws Exception {
        when(sqlService.execute(eq(1L), eq("select * from t"), eq(10_000), eq("admin"))).thenReturn(result());

        String body = exportService.export(1L, "select * from t", "csv", "admin");

        assertThat(body).isEqualTo("""
                "id","name","note","active","empty"
                "1","Alice","a, ""quoted"" value","true",""
                """.stripTrailing());
    }

    @Test
    void exportsJsonArray() throws Exception {
        when(sqlService.execute(eq(1L), eq("select * from t"), eq(10_000), eq("admin"))).thenReturn(result());

        String body = exportService.export(1L, "select * from t", "json", "admin");

        assertThat(body).contains("\"id\" : 1");
        assertThat(body).contains("\"name\" : \"Alice\"");
    }

    @Test
    void exportsSqlInsertSnapshot() throws Exception {
        when(sqlService.execute(eq(1L), eq("select * from t"), eq(10_000), eq("admin"))).thenReturn(result());

        String body = exportService.export(1L, "select * from t", "sql", "admin");

        assertThat(body).isEqualTo("INSERT INTO query_result (\"id\", \"name\", \"note\", \"active\", \"empty\") VALUES (1, 'Alice', 'a, \"quoted\" value', true, NULL);");
    }

    @Test
    void exportsXmlWithEscapedValuesAndSafeTags() throws Exception {
        SqlResult xmlResult = new SqlResult(
                List.of("1 id", "note"),
                List.of(Map.of("1 id", 1, "note", "a < b & c")),
                0,
                1,
                true
        );
        when(sqlService.execute(eq(1L), eq("select * from t"), eq(10_000), eq("admin"))).thenReturn(xmlResult);

        String body = exportService.export(1L, "select * from t", "xml", "admin");

        assertThat(body).contains("<_1_id>1</_1_id>");
        assertThat(body).contains("<note>a &lt; b &amp; c</note>");
    }

    @Test
    void rejectsNonQueryResults() throws Exception {
        when(sqlService.execute(eq(1L), eq("update t set name = 'x'"), eq(10_000), eq("admin")))
                .thenReturn(new SqlResult(List.of(), List.of(), 1, 1, false));

        assertThatThrownBy(() -> exportService.export(1L, "update t set name = 'x'", "csv", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("只有查询结果可以导出。");
    }

    @Test
    void rejectsUnsupportedFormat() throws Exception {
        when(sqlService.execute(eq(1L), eq("select * from t"), eq(10_000), eq("admin"))).thenReturn(result());

        assertThatThrownBy(() -> exportService.export(1L, "select * from t", "xlsx", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的导出格式：xlsx");
    }

    private SqlResult result() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("name", "Alice");
        row.put("note", "a, \"quoted\" value");
        row.put("active", true);
        row.put("empty", null);
        return new SqlResult(
                List.of("id", "name", "note", "active", "empty"),
                List.of(row),
                0,
                1,
                true
        );
    }
}
