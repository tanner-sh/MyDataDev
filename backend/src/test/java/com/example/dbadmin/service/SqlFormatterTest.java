package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
