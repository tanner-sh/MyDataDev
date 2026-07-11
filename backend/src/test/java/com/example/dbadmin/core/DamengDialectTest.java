package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DamengDialectTest {
    private final DamengDialect dialect = new DamengDialect();

    @Test
    void supportsDmUrlAndLimitOffsetPagination() {
        assertThat(dialect.supports("dm", "jdbc:dm://localhost:5236")).isTrue();
        assertThat(dialect.supports("mysql", "jdbc:dm://localhost:5236")).isTrue();
        assertThat(dialect.namespaceKind()).isEqualTo(DatabaseDialect.NamespaceKind.SCHEMA);
        assertThat(dialect.pageQuery("SELECT * FROM \"APP\".\"USERS\"", 101, 200))
                .isEqualTo("SELECT * FROM \"APP\".\"USERS\" LIMIT 101 OFFSET 200");
    }
}
