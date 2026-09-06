package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CellSerializerTest {
    private static final int CELL = 100_000;

    @Test
    void dropsTheEmptyFractionalSecondJdbcAlwaysAppends() {
        // Timestamp.toString() 没有小数秒时也补一位 .0，那位 0 出现在时间列的每一行里。
        assertThat(CellSerializer.text(Timestamp.valueOf("2025-09-18 14:30:00"))).isEqualTo("2025-09-18 14:30:00");
    }

    @Test
    void keepsRealFractionalSeconds() {
        assertThat(CellSerializer.text(Timestamp.valueOf("2025-09-18 14:30:00.123"))).isEqualTo("2025-09-18 14:30:00.123");
        assertThat(CellSerializer.text(Timestamp.valueOf("2025-09-18 14:30:00.5"))).isEqualTo("2025-09-18 14:30:00.5");
        // 纳秒级也不能被截掉。
        Timestamp nanos = Timestamp.valueOf("2025-09-18 14:30:00");
        nanos.setNanos(1);
        assertThat(CellSerializer.text(nanos)).isEqualTo("2025-09-18 14:30:00.000000001");
    }

    @Test
    void leavesOtherValuesUntouched() {
        assertThat(CellSerializer.text(java.sql.Date.valueOf("2025-09-18"))).isEqualTo("2025-09-18");
        assertThat(CellSerializer.text(LocalDateTime.parse("2025-09-18T14:30"))).isEqualTo("2025-09-18T14:30");
        assertThat(CellSerializer.text("原样")).isEqualTo("原样");
    }

    /**
     * 这条是这个类存在的理由。
     *
     * <p>查询结果、表数据浏览、执行计划、导出、例程执行结果五条路只有 CLOB 读取窗口不同，
     * 对同一个非 CLOB 的值必须给出完全一样的东西。历史上 {@code DefaultDialect} 与
     * {@code ExportService} 因为够不着 {@code service} 包内可见的文本化规则，各自退回了
     * {@code value.toString()}，于是执行计划和导出文件里的 Timestamp 多出一个 {@code .0}，
     * 而屏幕上没有 —— 导出尤其要紧，它的整个卖点就是「和屏幕上看到的一致」。
     * {@code SchemaObjectService}（例程执行结果，渲染在同一张网格里）当时没被一起收进来，
     * 除了那位 {@code .0} 还多丢一样东西：{@code BigInteger} 漏在「转成字符串」那一档外面，
     * 被当成 JSON 数字发出去，而浏览器按双精度解析。窗口参数化之后这类漂移不该再出现。</p>
     */
    @Test
    void everyPathAgreesOnValuesThatAreNotClobs() throws Exception {
        Timestamp timestamp = Timestamp.valueOf("2025-09-18 14:30:00");
        Object[] values = {
                timestamp, "文本", 42, 42L, new BigInteger("9007199254740993"),
                new BigDecimal("1.500"), true, 1.5d, Double.NaN, Float.POSITIVE_INFINITY, null
        };
        for (Object value : values) {
            Object sqlResult = CellSerializer.serialize(value, CELL, 10_000);
            Object tableBrowse = CellSerializer.serialize(value, CELL, 4_096);
            Object explain = CellSerializer.serialize(value, CELL, 100_000);
            Object export = CellSerializer.serialize(value, CELL, 10_000);
            Object routine = CellSerializer.serialize(value, CELL, 10_000);
            assertThat(tableBrowse).as("表数据浏览与查询结果对 %s 不一致", value).isEqualTo(sqlResult);
            assertThat(explain).as("执行计划与查询结果对 %s 不一致", value).isEqualTo(sqlResult);
            assertThat(export).as("导出与查询结果对 %s 不一致", value).isEqualTo(sqlResult);
            assertThat(routine).as("例程执行结果与查询结果对 %s 不一致", value).isEqualTo(sqlResult);
        }
        // 具体到那两处漂移：哪条路都不该出现 JDBC 补的那位 .0，BigInteger 也必须是文本。
        assertThat(CellSerializer.serialize(timestamp, CELL, 100_000)).isEqualTo("2025-09-18 14:30:00");
        assertThat(CellSerializer.serialize(new BigInteger("9007199254740993"), CELL, 10_000))
                .isEqualTo("9007199254740993");
    }

    @Test
    void sendsBigIntegersAndDecimalsAsTextSoTheBrowserCannotRoundThem() throws Exception {
        // 19 位雪花 ID 按 JSON 数字发会被双精度改写。
        assertThat(CellSerializer.serialize(9007199254740993L, CELL, 10_000)).isEqualTo("9007199254740993");
        assertThat(CellSerializer.serialize(new BigDecimal("1.500"), CELL, 10_000)).isEqualTo("1.500");
    }

    @Test
    void keepsNonFiniteFloatsAsTextBecauseJsonHasNoNaN() throws Exception {
        assertThat(CellSerializer.serialize(Double.NaN, CELL, 10_000)).isEqualTo("NaN");
        assertThat(CellSerializer.serialize(Float.NEGATIVE_INFINITY, CELL, 10_000)).isEqualTo("-Infinity");
        // 有限的数字仍按数字发，不转文本。
        assertThat(CellSerializer.serialize(1.5d, CELL, 10_000)).isEqualTo(1.5d);
    }

    @Test
    void marksTruncatedTextWithTheTotalLength() throws Exception {
        String long_ = "x".repeat(50);
        assertThat(CellSerializer.serialize(long_, 20, 10_000)).asString()
                .endsWith("… <文本已截断，共 50 字符>").hasSize(20);
    }

    @Test
    void truncateLeavesRoomForTheMarker() {
        assertThat(CellSerializer.truncate("abcdefghij", "…", 5)).isEqualTo("abcd…");
        assertThat(CellSerializer.truncate("abc", "", 5)).isEqualTo("abc");
        assertThat(CellSerializer.truncate("abc", "", 0)).isEmpty();
        // marker 比预算还长时退化成硬截，不至于抛异常。
        assertThat(CellSerializer.truncate("abcdef", "……长标记", 3)).isEqualTo("abc");
    }

    @Test
    void stopsProbingBinaryStreamsAfterOneMegabyte() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[2 * 1024 * 1024]);

        assertThat(CellSerializer.describeBinaryStream(input)).isEqualTo("<BINARY > 1 MB>");
        assertThat(input.available()).isPositive();
    }

    @Test
    void reportsExactLengthForSmallBinaryStreams() throws Exception {
        assertThat(CellSerializer.describeBinaryStream(new ByteArrayInputStream(new byte[17])))
                .isEqualTo("<BINARY 17 bytes>");
    }
}
