package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CellValuesTest {
    @Test
    void dropsTheEmptyFractionalSecondJdbcAlwaysAppends() {
        // Timestamp.toString() 没有小数秒时也补一位 .0，那位 0 出现在时间列的每一行里。
        assertThat(CellValues.text(Timestamp.valueOf("2025-09-18 14:30:00"))).isEqualTo("2025-09-18 14:30:00");
    }

    @Test
    void keepsRealFractionalSeconds() {
        assertThat(CellValues.text(Timestamp.valueOf("2025-09-18 14:30:00.123"))).isEqualTo("2025-09-18 14:30:00.123");
        assertThat(CellValues.text(Timestamp.valueOf("2025-09-18 14:30:00.5"))).isEqualTo("2025-09-18 14:30:00.5");
        // 纳秒级也不能被截掉。
        Timestamp nanos = Timestamp.valueOf("2025-09-18 14:30:00");
        nanos.setNanos(1);
        assertThat(CellValues.text(nanos)).isEqualTo("2025-09-18 14:30:00.000000001");
    }

    @Test
    void leavesOtherValuesUntouched() {
        assertThat(CellValues.text(java.sql.Date.valueOf("2025-09-18"))).isEqualTo("2025-09-18");
        assertThat(CellValues.text(LocalDateTime.parse("2025-09-18T14:30"))).isEqualTo("2025-09-18T14:30");
        assertThat(CellValues.text("原样")).isEqualTo("原样");
    }
}
