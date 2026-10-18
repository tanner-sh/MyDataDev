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

    @Test void keepsMySqlRoutineBodiesWholeWithoutDelimiter() {
        String procedure = """
                CREATE DEFINER=`root`@`%` PROCEDURE p_log(IN msg VARCHAR(200))
                BEGIN
                  DECLARE n INT DEFAULT 0;
                  IF msg IS NULL THEN SET msg = 'a;b'; END IF;
                  CASE n WHEN 0 THEN SET n = 1; ELSE SET n = 2; END CASE;
                  lbl: WHILE n < 3 DO SET n = n + 1; END WHILE lbl;
                  SELECT CASE WHEN n > 1 THEN 'x' END || CASE WHEN n > 2 THEN 'y' END INTO msg;
                  INSERT INTO t_log(msg) VALUES (msg);
                END""";
        for (String dbType : new String[]{"mysql", "mariadb", "oceanbase-mysql"}) {
            assertThat(splitter.split(procedure, dbType)).extracting(SqlScriptSplitter.StatementSegment::sql).containsExactly(procedure);
            assertThat(splitter.split(procedure + ";\nSELECT 2;", dbType)).hasSize(2);
        }
        assertThat(splitter.split("CREATE TRIGGER tr BEFORE INSERT ON t FOR EACH ROW BEGIN SET NEW.a = 1; END; SELECT 1", "mysql")).hasSize(2);
        assertThat(splitter.split("CREATE EVENT e ON SCHEDULE EVERY 1 DAY DO BEGIN DELETE FROM t WHERE id < 0; END", "mysql")).hasSize(1);
        assertThat(splitter.split("BEGIN NOT ATOMIC SELECT 1; SELECT 2; END; SELECT 3", "mariadb")).hasSize(2);
        assertThat(splitter.split("CREATE TRIGGER tr AFTER INSERT ON t BEGIN UPDATE t SET a = 1; END; SELECT 1", "sqlite")).hasSize(2);
    }
    @Test void mySqlTransactionsAndExpressionsStillSplitOnSemicolons() {
        assertThat(splitter.split("BEGIN; UPDATE t SET a = 1; COMMIT;", "mysql")).hasSize(3);
        assertThat(splitter.split("SELECT CASE WHEN a THEN 1 END FROM t; SELECT 2", "mysql")).hasSize(2);
        assertThat(splitter.split("CREATE FUNCTION f() RETURNS INT RETURN 1; SELECT f()", "mysql")).hasSize(2);
        assertThat(splitter.split("CREATE TABLE `begin` (`end` INT); SELECT 1", "mysql")).hasSize(2);
        assertThat(splitter.split("DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT 1; END$$\nDELIMITER ;\nSELECT 2;", "mysql")).hasSize(2);
    }
    @Test void keepsOceanBaseOracleAnonymousBlockWhole() {
        String block = """
                DECLARE
                  V_EXISTS NUMBER;
                BEGIN
                  SELECT COUNT(*) INTO V_EXISTS FROM ALL_TABLES WHERE OWNER = 'YY_FBIP_ETL' AND
                TABLE_NAME = 'YS_VIS_CH_QUOT_BOND_SHC_VAL_01';
                  IF V_EXISTS = 0 THEN
                    EXECUTE IMMEDIATE 'CREATE TABLE YY_FBIP_ETL.YS_VIS_CH_QUOT_BOND_SHC_VAL_01
                AS SELECT CAST(NULL AS VARCHAR2(32)) AS CH_RUN_ID, s.* FROM
                YY_FBIP_DATACENTER.BOND_DR_VALUATIONS s WHERE 1 = 0';
                  END IF;
                END;""";
        assertThat(splitter.split(block + "\n/\n", "oceanbase-oracle")).extracting(SqlScriptSplitter.StatementSegment::sql).containsExactly(block);
    }
    @Test void anonymousBlockWithExceptionHandlerIsOneUnitWithOrWithoutSlash() {
        String block = "DECLARE V VARCHAR2(200);\nBEGIN\n  YY_FBIP_ETL.SP_X(V, NULL);\nEXCEPTION WHEN OTHERS THEN NULL;\nEND;";
        for (String script : new String[]{block, block + "\n", block + "\n/", block + "\n/\n"}) {
            assertThat(splitter.split(script, "oceanbase-oracle")).extracting(SqlScriptSplitter.StatementSegment::sql).containsExactly(block);
        }
    }
    @Test void lastProcedureRunsWhateverTheFileEndingLooksLike() {
        String first = "CREATE OR REPLACE PROCEDURE A.P1 AS\nBEGIN\n  NULL;\nEXCEPTION WHEN OTHERS THEN RAISE;\nEND P1;";
        String last = "CREATE OR REPLACE PROCEDURE A.P2 AS\nBEGIN\n  NULL;\nEND;";
        for (String ending : new String[]{"", "\n", "\n/", "\n/\n"}) {
            assertThat(splitter.split(first + "\n/\n" + last + ending, "oceanbase-oracle"))
                    .extracting(SqlScriptSplitter.StatementSegment::sql).containsExactly(first, last);
        }
    }
    @Test void missingSlashBeforeNextCreateIsRejectedInsteadOfMergingTwoObjects() {
        String script = "CREATE OR REPLACE PROCEDURE A.P1 AS\nBEGIN\n  NULL;\nEND P1;\n-- next\nCREATE OR REPLACE PROCEDURE A.P2 AS\nBEGIN\n  NULL;\nEND;\n/";
        assertThatThrownBy(() -> splitter.split(script, "oceanbase-oracle")).hasMessageContaining("第 6 行").hasMessageContaining("缺少独占行 /");
        // 例程正文里的 CREATE 只会出现在字符串或注释里，不能被当成漏写分隔符。
        assertThat(splitter.split("BEGIN\n  NULL;\nEXECUTE IMMEDIATE '\nCREATE TABLE T (A NUMBER)';\nEND;\n/", "oracle")).hasSize(1);
        assertThat(splitter.split("BEGIN\n  NULL;\n/*\nCREATE TABLE T (A NUMBER);\n*/\nEND;\n/", "oracle")).hasSize(1);
    }
    @Test void rejectsUnterminatedStringsBeforeAnyExecution() {
        assertThatThrownBy(() -> splitter.split("select 1; select 'bad", "oracle")).hasMessageContaining("未闭合").hasMessageContaining("行");
    }
}
