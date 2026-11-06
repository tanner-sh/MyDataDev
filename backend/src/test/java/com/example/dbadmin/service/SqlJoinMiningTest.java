package com.example.dbadmin.service;

import com.example.dbadmin.service.SqlJoinMining.JoinPair;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlJoinMiningTest {
    private static List<String> keys(String sql) {
        return SqlJoinMining.extract(sql).stream().map(JoinPair::key).toList();
    }

    @Test
    void resolvesAliasesInJoinOnConditions() {
        assertThat(keys("select * from gl_voucher v join org_orgs o on o.pk_org = v.pk_org left join gl_detail d on d.pk_voucher = v.pk_voucher"))
                .containsExactly("gl_voucher.pk_org=org_orgs.pk_org", "gl_detail.pk_voucher=gl_voucher.pk_voucher");
    }

    @Test
    void readsOracleStyleCommaJoinsAndOuterJoinMarkers() {
        assertThat(keys("SELECT * FROM FBA.GL_VOUCHER V, ORG_ORGS O WHERE V.PK_ORG = O.PK_ORG(+) AND V.DR = 0"))
                .containsExactly("gl_voucher.pk_org=org_orgs.pk_org");
    }

    @Test
    void sameJoinWrittenEitherWayIsOnePair() {
        List<JoinPair> pairs = SqlJoinMining.extract("select 1 from a join b on a.x = b.y join b b2 on b2.y = a.x");
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0)).isEqualTo(new JoinPair("a", "x", "b", "y"));
    }

    @Test
    void acceptsTableNamesAsQualifiersAndQuotedIdentifiers() {
        assertThat(keys("select * from orders join \"Customer\" on orders.customer_id = \"Customer\".id"))
                .containsExactly("customer.id=orders.customer_id");
        assertThat(keys("select * from `orders` o join `t_user` u on o.user_id = u.id"))
                .containsExactly("orders.user_id=t_user.id");
    }

    @Test
    void keepsSelfJoinsOnDifferentColumns() {
        assertThat(keys("select * from org_dept d join org_dept p on d.pk_fatherorg = p.pk_dept"))
                .containsExactly("org_dept.pk_dept=org_dept.pk_fatherorg");
        assertThat(keys("select * from org_dept d join org_dept p on d.pk_dept = p.pk_dept")).isEmpty();
    }

    @Test
    void ignoresExpressionsUnqualifiedColumnsLiteralsAndComments() {
        assertThat(keys("select * from a join b on a.x = b.y + 1")).isEmpty();
        assertThat(keys("select * from a join b on a.x || '-' = b.y")).isEmpty();
        assertThat(keys("select * from a join b on x = y")).isEmpty();
        assertThat(keys("select * from a where a.code = 'b.y'")).isEmpty();
        assertThat(keys("select * from a join b on a.k >= b.k")).isEmpty();
        assertThat(keys("-- a.x = b.y\nselect * from a, b /* a.x = b.y */")).isEmpty();
    }

    @Test
    void skipsSubqueriesCtesAndAmbiguousAliases() {
        assertThat(keys("select * from (select * from b) t join a on a.x = t.y")).isEmpty();
        // 同一个别名在两层里指向不同的表：分不清，宁可不认。
        assertThat(keys("select * from a t where exists (select 1 from b t join c on c.k = t.k)")).isEmpty();
        assertThat(keys("select * from a x join b y on x.k = y.k where x.id in (select z.id from c z where z.a_id = x.id)"))
                .containsExactly("a.k=b.k", "a.id=c.a_id");
    }

    @Test
    void functionCallsAreNotColumns() {
        assertThat(keys("select * from a join b on a.x = pkg.fn(b.y)")).isEmpty();
    }

    @Test
    void cteNamesAreNotTablesButJoinsInsideThemStillCount() {
        String sql = "with report_rows as (select v.pk_org, v.hashkey from gl_voucher v join org_orgs o on o.pk_org = v.pk_org),"
                + " sales (pk_org, hashkey) as (select pk_org, hashkey from so_sale)"
                + " select * from report_rows r join sales s on s.pk_org = r.pk_org and s.hashkey = r.hashkey";
        assertThat(keys(sql)).containsExactly("gl_voucher.pk_org=org_orgs.pk_org");
        assertThat(keys("WITH RECURSIVE t AS (SELECT 1) SELECT * FROM t JOIN b ON t.k = b.k")).isEmpty();
        // Oracle 树形查询的 START WITH 不是 CTE。
        assertThat(keys("select * from org_dept d join org_dept p on d.pk_fatherorg = p.pk_dept start with d.pk_fatherorg is null"))
                .containsExactly("org_dept.pk_dept=org_dept.pk_fatherorg");
    }
}
