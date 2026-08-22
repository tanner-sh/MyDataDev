package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionProfileTest {
    private static final SqlScriptSplitter SPLITTER = new SqlScriptSplitter();
    private static final SqlStatementClassifier CLASSIFIER = new SqlStatementClassifier();

    @Test
    void trimsAndDropsBlankProfileText() {
        assertThat(ConnectionProfile.normalizeGroup("  订单业务  ")).isEqualTo("订单业务");
        assertThat(ConnectionProfile.normalizeGroup("   ")).isNull();
        assertThat(ConnectionProfile.normalizeGroup(null)).isNull();
        assertThat(ConnectionProfile.normalizeDefaultSchema(" public ")).isEqualTo("public");
        assertThat(ConnectionProfile.normalizeDescription("")).isNull();
    }

    @Test
    void rejectsOverlongProfileText() {
        assertThatThrownBy(() -> ConnectionProfile.normalizeGroup("g".repeat(ConnectionProfile.MAX_GROUP_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesTagsToACanonicalCommaSeparatedList() {
        assertThat(ConnectionProfile.normalizeTags(" 核心 , 只读 ,核心 ")).isEqualTo("核心,只读");
    }

    @Test
    void deduplicatesTagsIgnoringCaseButKeepsFirstSpelling() {
        assertThat(ConnectionProfile.parseTags("Core,core,CORE")).containsExactly("Core");
    }

    @Test
    void acceptsFullWidthCommaBecauseChineseInputProducesIt() {
        assertThat(ConnectionProfile.parseTags("核心，只读")).containsExactly("核心", "只读");
    }

    @Test
    void blankTagInputBecomesNullRatherThanEmptyString() {
        assertThat(ConnectionProfile.normalizeTags(" , , ")).isNull();
        assertThat(ConnectionProfile.parseTags(null)).isEmpty();
    }

    @Test
    void rejectsTooManyOrTooLongTags() {
        String many = String.join(",", java.util.Collections.nCopies(ConnectionProfile.MAX_TAGS + 1, "t").toArray(String[]::new));
        // nCopies 的元素相同会被去重，这里生成互不相同的标签。
        StringBuilder distinct = new StringBuilder();
        for (int index = 0; index <= ConnectionProfile.MAX_TAGS; index++) distinct.append("tag").append(index).append(',');
        assertThatThrownBy(() -> ConnectionProfile.parseTags(distinct.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(ConnectionProfile.MAX_TAGS));
        assertThat(ConnectionProfile.parseTags(many)).hasSize(1);
        assertThatThrownBy(() -> ConnectionProfile.parseTags("x".repeat(ConnectionProfile.MAX_TAG_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitsInitSqlIntoSessionStatements() {
        List<String> statements = ConnectionProfile.initStatements(
                "SET SESSION sql_mode='STRICT_TRANS_TABLES';\nSET time_zone = '+08:00';", SPLITTER, CLASSIFIER);
        assertThat(statements).containsExactly("SET SESSION sql_mode='STRICT_TRANS_TABLES'", "SET time_zone = '+08:00'");
    }

    @Test
    void allowsOracleAlterSessionEvenThoughItLooksLikeDdl() {
        assertThat(ConnectionProfile.initStatements("ALTER SESSION SET CURRENT_SCHEMA = APP", SPLITTER, CLASSIFIER))
                .containsExactly("ALTER SESSION SET CURRENT_SCHEMA = APP");
    }

    @Test
    void rejectsNonSessionStatementsBecauseTheyWouldRunOnReadOnlyMcpSessionsToo() {
        assertThatThrownBy(() -> ConnectionProfile.initStatements("DELETE FROM users", SPLITTER, CLASSIFIER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话级设置");
        assertThatThrownBy(() -> ConnectionProfile.initStatements("SET time_zone='+08:00'; DROP TABLE users", SPLITTER, CLASSIFIER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DROP TABLE users");
    }

    @Test
    void rejectsTooManyInitStatements() {
        String script = "SET a=1;".repeat(ConnectionProfile.MAX_INIT_STATEMENTS + 1);
        assertThatThrownBy(() -> ConnectionProfile.initStatements(script, SPLITTER, CLASSIFIER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(ConnectionProfile.MAX_INIT_STATEMENTS));
    }

    @Test
    void emptyInitSqlYieldsNoStatements() {
        assertThat(ConnectionProfile.initStatements(null, SPLITTER, CLASSIFIER)).isEmpty();
        assertThat(ConnectionProfile.initStatements("  \n ;; \n", SPLITTER, CLASSIFIER)).isEmpty();
        assertThat(ConnectionProfile.normalizeInitSql("  ")).isNull();
    }
}
