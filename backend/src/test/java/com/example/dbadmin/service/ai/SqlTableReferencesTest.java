package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTableReferencesTest {
    @Test
    void findsTablesBehindFromAndJoin() {
        var tables = SqlTableReferences.extract(
                "SELECT o.id FROM orders o JOIN order_item i ON i.order_id = o.id LEFT JOIN users u ON u.id = o.user_id");

        assertThat(tables).containsExactly("orders", "order_item", "users");
    }

    @Test
    void findsTablesInWriteStatements() {
        assertThat(SqlTableReferences.extract("UPDATE billing.invoice SET paid = 1 WHERE id = 3")).containsExactly("billing.invoice");
        assertThat(SqlTableReferences.extract("INSERT INTO audit_log(action) VALUES ('x')")).containsExactly("audit_log");
        assertThat(SqlTableReferences.extract("ALTER TABLE `user profile` ADD COLUMN age INT")).containsExactly("`user profile`");
    }

    /** 注释里的表名不是引用 —— 否则把注释掉的旧 SQL 里的表也一并发出去了。 */
    @Test
    void ignoresCommentsAndStringLiterals() {
        var tables = SqlTableReferences.extract("""
                -- FROM legacy_orders
                SELECT * FROM orders /* FROM archive_orders */ WHERE note = 'from secret_table'
                """);

        assertThat(tables).containsExactly("orders");
    }

    @Test
    void skipsSubqueriesAndKeywords() {
        assertThat(SqlTableReferences.extract("SELECT * FROM (SELECT 1) t")).isEmpty();
        assertThat(SqlTableReferences.extract("SELECT 1 FROM dual")).isEmpty();
    }

    @Test
    void keepsOriginalCaseAndQuoting() {
        assertThat(SqlTableReferences.extract("SELECT * FROM \"Orders\"")).containsExactly("\"Orders\"");
    }

    @Test
    void capsHowManyTablesItReports() {
        StringBuilder sql = new StringBuilder("SELECT 1 FROM t0");
        for (int index = 1; index < 40; index++) sql.append(" JOIN t").append(index).append(" ON 1=1");

        assertThat(SqlTableReferences.extract(sql.toString())).hasSize(SqlTableReferences.MAX_TABLES);
    }

    @Test
    void splitsQualifiedNames() {
        assertThat(SqlTableReferences.split("billing.invoice")).containsExactly("billing", "invoice");
        assertThat(SqlTableReferences.split("`orders`")).containsExactly(null, "orders");
        assertThat(SqlTableReferences.split("catalog.schema.tbl")).containsExactly("catalog.schema", "tbl");
    }

    @Test
    void toleratesEmptyInput() {
        assertThat(SqlTableReferences.extract(null)).isEmpty();
        assertThat(SqlTableReferences.extract("   ")).isEmpty();
    }
}
