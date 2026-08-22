package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvStreamReaderTest {
    private static List<List<String>> readAll(String csv) throws Exception {
        try (CsvStreamReader reader = new CsvStreamReader(new StringReader(csv))) {
            java.util.List<List<String>> rows = new java.util.ArrayList<>();
            List<String> row;
            while ((row = reader.readRow()) != null) rows.add(row);
            return rows;
        }
    }

    @Test
    void readsPlainRows() throws Exception {
        assertThat(readAll("id,name\n1,张三\n2,李四\n"))
                .containsExactly(List.of("id", "name"), List.of("1", "张三"), List.of("2", "李四"));
    }

    @Test
    void keepsEmptyFieldsInsteadOfCollapsingColumns() throws Exception {
        // 列数必须稳定，否则上层的「字段数与表头一致」校验会误报。
        assertThat(readAll("a,b,c\n1,,3\n")).containsExactly(List.of("a", "b", "c"), List.of("1", "", "3"));
    }

    @Test
    void readsQuotedFieldsWithSeparatorsAndNewlines() throws Exception {
        assertThat(readAll("id,note\n1,\"a,b\"\n2,\"line1\nline2\"\n"))
                .containsExactly(List.of("id", "note"), List.of("1", "a,b"), List.of("2", "line1\nline2"));
    }

    @Test
    void doubledQuoteBecomesOneLiteralQuote() throws Exception {
        assertThat(readAll("v\n\"say \"\"hi\"\"\"\n")).containsExactly(List.of("v"), List.of("say \"hi\""));
    }

    @Test
    void handlesCrLfAndLastRowWithoutTrailingNewline() throws Exception {
        assertThat(readAll("a,b\r\n1,2\r\n3,4"))
                .containsExactly(List.of("a", "b"), List.of("1", "2"), List.of("3", "4"));
    }

    @Test
    void skipsBlankLines() throws Exception {
        // Excel 导出的文件末尾常有空行，不能被当成一条全空的数据行。
        assertThat(readAll("a\n1\n\n2\n\n")).containsExactly(List.of("a"), List.of("1"), List.of("2"));
    }

    @Test
    void quotedEmptyFieldIsNotTreatedAsBlankLine() throws Exception {
        assertThat(readAll("a\n\"\"\n")).containsExactly(List.of("a"), List.of(""));
    }

    @Test
    void returnsNullAtEndOfInput() throws Exception {
        try (CsvStreamReader reader = new CsvStreamReader(new StringReader("a\n"))) {
            assertThat(reader.readRow()).containsExactly("a");
            assertThat(reader.readRow()).isNull();
            assertThat(reader.readRow()).isNull();
        }
    }

    @Test
    void rejectsRunawayFieldInsteadOfBufferingWholeFile() {
        String malformed = "v\n\"" + "x".repeat(CsvStreamReader.MAX_FIELD_CHARS + 10);
        assertThatThrownBy(() -> readAll(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过");
    }
}
