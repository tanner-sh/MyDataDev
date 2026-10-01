package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class SqlScriptParserTest {
    private final SqlScriptSplitter splitter = new SqlScriptSplitter();
    @Test void preservesCommentPrefixedBlocksAndTransactionBodies() {
        String script = """
                -- installation
                /* keep procedure intact */
                CREATE OR REPLACE
                PROCEDURE p AS
                  PRAGMA AUTONOMOUS_TRANSACTION;
                BEGIN
                  INSERT INTO t VALUES (q'[a;b]');
                  COMMIT;
                EXCEPTION WHEN OTHERS THEN ROLLBACK; RAISE;
                END;
                /
                -- anonymous
                DECLARE
                  n NUMBER;
                BEGIN
                  n := 1;
                END;
                /
                COMMIT;
                """;
        var units = splitter.split(script, "oceanbase-oracle");
        assertThat(units).hasSize(3);
        assertThat(units.get(0).sql()).contains("COMMIT;", "END;").doesNotEndWith("/");
        assertThat(SqlScriptSyntax.transaction(units.get(0).sql())).isEqualTo(SqlScriptSyntax.Transaction.NONE);
        assertThat(SqlScriptSyntax.transaction(units.get(1).sql())).isEqualTo(SqlScriptSyntax.Transaction.NONE);
        assertThat(SqlScriptSyntax.transaction(units.get(2).sql())).isEqualTo(SqlScriptSyntax.Transaction.COMMIT);
    }
    @Test void sourceOffsetsAndLineNumbersPreserveCrLfAndBom() throws Exception {
        String text = "\ufeff  -- comment\r\nBEGIN\r\n NULL;\r\nEND;\r\n/\r\n  COMMIT;";
        var units = new ArrayList<SqlScriptParser.Unit>();
        SqlScriptParser.read(new StringReader(text), "oracle", 10000, units::add);
        assertThat(units).hasSize(2);
        assertThat(units.get(0).startLine()).isEqualTo(1);
        assertThat(units.get(0).endLine()).isEqualTo(4);
        assertThat(units.get(1).startLine()).isEqualTo(6);
        for (var unit : units) assertThat(text.substring((int)unit.startOffset(), (int)unit.endOffset())).isEqualTo(unit.sql());
    }
    @Test void sharedParserKeepsDialectSeparatorsAndNeverRepeatsSlash() {
        assertThat(splitter.split("BEGIN NULL; END;\n/\n/\nSELECT 1 FROM dual;\n/", "oracle")).hasSize(2);
        assertThat(splitter.split("DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT 1; END$$\nDELIMITER ;\nSELECT 2;", "mysql")).hasSize(2);
        assertThat(splitter.split("SELECT 1; SELECT 2;\nGO\nSELECT 3;\nGO", "sqlserver")).hasSize(2);
        assertThat(splitter.split("DO $$ BEGIN PERFORM 1; END $$; SELECT 2;", "postgresql")).hasSize(2);
    }
    @Test void commentMarkersInLiteralsDoNotHideTheBlockEnding() {
        assertThat(splitter.split("BEGIN x := '-- not a comment'; END;\n/", "oracle")).hasSize(1);
        assertThat(splitter.split("BEGIN x := q'[/* not a comment]'; END; -- tail\n/", "oracle")).hasSize(1);
        assertThatThrownBy(() -> splitter.split("BEGIN NULL;\n/", "oracle")).hasMessageContaining("未完整结束");
    }

    @Test void preservesCarriageReturnOnlyScriptsAndCommentBoundaries() throws Exception {
        String sql = "-- header\rBEGIN/* block */ NULL; END;\r/\rCOMMIT;";
        var units = new ArrayList<SqlScriptParser.Unit>();
        SqlScriptParser.read(new StringReader(sql), "oracle", 10000, units::add);
        assertThat(units).hasSize(2);
        assertThat(units.get(1).startLine()).isEqualTo(4);
        for (var unit : units) assertThat(sql.substring((int)unit.startOffset(), (int)unit.endOffset())).isEqualTo(unit.sql());
        assertThat(splitter.split("CREATE PROCEDURE p AS BEGIN NULL; END/* name */p;\n/", "oracle")).hasSize(1);
        assertThat(new SqlStatementClassifier().classify(units.get(0).sql())).isEqualTo(SqlStatementClassifier.Kind.UNKNOWN);
    }

    @Test void rejectsUnterminatedStringsBeforeAnyExecution() {
        assertThatThrownBy(() -> splitter.split("select 1; select 'bad", "oracle")).hasMessageContaining("未闭合").hasMessageContaining("行");
    }
}
