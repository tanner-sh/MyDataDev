package com.example.dbadmin.core;

import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DialectRegistryTest {
    private final DialectRegistry registry = new DialectRegistry();

    @Test
    void resolvesEveryDatabaseTypeExposedByTheFrontend() {
        Map<String, Class<? extends DatabaseDialect>> expected = Map.ofEntries(
                Map.entry("h2", H2Dialect.class),
                Map.entry("mysql", MySqlDialect.class),
                Map.entry("mariadb", MySqlDialect.class),
                Map.entry("postgresql", PostgreSqlDialect.class),
                Map.entry("oracle", OracleDialect.class),
                Map.entry("dm", DamengDialect.class),
                Map.entry("oceanbase-mysql", OceanBaseMySqlDialect.class),
                Map.entry("oceanbase-oracle", OceanBaseOracleDialect.class),
                Map.entry("sqlserver", SqlServerDialect.class),
                Map.entry("sqlite", SqliteDialect.class),
                Map.entry("clickhouse", ClickHouseDialect.class)
        );

        expected.forEach((dbType, dialectClass) -> assertThat(registry.dialectFor(connection(dbType)))
                .as(dbType)
                .isInstanceOf(dialectClass));
    }

    @Test
    void usesSqlServerSpecificPagingAndIdentifierQuoting() {
        DatabaseDialect dialect = registry.dialectFor(connection("sqlserver"));

        assertThat(dialect.pageQuery("SELECT * FROM users ORDER BY id", 101, 200))
                .isEqualTo("SELECT * FROM users ORDER BY id OFFSET 200 ROWS FETCH NEXT 101 ROWS ONLY");
        assertThat(dialect.quoteIdentifier("a]b")).isEqualTo("[a]]b]");
        assertThat(dialect.capabilities().tableDesign()).isFalse();
    }

    @Test
    void keepsUnsupportedGenericConnectionsBrowseOnly() {
        DatabaseDialect dialect = registry.dialectFor(connection("unknown"));

        assertThat(dialect).isInstanceOf(DefaultDialect.class);
        assertThat(dialect.capabilities().tableBrowse()).isTrue();
        assertThat(dialect.capabilities().tableEdit()).isFalse();
        assertThat(dialect.capabilities().tableDesign()).isFalse();
        assertThat(dialect.capabilities().explain()).isFalse();
    }

    private DbConnection connection(String dbType) {
        return new DbConnection(1L, dbType, dbType, "jdbc:" + dbType + ":test", "user", "", "dev", false, Instant.now(), Instant.now());
    }
}
