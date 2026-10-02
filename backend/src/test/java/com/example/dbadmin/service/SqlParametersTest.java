package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.SqlParameter;
import com.example.dbadmin.dto.ApiDtos.SqlParameterDefinition;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SqlParametersTest {
    @Test void ignoresQuotedTextCommentsAndPostgresCastsAndKeepsRepeatedBindings() {
        var parsed = SqlParameters.parse("select :id::bigint, :id, ':fake', \"a:b\", $$:hidden$$, $tag$:hidden$tag$ /* :comment /* :nested */ */ -- :line\n", "postgresql");
        assertThat(parsed.names()).containsExactly("id");
        assertThat(parsed.occurrences()).containsExactly("id", "id");
        assertThat(parsed.sql()).startsWith("select ?::bigint, ?, ':fake'");
        assertThat(SqlParameters.parse("select ARRAY[:id], E'escaped\\\':hidden', :next", "postgresql").names()).containsExactly("id", "next");
        assertThat(SqlParameters.parse("select $$:hidden$$, :id", "h2").names()).containsExactly("id");
        assertThat(SqlParameters.parse("select q'[don't :bind]', :id from dual", "oracle").names()).containsExactly("id");
        assertThat(SqlParameters.parse("select [x:y]],z], :id", "sqlserver").names()).containsExactly("id");
        assertThat(SqlParameters.parse("select ':escaped\\\' :hidden', `a:b`, :id # :comment\n", "mysql").names()).containsExactly("id");
    }
    @Test void rejectsMalformedOrAmbiguousParameters() {
        assertThatThrownBy(() -> SqlParameters.parse("select ':x", "h2")).hasMessageContaining("未闭合");
        assertThatThrownBy(() -> SqlParameters.parse("select 1 /*! :x */", "mysql")).hasMessageContaining("可执行注释");
        var parsed = SqlParameters.parse("select :id", "h2");
        assertThatThrownBy(() -> SqlParameters.validateValues(parsed, Map.of())).hasMessageContaining("完全一致");
        assertThatThrownBy(() -> SqlParameters.validateValues(parsed, Map.of("id", new SqlParameter("INTEGER", "9223372036854775808")))).hasMessageContaining("格式");
        assertThatThrownBy(() -> SqlParameters.validateValues(parsed, Map.of("id", new SqlParameter("BOOLEAN", "yes")))).hasMessageContaining("格式");
        assertThatThrownBy(() -> SqlParameters.validateValues(parsed, Map.of("id", new SqlParameter("DECIMAL", "1e999999999")))).hasMessageContaining("格式");
        assertThatThrownBy(() -> SqlParameters.validateValues(parsed, Map.of("id", new SqlParameter("DATE", "2026-02-30")))).hasMessageContaining("格式");
        assertThatThrownBy(() -> SqlParameters.validateValues(SqlParameters.parse("select ?, :id", "h2"), Map.of("id", new SqlParameter("TEXT", "x")))).hasMessageContaining("混用");
    }
    @Test void bindsValuesAsDataAndPreservesNullEmptyAndPrecision() throws Exception {
        var parsed = SqlParameters.parse("select cast(:text as varchar), cast(:text as varchar), cast(:empty as varchar), cast(:nil as varchar), cast(:big as bigint), cast(:decimal as decimal(25,5)), cast(:date as date), cast(:time as timestamp), cast(:bool as boolean)", "h2");
        var values = Map.of("text", new SqlParameter("TEXT", "x'; DROP TABLE users; --"), "empty", new SqlParameter("TEXT", ""),
                "nil", new SqlParameter("TEXT", null), "big", new SqlParameter("INTEGER", "9223372036854775807"),
                "decimal", new SqlParameter("DECIMAL", "12345678901234567890.12345"), "date", new SqlParameter("DATE", "2026-10-01"),
                "time", new SqlParameter("TIMESTAMP", "2026-10-01 12:34:56.123456"), "bool", new SqlParameter("BOOLEAN", "false"));
        SqlParameters.validateValues(parsed, values);
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:parameters"); var statement = connection.prepareStatement(parsed.sql())) {
            SqlParameters.bind(statement, parsed, values);
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(values.get("text").value());
                assertThat(rs.getString(2)).isEqualTo(rs.getString(1));
                assertThat(rs.getString(3)).isEmpty(); assertThat(rs.getString(4)).isNull();
                assertThat(rs.getLong(5)).isEqualTo(Long.MAX_VALUE);
                assertThat(rs.getBigDecimal(6).toPlainString()).isEqualTo("12345678901234567890.12345");
                assertThat(rs.getDate(7).toString()).isEqualTo("2026-10-01");
                assertThat(rs.getTimestamp(8).toString()).isEqualTo("2026-10-01 12:34:56.123456");
                assertThat(rs.getBoolean(9)).isFalse();
            }
        }
    }
    @Test void definitionsMustMatchQueryAndDefaultsMustBeValid() {
        var definitions = List.of(new SqlParameterDefinition("id", "INTEGER", true, "12"));
        SqlParameters.validateDefinitions("select :id", "h2", definitions);
        assertThatThrownBy(() -> SqlParameters.validateDefinitions("select :other", "h2", definitions)).hasMessageContaining("完全一致");
        assertThatThrownBy(() -> SqlParameters.validateDefinitions("select :id; delete from users", "h2", definitions)).hasMessageContaining("单条");
        assertThatThrownBy(() -> SqlParameters.validateDefinitions("delete from users where id=:id", "h2", definitions)).hasMessageContaining("SELECT");
        assertThatThrownBy(() -> SqlParameters.validateDefinitions("select :id DELETE FROM users", "sqlserver", definitions)).hasMessageContaining("SELECT");
        assertThatThrownBy(() -> SqlParameters.validateDefinitions("select :id EXEC dangerous_proc", "sqlserver", definitions)).hasMessageContaining("SELECT");
        assertThatThrownBy(() -> SqlParameters.validateDefinitions("select :id", "h2", List.of(definitions.get(0), definitions.get(0)))).hasMessageContaining("重复");
    }
}
