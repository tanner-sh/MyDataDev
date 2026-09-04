package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSharingRulesTest {
    @Test
    void defaultsToNoSharing() {
        AiConnectionPolicy policy = AiSharingRules.normalize(7, "NONE", 10, false);

        assertThat(policy.sharing()).isEqualTo(AiSchemaSharing.NONE);
        assertThat(policy.sharing().allowsStructure()).isFalse();
        assertThat(policy.sampleRowLimit()).isZero();
    }

    /** 只结构档带回来的行数必须清零，否则界面上会显示一个不生效的数字。 */
    @Test
    void zeroesTheRowLimitOutsideTheSampleTier() {
        assertThat(AiSharingRules.normalize(7, "STRUCTURE", 10, false).sampleRowLimit()).isZero();
    }

    @Test
    void fillsInADefaultRowLimitForTheSampleTier() {
        AiConnectionPolicy policy = AiSharingRules.normalize(7, "STRUCTURE_AND_SAMPLE", null, false);

        assertThat(policy.sampleRowLimit()).isEqualTo(AiSharingRules.DEFAULT_SAMPLE_ROWS);
    }

    @Test
    void rejectsARowLimitAboveTheCap() {
        assertThatThrownBy(() -> AiSharingRules.normalize(7, "STRUCTURE_AND_SAMPLE", 200, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void refusesToSendSampleRowsFromAProductionConnection() {
        assertThatThrownBy(() -> AiSharingRules.normalize(7, "STRUCTURE_AND_SAMPLE", 5, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("生产连接");
    }

    /**
     * 一条连接可能先按测试库开了样本档，之后被改成生产环境；读回来时降级，
     * 而不是等到真的发请求时才发现。
     */
    @Test
    void downgradesAStoredSampleTierWhenTheConnectionBecameProduction() {
        AiConnectionPolicy stored = new AiConnectionPolicy(7, AiSchemaSharing.STRUCTURE_AND_SAMPLE, 5);

        AiConnectionPolicy effective = AiSharingRules.effective(stored, true);

        assertThat(effective.sharing()).isEqualTo(AiSchemaSharing.STRUCTURE);
        assertThat(effective.sampleRowLimit()).isZero();
    }

    @Test
    void leavesNonProductionPoliciesUntouched() {
        AiConnectionPolicy stored = new AiConnectionPolicy(7, AiSchemaSharing.STRUCTURE_AND_SAMPLE, 5);

        assertThat(AiSharingRules.effective(stored, false)).isEqualTo(stored);
    }

    @Test
    void rejectsAnUnknownTier() {
        assertThatThrownBy(() -> AiSharingRules.normalize(7, "EVERYTHING", 1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("共享档位");
    }
}
