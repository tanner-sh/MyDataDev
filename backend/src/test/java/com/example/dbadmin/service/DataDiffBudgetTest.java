package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataDiffBudgetTest {
    @Test
    void stopsReadingAnOversizeCellAndClosesItsReader() throws Exception {
        Reader reader = spy(new StringReader("a".repeat(100_000)));
        DataDiffBudget budget = new DataDiffBudget(1_000_000, 10);
        assertThatThrownBy(() -> budget.readCell(reader))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("DATA_DIFF_CELL_LIMIT"));
        verify(reader, times(1)).read(any(char[].class), eq(0), eq(11));
        verify(reader).close();
    }

    @Test
    void consumesOneSharedBudgetAcrossCellsAndRetainedOutput() throws Exception {
        DataDiffBudget budget = new DataDiffBudget(144, 20);
        assertThat(budget.readCell(new StringReader("ab"))).isEqualTo("ab"); // 72
        budget.retainText("cd"); // 72，恰好到上限
        assertThatThrownBy(() -> budget.reserve(1))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("DATA_DIFF_MEMORY_LIMIT"));
    }

    @Test
    void preservesNullEmptyAndExactLengthValues() throws Exception {
        DataDiffBudget budget = new DataDiffBudget(4096, 2);
        assertThat(budget.readCell(null)).isNull();
        assertThat(budget.readCell(new StringReader(""))).isEmpty();
        assertThat(budget.readCell(new StringReader("中文"))).isEqualTo("中文");
    }

    @Test
    void budgetFailureAlsoClosesTheReader() throws Exception {
        Reader reader = spy(new StringReader("payload"));
        assertThatThrownBy(() -> new DataDiffBudget(1, 100).readCell(reader))
                .isInstanceOf(ApiProblemException.class);
        verify(reader).close();
    }

    @Test
    void fixedWidthTypesDoNotRequireDriverCharacterStreamConversion() throws Exception {
        var rs = mock(java.sql.ResultSet.class);
        when(rs.getString(1)).thenReturn("123.45");
        assertThat(new DataDiffBudget(1024, 20).readCell(rs, 1, java.sql.Types.DECIMAL)).isEqualTo("123.45");
        verify(rs, never()).getCharacterStream(1);
    }
}
