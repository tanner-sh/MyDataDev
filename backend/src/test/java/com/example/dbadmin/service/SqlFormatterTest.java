package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlFormatterTest {
    private final SqlFormatter formatter = new SqlFormatter();

    @Test
    void indentsSubqueriesJoinsAndConditions() {
        String formatted = formatter.format("""
                SELECT c.pk_accountingbook, d.pk_accasoa FROM bd_accasoa a
                inner join bd_accchart b on a.pk_accchart = b.pk_accchart
                inner join (select pk_account, pk_accasoa from bd_accasoa
                where pk_accasoa in (select distinct(pk_accasoa) from si_detailtemplet where nvl(dr,0)=0)) d
                on d.pk_account = a.pk_account
                where nvl(a.dr,0)=0 and nvl(b.dr,0)=0
                """);

        assertThat(formatted).isEqualTo("""
                SELECT c.pk_accountingbook,
                       d.pk_accasoa
                FROM bd_accasoa a
                    INNER JOIN bd_accchart b ON a.pk_accchart = b.pk_accchart
                    INNER JOIN (SELECT pk_account,
                                       pk_accasoa
                                FROM bd_accasoa
                                WHERE pk_accasoa IN (SELECT DISTINCT (pk_accasoa)
                                                     FROM si_detailtemplet
                                                     WHERE nvl(dr, 0) = 0)) d
                        ON d.pk_account = a.pk_account
                WHERE nvl(a.dr, 0) = 0
                  AND nvl(b.dr, 0) = 0""");
    }

    @Test
    void alignsCaseBranchesAndKeepsBetweenInline() {
        String formatted = formatter.format(
                "select case when r.name is null then 'unknown' else r.name end as label from recent r "
                        + "where r.id between 1 and 100 or r.name like 'a%'"
        );

        assertThat(formatted).isEqualTo("""
                SELECT CASE
                           WHEN r.name IS NULL THEN 'unknown'
                           ELSE r.name
                       END AS label
                FROM recent r
                WHERE r.id BETWEEN 1 AND 100
                   OR r.name LIKE 'a%'""");
    }

    @Test
    void keepsFunctionCallsTightAndSeparatesStatements() {
        String formatted = formatter.format("insert into t (a,b) values (1,-2);delete from t where id in (1,2)");

        assertThat(formatted).isEqualTo("""
                INSERT INTO t (a, b)
                VALUES (1, -2);

                DELETE FROM t
                WHERE id IN (1, 2)""");
    }

    @Test
    void preservesLiteralsCommentsAndQuotedIdentifiers() {
        String formatted = formatter.format("""
                select 'from  where' as text_value, "order by" as quoted_name,
                       $tag$select  from$tag$ as function_body, q'[left  join]' as oracle_text
                from users left join roles on roles.id = users.role_id -- where  join
                where users.name = 'A  B'
                """);

        assertThat(formatted)
                .contains("'from  where'")
                .contains("\"order by\"")
                .contains("$tag$select  from$tag$")
                .contains("q'[left  join]'")
                .contains("-- where  join\n")
                .contains("\n    LEFT JOIN roles")
                .contains("\nWHERE users.name")
                .contains("'A  B'");
    }

    @Test
    void formatsCreateTableColumnsAndConstraintsOnePerLine() {
        String source = """
                create table "FBA_DEV_20230210"."SIM_OTCTRADE"("DEF20" VARCHAR2(101),"TAX6" NUMBER(28,8),
                "TAX3" NUMBER(*,0),CONSTRAINT "PK_SIM_OTCTRADE" PRIMARY KEY("DEF20")) TABLESPACE "DATA";
                """;

        String formatted = formatter.format(source);

        assertThat(formatted).isEqualTo("""
                CREATE TABLE "FBA_DEV_20230210"."SIM_OTCTRADE" (
                    "DEF20" VARCHAR2(101),
                    "TAX6" NUMBER(28, 8),
                    "TAX3" NUMBER(*, 0),
                    CONSTRAINT "PK_SIM_OTCTRADE" PRIMARY KEY ("DEF20")
                ) TABLESPACE "DATA";""");
        assertThat(formatter.format(formatted)).isEqualTo(formatted);
    }

    @Test
    void keepsShortTypeParametersTogetherEvenAfterALongColumnName() {
        String column = "very_long_column_".repeat(7);

        String formatted = formatter.format("create table t (" + column + " NUMBER(28,8), other VARCHAR2(10))");

        assertThat(formatted).isEqualTo("CREATE TABLE t (\n    " + column
                + " NUMBER(28, 8),\n    other VARCHAR2(10)\n)");
    }

    @Test
    void keepsLongDefaultExpressionContinuationsNearTheTableIndent() {
        String column = "long_column_".repeat(5);
        String firstValue = "'" + "a".repeat(50) + "'";
        String secondValue = "'" + "b".repeat(50) + "'";
        String source = "CREATE TABLE t (" + column + " VARCHAR(20) DEFAULT coalesce("
                + firstValue + "," + secondValue + "), other NUMBER(28,8))";

        String formatted = formatter.format(source);

        assertThat(formatted).contains(firstValue + ",\n        " + secondValue + "),\n    other NUMBER(28, 8)");
        assertThat(formatter.format(formatted)).isEqualTo(formatted);
    }

    @Test
    void keepsCreateTableCommentsAndNestedExpressionsInPlace() {
        String source = """
                create table if not exists `db`.`orders` (
                `id` DECIMAL(28,8) NOT NULL, -- identifier
                /* explanation, with comma */
                `status` varchar(20) default 'a,b', /* status note */
                `amount` DECIMAL(28,8) default coalesce(1,2),
                CONSTRAINT positive CHECK (`amount` IN (1,2,3)),
                `note` varchar(20) default q'[a,b]' -- last column
                ) ENGINE=InnoDB;
                """;

        String formatted = formatter.format(source);

        assertThat(formatted).isEqualTo("""
                CREATE TABLE IF NOT EXISTS `db`.`orders` (
                    `id` DECIMAL(28, 8) NOT NULL, -- identifier
                    /* explanation, with comma */
                    `status` varchar(20) DEFAULT 'a,b', /* status note */
                    `amount` DECIMAL(28, 8) DEFAULT coalesce(1, 2),
                    CONSTRAINT positive CHECK (`amount` IN (1, 2, 3)),
                    `note` varchar(20) DEFAULT q'[a,b]' -- last column
                ) ENGINE = InnoDB;""");
        assertThat(formatter.format(formatted)).isEqualTo(formatted);
    }

    @Test
    void formatsTemporaryTablesWithoutTreatingCreateTableAsSelectAsAColumnList() {
        String formatted = formatter.format("""
                create GLOBAL temporary table temp_data (id NUMBER(10), name VARCHAR2(20));
                create table copied as select coalesce(a,b) from source;
                """);

        assertThat(formatted).isEqualTo("""
                CREATE GLOBAL TEMPORARY TABLE temp_data (
                    id NUMBER(10),
                    name VARCHAR2(20)
                );

                CREATE TABLE copied AS
                SELECT coalesce(a, b)
                FROM source;""");
    }

    @Test
    void preservesIdentifiersThatCanAlsoAppearInTableSyntax() {
        String formatted = formatter.format("create table global (local VARCHAR(20), key INTEGER)");

        assertThat(formatted).isEqualTo("""
                CREATE TABLE global (
                    local VARCHAR(20),
                    key INTEGER
                )""");
    }

    @Test
    void recognizesCommentedHeadersAndPreservesMultilineContent() {
        String source = """
                /* header */ create table /* table name */ [db].[notes] (
                id INTEGER,
                /* first line
                second line */
                note VARCHAR(40) DEFAULT 'first line
                second line',
                amount DECIMAL(28,8)
                )
                """;

        String formatted = formatter.format(source);

        assertThat(formatted).isEqualTo("""
                /* header */ CREATE TABLE /* table name */ [db].[notes] (
                    id INTEGER,
                    /* first line
                second line */
                    note VARCHAR(40) DEFAULT 'first line
                second line',
                    amount DECIMAL(28, 8)
                )""");
        assertThat(formatter.format(formatted)).isEqualTo(formatted);
    }

    @Test
    void keepsClosingParenthesisAfterALineComment() {
        assertThat(formatter.format("select coalesce(a -- argument\n) from t"))
                .isEqualTo("""
                        SELECT coalesce(a -- argument
                                       )
                        FROM t""");
    }

    @Test
    void formattedTableDefinitionRetainsExecutableConstraintsAndComments() throws Exception {
        String source = """
                CREATE TABLE sample (id DECIMAL(28,8) NOT NULL, -- identifier
                label VARCHAR(40) DEFAULT 'A,B', /* default */
                CONSTRAINT sample_pk PRIMARY KEY (id),
                CONSTRAINT sample_ck CHECK (label IN ('A,B','C')) -- constraint
                )
                """;

        try (var connection = DriverManager.getConnection("jdbc:h2:mem:sql_formatter");
             var statement = connection.createStatement()) {
            statement.execute(formatter.format(source));
            statement.executeUpdate("INSERT INTO sample (id) VALUES (1)");
            try (var rows = statement.executeQuery("SELECT label FROM sample WHERE id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("A,B");
            }
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO sample VALUES (2, 'invalid')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void leavesProceduralBlocksUntouched() {
        String source = "create or replace procedure p is\nbegin\n  null;\nend;";

        assertThat(formatter.format(source)).isEqualTo(source);
    }

    @Test
    void formattingTwiceProducesTheSameText() {
        String source = "select a.*, count(*) cnt from t a where a.d = to_date('2020-01-01','yyyy-mm-dd') "
                + "and exists (select 1 from u where u.id = a.id) group by a.id order by a.id desc";

        String once = formatter.format(source);

        assertThat(formatter.format(once)).isEqualTo(once);
    }

    @Test
    void returnsEmptyTextForBlankInput() {
        assertThat(formatter.format("   \n ")).isEmpty();
        assertThat(formatter.format(null)).isEmpty();
    }
}
