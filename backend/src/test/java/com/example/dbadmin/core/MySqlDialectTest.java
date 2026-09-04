package com.example.dbadmin.core;

import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlDialectTest {
    private final MySqlDialect dialect = new MySqlDialect();

    /**
     * Connector/J 默认走客户端预编译，此时 {@code getMetaData()} 会另建一个语句把查询真执行
     * 一遍，而且不继承外层的 queryTimeout。AI 会拿没人看过的 SQL 反复调用编译校验，所以这条
     * 路径必须是 EXPLAIN，绝不能落回默认的 prepare。
     */
    @Test
    void compilesQueriesWithExplainRatherThanPreparingThem() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(mock(ResultSet.class));

        dialect.compileQuery(connection, "SELECT id FROM app_user", 9);

        verify(statement).executeQuery("EXPLAIN SELECT id FROM app_user");
        verify(statement).setQueryTimeout(9);
        verify(connection, never()).prepareStatement(anyString());
    }

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
