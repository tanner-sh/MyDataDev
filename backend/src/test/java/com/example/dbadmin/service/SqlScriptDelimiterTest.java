package com.example.dbadmin.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批量脚本的分段兼容：PL/SQL 块不写 /、SQL*Plus 指令、DBeaver 的自定义分隔符与空行分隔。
 *
 * <p>判据一律是「切出来的每一条是什么」，不只是条数 —— 条数对了而边界错了，执行时同样报错。</p>
 */
class SqlScriptDelimiterTest {
    private final SqlScriptSplitter splitter = new SqlScriptSplitter();

    private List<String> split(String script, String dbType) {
        return splitter.split(script, dbType).stream().map(SqlScriptSplitter.StatementSegment::sql).toList();
    }

    private List<String> split(String script, String dbType, SqlScriptDelimiter delimiter) {
        return splitter.parse(script, dbType, delimiter).segments().stream().map(SqlScriptSplitter.StatementSegment::sql).toList();
    }

    @Nested
    class PlsqlBlocksWithoutSlash {
        @Test
        void closesProceduresTriggersAndAnonymousBlocksWhenTheNextStatementStarts() {
            String script = """
                    CREATE OR REPLACE PROCEDURE p1 AS
                    BEGIN
                      UPDATE t SET a = 1;
                      COMMIT;
                    END;
                    INSERT INTO t VALUES (1);
                    BEGIN
                      NULL;
                    END;
                    SELECT 1 FROM dual;
                    create or replace function f return number is
                    begin
                      return 1;
                    end f;
                    CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW
                    BEGIN
                      :NEW.a := 1;
                    END;
                    DELETE FROM t;
                    """;
            assertThat(split(script, "oracle")).containsExactly(
                    "CREATE OR REPLACE PROCEDURE p1 AS\nBEGIN\n  UPDATE t SET a = 1;\n  COMMIT;\nEND;",
                    "INSERT INTO t VALUES (1)",
                    "BEGIN\n  NULL;\nEND;",
                    "SELECT 1 FROM dual",
                    "create or replace function f return number is\nbegin\n  return 1;\nend f;",
                    "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW\nBEGIN\n  :NEW.a := 1;\nEND;",
                    "DELETE FROM t");
        }

        @Test
        void aNestedSubprogramInTheDeclarationSectionDoesNotEndTheOuterProcedure() {
            // 内层 END; 之后深度确实回到 0，但下一行是外层的 BEGIN 或一个游标声明 —— 都不是新语句的开头。
            String script = """
                    CREATE OR REPLACE PROCEDURE outer_p AS
                      PROCEDURE inner_p IS BEGIN NULL; END;
                      CURSOR c IS
                        SELECT * FROM t;
                    BEGIN
                      inner_p;
                      UPDATE t SET a = CASE WHEN a > 1 THEN 1 ELSE 0 END;
                      CASE a WHEN 1 THEN NULL; ELSE NULL; END CASE;
                      FOR r IN c LOOP NULL; END LOOP;
                      IF a = 1 THEN NULL; END IF;
                    END outer_p;
                    INSERT INTO t VALUES (2);
                    """;
            List<String> statements = split(script, "oceanbase-oracle");
            assertThat(statements).hasSize(2);
            assertThat(statements.get(0)).startsWith("CREATE OR REPLACE PROCEDURE outer_p").endsWith("END outer_p;");
            assertThat(statements.get(1)).isEqualTo("INSERT INTO t VALUES (2)");
        }

        @Test
        void packagesTypesAndCompoundTriggersStartOneLevelDeep() {
            String script = """
                    CREATE OR REPLACE PACKAGE pk AS
                      PROCEDURE x;
                    END pk;
                    CREATE OR REPLACE PACKAGE BODY pk AS
                      PROCEDURE x IS BEGIN NULL; END;
                    END pk;
                    CREATE OR REPLACE TYPE t_obj AS OBJECT (a NUMBER);
                    CREATE OR REPLACE TYPE BODY t_obj AS
                      MEMBER FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;
                    END;
                    CREATE OR REPLACE TRIGGER ct FOR INSERT ON t COMPOUND TRIGGER
                      BEFORE STATEMENT IS BEGIN NULL; END BEFORE STATEMENT;
                    END ct;
                    COMMIT;
                    """;
            assertThat(split(script, "oracle")).extracting(sql -> sql.lines().findFirst().orElse(""))
                    .containsExactly(
                            "CREATE OR REPLACE PACKAGE pk AS",
                            "CREATE OR REPLACE PACKAGE BODY pk AS",
                            "CREATE OR REPLACE TYPE t_obj AS OBJECT (a NUMBER);",
                            "CREATE OR REPLACE TYPE BODY t_obj AS",
                            "CREATE OR REPLACE TRIGGER ct FOR INSERT ON t COMPOUND TRIGGER",
                            "COMMIT");
        }

