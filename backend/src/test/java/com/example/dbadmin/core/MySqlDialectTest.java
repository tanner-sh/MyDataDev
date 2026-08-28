package com.example.dbadmin.core;

import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlDialectTest {
    private final MySqlDialect dialect = new MySqlDialect();

    @Test
    void usesCatalogAndBackticksForMySqlObjects() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn("trading");

        assertThat(dialect.namespaceKind()).isEqualTo(DatabaseDialect.NamespaceKind.CATALOG);
        assertThat(dialect.currentSchema(connection)).isEqualTo("trading");
        assertThat(dialect.metadataScope(connection, "archive"))
                .isEqualTo(new DatabaseDialect.MetadataScope("archive", null));
        assertThat(dialect.qualifiedName("trading", "cash_ledger"))
                .isEqualTo("`trading`.`cash_ledger`");
        assertThat(dialect.quoteIdentifier("odd`name")).isEqualTo("`odd``name`");
    }

    @Test
    void activatesRequestedDatabaseAsJdbcCatalog() throws Exception {
        Connection connection = mock(Connection.class);

        dialect.activateNamespace(connection, "i_fin_fi_va_db");

        verify(connection).setCatalog("i_fin_fi_va_db");
    }

    @Test
    void registrySelectsExplicitOceanBaseModesAndDameng() {
        DialectRegistry registry = new DialectRegistry();

        assertThat(registry.dialectFor(connection("oceanbase-mysql", "jdbc:oceanbase://localhost:2881/demo")))
                .isInstanceOf(OceanBaseMySqlDialect.class);
        assertThat(registry.dialectFor(connection("oceanbase-oracle", "jdbc:oceanbase://localhost:2881/demo")))
                .isInstanceOf(OceanBaseOracleDialect.class);
        assertThat(registry.dialectFor(connection("dm", "jdbc:dm://localhost:5236")))
                .isInstanceOf(DamengDialect.class);
    }

    @Test
    void quotesMySqlTableLifecycleStatementsWithBackticks() {
        assertThat(dialect.renameTableSql("trading", "cash_ledger", "cash_archive"))
                .isEqualTo("ALTER TABLE `trading`.`cash_ledger` RENAME TO `cash_archive`");
        assertThat(dialect.dropTableSql("trading", "cash_archive"))
                .isEqualTo("DROP TABLE `trading`.`cash_archive`");
    }

    @Test
    void generatedLiteralsAreIndependentOfMysqlBackslashMode() {
        assertThat(dialect.scriptLiteral("path\\file"))
                .isEqualTo("_utf8mb4 0x706174685c66696c65");
        assertThat(dialect.scriptLiteral(true)).isEqualTo("1");
        assertThat(dialect.scriptLiteral(new byte[]{0, (byte) 0xff})).isEqualTo("0x00ff");
    }

    private DbConnection connection(String type, String url) {
        return new DbConnection(1L, type, type, url, "user", "", "dev", false, Instant.now(), Instant.now());
    }
}
