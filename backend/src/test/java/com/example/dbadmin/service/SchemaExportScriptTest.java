package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaExportScriptTest {
    private final SqlScriptSplitter splitter = new SqlScriptSplitter();

    private List<String> split(String script, String dbType) {
        return splitter.split(script, dbType).stream().map(segment -> segment.sql().strip()).toList();
    }

    @Test
    void oracleRoutinesEndWithASlashAndTheScriptSplitsBackIntoOneStatementPerObject() {
        String table = SchemaExportScript.statement("oracle", "TABLE", "\n  CREATE TABLE \"APP\".\"T\" (\"ID\" NUMBER)\n");
        String procedure = SchemaExportScript.statement("oracle", "PROCEDURE",
                "CREATE OR REPLACE PROCEDURE \"APP\".\"P\" AS\nBEGIN\n  UPDATE t SET id = 1;\n  COMMIT;\nEND P;");
        String view = SchemaExportScript.statement("oracle", "VIEW", "CREATE OR REPLACE VIEW \"APP\".\"V\" AS SELECT id FROM t;");
        assertThat(procedure).endsWith("END P;\n/");
        assertThat(view).endsWith("FROM t;");
        List<String> statements = split(table + "\n\n" + procedure + "\n\n" + view + "\n", "oracle");
        assertThat(statements).hasSize(3);
        assertThat(statements.get(1)).contains("COMMIT;").endsWith("END P;");
    }

    @Test
    void oracleTriggerEnableClauseBecomesItsOwnStatementAfterTheBlock() {
        String trigger = SchemaExportScript.statement("oracle", "TRIGGER", """
                CREATE OR REPLACE EDITIONABLE TRIGGER "APP"."TRG" BEFORE INSERT ON "APP"."T" FOR EACH ROW
                BEGIN
                  :NEW.ID := 1;
                END;
                ALTER TRIGGER "APP"."TRG" ENABLE""");
        assertThat(trigger).contains("END;\n/\nALTER TRIGGER \"APP\".\"TRG\" ENABLE;");
        List<String> statements = split(trigger + "\nCREATE TABLE x (id NUMBER);\n", "oracle");
        assertThat(statements).hasSize(3);
        assertThat(statements.get(1)).startsWith("ALTER TRIGGER");
    }

    @Test
    void mysqlRoutinesAreWrappedInDelimiterAndStillSplitCorrectly() {
        String procedure = SchemaExportScript.statement("mysql", "PROCEDURE",
                "CREATE PROCEDURE `p`()\nBEGIN\n  SELECT 1;\n  SELECT 2;\nEND");
        assertThat(procedure).startsWith("DELIMITER ;;\n").endsWith("END;;\nDELIMITER ;");
        String script = "CREATE TABLE `t` (`id` int);\n\n" + procedure + "\n\n"
                + SchemaExportScript.statement("mysql", "VIEW", "CREATE VIEW `v` AS select 1 AS `x`") + "\n";
        List<String> statements = split(script, "mysql");
        assertThat(statements).hasSize(3);
        assertThat(statements.get(1)).contains("SELECT 1;").contains("SELECT 2;");
    }

    @Test
    void sqlServerPutsEachDefinitionInItsOwnBatch() {
        String script = SchemaExportScript.statement("sqlserver", "TABLE", "CREATE TABLE [dbo].[t] ([id] int)") + "\n"
                + SchemaExportScript.statement("sqlserver", "PROCEDURE", "CREATE PROCEDURE [dbo].[p] AS\nBEGIN\n  SELECT 1;\nEND;") + "\n";
        assertThat(script).contains("[id] int)\nGO").contains("END\nGO");
        assertThat(split(script, "sqlserver")).hasSize(2);
    }

    @Test
    void postgresqlFunctionBodiesKeepTheirDollarQuotedSemicolons() {
        String function = SchemaExportScript.statement("postgresql", "FUNCTION",
                "CREATE OR REPLACE FUNCTION public.f() RETURNS int LANGUAGE plpgsql AS $function$\nBEGIN\n  RETURN 1;\nEND;\n$function$\n");
        assertThat(split(function + "\nCREATE VIEW v AS SELECT 1;\n", "postgresql")).hasSize(2);
    }

    @Test
    void stripsMysqlDefinerOnlyFromTheHeader() {
        String view = "CREATE ALGORITHM=UNDEFINED DEFINER=`app`@`10.0.0.%` SQL SECURITY DEFINER VIEW `v` AS select 'DEFINER=`x`@`y`' AS `note`";
        assertThat(SchemaExportScript.stripDefiner(view))
                .isEqualTo("CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `v` AS select 'DEFINER=`x`@`y`' AS `note`");
        assertThat(SchemaExportScript.stripDefiner("CREATE DEFINER=root@localhost PROCEDURE `p`() SELECT 1"))
                .isEqualTo("CREATE PROCEDURE `p`() SELECT 1");
        assertThat(SchemaExportScript.stripDefiner("CREATE TABLE t (id int)")).isEqualTo("CREATE TABLE t (id int)");
    }

    @Test
    void findsReferencedNamesButNotInsideCommentsOrStrings() {
        var names = SchemaExportScript.referencedIdentifiers("""
                CREATE VIEW "App"."V_Sum" AS
                -- uses OLD_TABLE no more
                /* LEGACY_VIEW */
                SELECT o.id, 'ORDERS_ARCHIVE' AS label FROM `orders` o JOIN [Customers] c ON 1 = 1
                """);
        assertThat(names).contains("APP", "V_SUM", "ORDERS", "CUSTOMERS")
                .doesNotContain("OLD_TABLE", "LEGACY_VIEW", "ORDERS_ARCHIVE");
    }
}