        @Test
        void keywordsInsideStringsAndCommentsDoNotMoveTheDepth() {
            String script = "BEGIN\n  v := 'END; BEGIN';\n  -- END;\n  /* BEGIN */\n  NULL;\nEND;\nSELECT 1 FROM dual;";
            assertThat(split(script, "oracle")).containsExactly("BEGIN\n  v := 'END; BEGIN';\n  -- END;\n  /* BEGIN */\n  NULL;\nEND;", "SELECT 1 FROM dual");
        }

        @Test
        void slashStillWorksAndMysqlRoutinesAreUnchanged() {
            assertThat(split("BEGIN NULL; END;\n/\nBEGIN NULL; END;\n/\n", "oracle")).hasSize(2);
            assertThat(split("CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\nEND;\nSELECT 2;", "mysql"))
                    .containsExactly("CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\nEND", "SELECT 2");
        }
    }

    @Nested
    class SqlPlusCommands {
        @Test
        void ignoresDisplaySettingsAndReportsThem() {
            var result = splitter.parse("SET DEFINE OFF;\nset serveroutput on size 1000000\nREM setup\nPROMPT 正在建表...\nSPOOL out.log\n"
                    + "INSERT INTO t VALUES ('a&b');\nSET TRANSACTION READ ONLY;\nSHOW ERRORS\nWHENEVER SQLERROR EXIT SQL.SQLCODE\n", "oracle", SqlScriptDelimiter.AUTO);
            assertThat(result.segments()).extracting(SqlScriptSplitter.StatementSegment::sql)
                    // SET TRANSACTION 是真 SQL，不在 SQL*Plus 设置清单里，照常发给数据库。
                    .containsExactly("INSERT INTO t VALUES ('a&b')", "SET TRANSACTION READ ONLY");
            assertThat(result.notices()).anySatisfy(notice -> assertThat(notice).contains("SET DEFINE OFF"))
                    .anySatisfy(notice -> assertThat(notice).contains("DBMS_OUTPUT"))
                    .anySatisfy(notice -> assertThat(notice).isEqualTo("PROMPT 正在建表..."))
                    .anySatisfy(notice -> assertThat(notice).contains("SHOW ERRORS"))
                    .anySatisfy(notice -> assertThat(notice).contains("WHENEVER"));
        }

        @Test
        void exitStopsTheScriptAndExecBecomesAnAnonymousBlock() {
            var result = splitter.parse("exec dbms_stats.gather_table_stats('APP', 'T');\nCOMMIT;\nEXIT\nDROP TABLE t;\n", "oceanbase-oracle", SqlScriptDelimiter.AUTO);
            assertThat(result.segments()).extracting(SqlScriptSplitter.StatementSegment::sql)
                    .containsExactly("BEGIN dbms_stats.gather_table_stats('APP', 'T'); END;", "COMMIT");
            assertThat(result.notices()).anySatisfy(notice -> assertThat(notice).contains("EXIT"));
        }

        @Test
        void rejectsCommandsThatWouldNeedAnotherFileAConnectionOrContinueOnError() {
            assertThatThrownBy(() -> split("@other.sql\n", "oracle")).hasMessageContaining("@");
            assertThatThrownBy(() -> split("CONNECT app/secret@db\n", "oracle")).hasMessageContaining("CONNECT");
            assertThatThrownBy(() -> split("WHENEVER SQLERROR CONTINUE\nSELECT 1 FROM dual;", "oracle")).hasMessageContaining("遇错继续");
        }

        @Test
        void onlyOracleConnectionsTreatThemAsClientCommands() {
            assertThatThrownBy(() -> split("PROMPT hi\nSELECT 1;", "mysql")).hasMessageContaining("客户端指令");
            // generic 走 Oracle 词法，但 MySQL 的 SHOW ERRORS 是真 SQL，不能被当成指令吞掉。
            assertThat(split("SHOW ERRORS;", "generic")).containsExactly("SHOW ERRORS");
        }
    }

    @Nested
    class CustomDelimiter {
        private static final String DBEAVER = """
                -- DBeaver客户端分隔符为 !；设置说明见README。!不发送数据库。

                -- 第 001 条
                DECLARE
                  V_N NUMBER;
                BEGIN
                  SELECT COUNT(*) INTO V_N FROM ALL_TABLES WHERE TABLE_NAME='T_LOCK';
                  IF V_N=0 THEN EXECUTE IMMEDIATE 'CREATE TABLE T_LOCK(A VARCHAR2(64))'; END IF;
                END;
                !

                -- 第 002 条
                INSERT INTO T_LOCK(A) SELECT 'X!' FROM DUAL
                !
                UPDATE T_LOCK SET A = 'Y'; -- 末尾带分号也行
                !
                SELECT 'last one without a delimiter' FROM DUAL
                """;

