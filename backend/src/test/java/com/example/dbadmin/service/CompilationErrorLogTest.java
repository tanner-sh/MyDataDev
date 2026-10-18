package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.CompilationError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompilationErrorLogTest {
    private static final CompilationDiagnostics.Target SP_A = new CompilationDiagnostics.Target("ETL", "SP_A", "PROCEDURE");
    private static final CompilationDiagnostics.Target SP_B = new CompilationDiagnostics.Target(null, "sp_b", "PROCEDURE");

    @Test
    void anObjectFixedLaterInTheSameScriptIsNoLongerReported() {
        var log = new CompilationErrorLog(200);
        assertThat(log.record(SP_A, List.of(new CompilationError(3, 5, "PLS-00201\nidentifier")))).isTrue();
        assertThat(log.record(SP_B, List.of(new CompilationError(1, 1, "PLS-00103")))).isTrue();
        assertThat(log.lines()).containsExactly("ETL.SP_A（PROCEDURE） 第 3 行第 5 列：PLS-00201 identifier", "sp_b（PROCEDURE） 第 1 行第 1 列：PLS-00103");

        // 同一对象换了大小写写法、这次编译通过：从账本里移除。
        assertThat(log.record(new CompilationDiagnostics.Target("etl", "sp_a", "PROCEDURE"), List.of())).isTrue();
        assertThat(log.lines()).containsExactly("sp_b（PROCEDURE） 第 1 行第 1 列：PLS-00103");
        assertThat(log.summary()).isEqualTo("其中 1 个对象编译失败（INVALID）：sp_b（PROCEDURE）；明细见 compilationErrors。");

        // 一直是好的对象不需要落库。
        assertThat(log.record(new CompilationDiagnostics.Target(null, "SP_OK", "PROCEDURE"), List.of())).isFalse();
    }

    @Test
    void capsTheTotalNumberOfLines() {
        var log = new CompilationErrorLog(2);
        log.record(SP_A, List.of(new CompilationError(1, 1, "a"), new CompilationError(2, 1, "b"), new CompilationError(3, 1, "c")));
        assertThat(log.lines()).hasSize(2);
        assertThat(new CompilationErrorLog(2).summary()).isEmpty();
    }
}
