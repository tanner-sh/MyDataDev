package com.example.dbadmin.mcp;

import com.example.dbadmin.service.SqlStatementClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpAccessLevelTest {
    @Test
    void higherLevelsCoverLowerOnes() {
        assertThat(McpAccessLevel.FULL.covers(McpAccessLevel.DATA_WRITE)).isTrue();
        assertThat(McpAccessLevel.FULL.covers(McpAccessLevel.READ_ONLY)).isTrue();
        assertThat(McpAccessLevel.DATA_WRITE.covers(McpAccessLevel.READ_ONLY)).isTrue();
        assertThat(McpAccessLevel.DATA_WRITE.covers(McpAccessLevel.FULL)).isFalse();
        assertThat(McpAccessLevel.READ_ONLY.covers(McpAccessLevel.DATA_WRITE)).isFalse();
    }

    @Test
    void mapsStatementKindsToTheLowestSufficientLevel() {
        assertThat(McpAccessLevel.requiredFor(SqlStatementClassifier.Kind.QUERY)).isEqualTo(McpAccessLevel.READ_ONLY);
        assertThat(McpAccessLevel.requiredFor(SqlStatementClassifier.Kind.MUTATION)).isEqualTo(McpAccessLevel.DATA_WRITE);
        assertThat(McpAccessLevel.requiredFor(SqlStatementClassifier.Kind.DDL)).isEqualTo(McpAccessLevel.FULL);
        // 认不出来的语句可能是任何东西，必须按最危险的算。
        assertThat(McpAccessLevel.requiredFor(SqlStatementClassifier.Kind.UNKNOWN)).isEqualTo(McpAccessLevel.FULL);
    }

    @Test
    void parsesConfiguredValuesAndRejectsUnknownOnes() {
        assertThat(McpAccessLevel.parse("data_write")).isEqualTo(McpAccessLevel.DATA_WRITE);
        assertThat(McpAccessLevel.parse("  FULL  ")).isEqualTo(McpAccessLevel.FULL);
        // 缺省视为只读：升级时既有授权没有这个字段。
        assertThat(McpAccessLevel.parse(null)).isEqualTo(McpAccessLevel.READ_ONLY);
        assertThat(McpAccessLevel.parse("")).isEqualTo(McpAccessLevel.READ_ONLY);
        assertThatThrownBy(() -> McpAccessLevel.parse("SUPERUSER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("READ_ONLY");
    }
}