        @Test
        void splitsOnlyOnTheStandaloneDelimiterLine() {
            List<String> statements = split(DBEAVER, "oceanbase-oracle", SqlScriptDelimiter.custom("!"));
            assertThat(statements).hasSize(4);
            assertThat(statements.get(0)).contains("DECLARE").endsWith("END IF;\nEND;");
            assertThat(statements.get(1)).endsWith("INSERT INTO T_LOCK(A) SELECT 'X!' FROM DUAL");
            assertThat(statements.get(2)).isEqualTo("UPDATE T_LOCK SET A = 'Y' -- 末尾带分号也行");
            assertThat(statements.get(3)).isEqualTo("SELECT 'last one without a delimiter' FROM DUAL");
        }

        @Test
        void aDelimiterLineInsideAStringIsNotABoundary() {
            assertThat(split("SELECT '\n!\n' FROM dual\n!\nSELECT 2 FROM dual\n", "oracle", SqlScriptDelimiter.custom("!")))
                    .containsExactly("SELECT '\n!\n' FROM dual", "SELECT 2 FROM dual");
        }

        @Test
        void worksForOtherDatabasesToo() {
            assertThat(split("CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\nEND;\n$$\nSELECT 2\n$$\n", "mysql", SqlScriptDelimiter.custom("$$")))
                    .containsExactly("CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\nEND", "SELECT 2");
        }
    }

    @Nested
    class BlankLineDelimiter {
        @Test
        void blankLinesEndStatementsButNotInsideBlocksOrRoutines() {
            String script = """
                    SELECT 1 FROM dual

                    INSERT INTO t VALUES (1);
                    UPDATE t SET a = 2

                    BEGIN
                      NULL;

                      NULL;
                    END;
                    /

                    -- just a comment

                    DELETE FROM t
                    """;
            assertThat(split(script, "oracle", SqlScriptDelimiter.BLANK_LINE)).containsExactly(
                    "SELECT 1 FROM dual",
                    "INSERT INTO t VALUES (1)",
                    "UPDATE t SET a = 2",
                    "BEGIN\n  NULL;\n\n  NULL;\nEND;",
                    "-- just a comment\n\nDELETE FROM t");
            assertThat(split("CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\n\n  SELECT 2;\nEND;\n\nSELECT 3\n", "mysql", SqlScriptDelimiter.BLANK_LINE))
                    .containsExactly("CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\n\n  SELECT 2;\nEND", "SELECT 3");
        }
    }

    @Nested
    class Detection {
        @Test
        void recognisesStandaloneSymbolLines() {
            assertThat(SqlScriptDelimiter.detect("SELECT 1\n!\nSELECT 2\n!\n", "oracle")).contains("!");
            assertThat(SqlScriptDelimiter.detect("SELECT 1\n  @@  \nSELECT 2\n", "mysql")).contains("@@");
        }

        @Test
        void ignoresOrdinaryScriptsCommentsStringsAndDollarQuotes() {
            assertThat(SqlScriptDelimiter.detect("SELECT 1;\nBEGIN NULL; END;\n/\n", "oracle")).isEmpty();
            assertThat(SqlScriptDelimiter.detect("/*\n * header\n*/\nSELECT 1;\n", "oracle")).isEmpty();
            assertThat(SqlScriptDelimiter.detect("SELECT '\n!\n' FROM dual;\n", "oracle")).isEmpty();
            assertThat(SqlScriptDelimiter.detect("CREATE FUNCTION f() RETURNS int AS\n$$\nSELECT 1\n$$ LANGUAGE sql;\n", "postgresql")).isEmpty();
        }
    }

    @Test
    void delimiterSettingsRoundTripAndRejectNonsense() {
        assertThat(SqlScriptDelimiter.decode(SqlScriptDelimiter.custom("!").encode())).isEqualTo(SqlScriptDelimiter.custom("!"));
        assertThat(SqlScriptDelimiter.decode(SqlScriptDelimiter.BLANK_LINE.encode())).isEqualTo(SqlScriptDelimiter.BLANK_LINE);
        assertThat(SqlScriptDelimiter.decode(null)).isEqualTo(SqlScriptDelimiter.AUTO);
        assertThat(SqlScriptDelimiter.of("custom", " !! ")).isEqualTo(SqlScriptDelimiter.custom("!!"));
        assertThatThrownBy(() -> SqlScriptDelimiter.custom(";")).hasMessageContaining("分号");
        assertThatThrownBy(() -> SqlScriptDelimiter.custom("a b")).hasMessageContaining("非空白");
        assertThatThrownBy(() -> SqlScriptDelimiter.of("SEMI", null)).hasMessageContaining("不支持");
    }
}
