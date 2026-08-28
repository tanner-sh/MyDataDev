package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcEndpointsTest {
    @Test
    void locatesHostAndPortForCommonUrls() {
        assertThat(JdbcEndpoints.locate("jdbc:mysql://db.internal:3307/demo?useSSL=false"))
                .extracting(JdbcEndpoints.Endpoint::host, JdbcEndpoints.Endpoint::port)
                .containsExactly("db.internal", 3307);
        assertThat(JdbcEndpoints.locate("jdbc:postgresql://10.0.0.7/demo"))
                .extracting(JdbcEndpoints.Endpoint::host, JdbcEndpoints.Endpoint::port)
                .containsExactly("10.0.0.7", 5432);
        assertThat(JdbcEndpoints.locate("jdbc:sqlserver://sql.internal:1433;databaseName=demo"))
                .extracting(JdbcEndpoints.Endpoint::host, JdbcEndpoints.Endpoint::port)
                .containsExactly("sql.internal", 1433);
        assertThat(JdbcEndpoints.locate("jdbc:dm://dm.internal"))
                .extracting(JdbcEndpoints.Endpoint::host, JdbcEndpoints.Endpoint::port)
                .containsExactly("dm.internal", 5236);
    }

    @Test
    void locatesOracleServiceAndSidForms() {
        assertThat(JdbcEndpoints.locate("jdbc:oracle:thin:@//ora.internal:1522/ORCLPDB1"))
                .extracting(JdbcEndpoints.Endpoint::host, JdbcEndpoints.Endpoint::port)
                .containsExactly("ora.internal", 1522);
        assertThat(JdbcEndpoints.locate("jdbc:oracle:thin:@ora.internal:1521:ORCL"))
                .extracting(JdbcEndpoints.Endpoint::host, JdbcEndpoints.Endpoint::port)
                .containsExactly("ora.internal", 1521);
        assertThat(JdbcEndpoints.locate("jdbc:oracle:thin:@ora.internal/ORCLPDB1"))
                .extracting(JdbcEndpoints.Endpoint::host, JdbcEndpoints.Endpoint::port)
                .containsExactly("ora.internal", 1521);
    }

    @Test
    void ignoresCredentialsInTheAuthority() {
        assertThat(JdbcEndpoints.locate("jdbc:mysql://user:p@ss@db.internal:3306/demo").host())
                .isEqualTo("db.internal");
    }

    @Test
    void supportsBracketedIpv6Hosts() {
        assertThat(JdbcEndpoints.locate("jdbc:postgresql://[2001:db8::1]:5433/demo"))
                .extracting(JdbcEndpoints.Endpoint::host, JdbcEndpoints.Endpoint::port)
                .containsExactly("2001:db8::1", 5433);
        assertThat(JdbcEndpoints.rewrite("jdbc:postgresql://[2001:db8::1]:5433/demo", "127.0.0.1", 40000))
                .isEqualTo("jdbc:postgresql://127.0.0.1:40000/demo");
    }

    @Test
    void rewritesOnlyTheHostAndPort() {
        assertThat(JdbcEndpoints.rewrite("jdbc:mysql://db.internal:3307/demo?useSSL=false", "127.0.0.1", 51234))
                .isEqualTo("jdbc:mysql://127.0.0.1:51234/demo?useSSL=false");
        assertThat(JdbcEndpoints.rewrite("jdbc:postgresql://10.0.0.7/demo", "127.0.0.1", 51234))
                .isEqualTo("jdbc:postgresql://127.0.0.1:51234/demo");
        assertThat(JdbcEndpoints.rewrite("jdbc:sqlserver://sql.internal:1433;databaseName=demo", "127.0.0.1", 51234))
                .isEqualTo("jdbc:sqlserver://127.0.0.1:51234;databaseName=demo");
        assertThat(JdbcEndpoints.rewrite("jdbc:oracle:thin:@ora.internal:1521:ORCL", "127.0.0.1", 51234))
                .isEqualTo("jdbc:oracle:thin:@127.0.0.1:51234:ORCL");
        assertThat(JdbcEndpoints.rewrite("jdbc:oracle:thin:@//ora.internal:1522/ORCLPDB1", "127.0.0.1", 51234))
                .isEqualTo("jdbc:oracle:thin:@//127.0.0.1:51234/ORCLPDB1");
    }

    @Test
    void rejectsAddressesATunnelCannotServe() {
        // 本地文件数据库没有主机端口。
        assertThatThrownBy(() -> JdbcEndpoints.locate("jdbc:sqlite:/tmp/demo.db"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法通过 SSH 隧道访问");
        assertThatThrownBy(() -> JdbcEndpoints.locate("jdbc:mysql://a.internal:3306,b.internal:3306/demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多主机");
        assertThatThrownBy(() -> JdbcEndpoints.locate("jdbc:sqlserver://sql.internal\\SQLEXPRESS;databaseName=demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("命名实例");
        assertThatThrownBy(() -> JdbcEndpoints.locate("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=ora)(PORT=1521)))"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TNS");
        assertThatThrownBy(() -> JdbcEndpoints.locate("mysql://db.internal:3306/demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbc:");
    }
}
