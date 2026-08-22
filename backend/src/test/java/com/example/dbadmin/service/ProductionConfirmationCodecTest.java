package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfirmationCodecTest {
    @Test
    void restoresTheChineseConnectionNameTheBrowserHadToEncode() {
        // encodeURIComponent('生产订单库')
        assertThat(ProductionConfirmationCodec.decode("%E7%94%9F%E4%BA%A7%E8%AE%A2%E5%8D%95%E5%BA%93"))
                .isEqualTo("生产订单库");
    }

    @Test
    void leavesPlainAsciiNamesUntouched() {
        assertThat(ProductionConfirmationCodec.decode("prod-main")).isEqualTo("prod-main");
        assertThat(ProductionConfirmationCodec.decode("")).isEmpty();
        assertThat(ProductionConfirmationCodec.decode(null)).isNull();
    }

    @Test
    void doesNotTurnPlusIntoASpaceTheWayUrlDecoderWould() {
        // encodeURIComponent('prod+backup') === 'prod%2Bbackup'
        assertThat(ProductionConfirmationCodec.decode("prod%2Bbackup")).isEqualTo("prod+backup");
        // 未编码的加号必须原样保留。
        assertThat(ProductionConfirmationCodec.decode("prod+backup%2Fa")).isEqualTo("prod+backup/a");
    }

    @Test
    void roundTripsAPercentSignInTheConnectionName() {
        // encodeURIComponent('折扣100%库') 的 ASCII 部分
        assertThat(ProductionConfirmationCodec.decode("100%25")).isEqualTo("100%");
    }

    @Test
    void leavesRawNonAsciiValuesAlone() {
        // 恢复任务从 JSON body 传原始值，body 是 UTF-8，本来就不需要编码。
        assertThat(ProductionConfirmationCodec.decode("生产订单库")).isEqualTo("生产订单库");
        assertThat(ProductionConfirmationCodec.decode("折扣100%库")).isEqualTo("折扣100%库");
    }

    @Test
    void returnsMalformedEscapesUnchangedRatherThanCorruptingThem() {
        assertThat(ProductionConfirmationCodec.decode("prod%")).isEqualTo("prod%");
        assertThat(ProductionConfirmationCodec.decode("prod%zz")).isEqualTo("prod%zz");
        assertThat(ProductionConfirmationCodec.decode("prod%2")).isEqualTo("prod%2");
        // 半个 UTF-8 序列
        assertThat(ProductionConfirmationCodec.decode("%E7%94")).isEqualTo("%E7%94");
    }
}
