package com.example.dbadmin.core;

import com.example.dbadmin.core.SchemaObjectSql.ScriptBlockStyle;
import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对象目录的写法挂在方言上之后，「这种库归哪一家」由方言注册表决定，不再按类型串另判一次。
 * 这里守住家族归属（达梦、OceanBase 两种模式跟着谁走）和那几处语法差异的开关。
 */
class SchemaObjectSqlTest {
    private final DialectRegistry registry = new DialectRegistry();

    private SchemaObjectSql of(String dbType, String jdbcUrl) {
        return registry.dialectFor(new DbConnection(1L, "c", dbType, jdbcUrl, "u", "", "dev", false, Instant.now(), Instant.now())).schemaObjects();
    }

    @Test
    void oracleCompatibleDatabasesShareTheOracleCatalog() {
        assertThat(of("oracle", "jdbc:oracle:thin:@h:1521/x")).isSameAs(OracleObjectSql.INSTANCE);
        assertThat(of("dm", "jdbc:dm://h")).isSameAs(OracleObjectSql.INSTANCE);
        assertThat(of("oceanbase-oracle", "jdbc:oceanbase://h")).isSameAs(OracleObjectSql.INSTANCE);
    }

    @Test
    void onlyMariaDbListsSequencesAmongMySqlCompatibleDatabases() {
        assertThat(of("mariadb", "jdbc:mariadb://h/d").listQuery("app", SchemaObjectKind.SEQUENCE)).isNotNull();
        assertThat(of("mysql", "jdbc:mysql://h/d").listQuery("app", SchemaObjectKind.SEQUENCE)).isNull();
        assertThat(of("oceanbase-mysql", "jdbc:oceanbase://h")).isSameAs(MySqlObjectSql.MYSQL);
    }

    @Test
    void unknownDatabasesHaveNoObjectCatalog() {
        SchemaObjectSql none = of("db2", "jdbc:db2://h");
        assertThat(none).isSameAs(SchemaObjectSql.NONE);
        for (SchemaObjectKind kind : SchemaObjectKind.values()) assertThat(none.listQuery("app", kind)).isNull();
    }

    @Test
    void syntaxDifferencesFollowTheDialect() {
        SchemaObjectSql postgres = of("postgresql", "jdbc:postgresql://h/d");
        SchemaObjectSql sqlServer = of("sqlserver", "jdbc:sqlserver://h");
        SchemaObjectSql clickHouse = of("clickhouse", "jdbc:clickhouse://h");
        SchemaObjectSql h2 = of("h2", "jdbc:h2:mem:x");
        SchemaObjectSql mysql = of("mysql", "jdbc:mysql://h/d");

        assertThat(sqlServer.replacePrefix()).isEqualTo("CREATE OR ALTER ");
        assertThat(postgres.replacePrefix()).isEqualTo("CREATE OR REPLACE ");
        assertThat(postgres.invokesRoutinesAsQuery()).isTrue();
        assertThat(clickHouse.invokesRoutinesAsQuery()).isTrue();
        assertThat(mysql.invokesRoutinesAsQuery()).isFalse();
        assertThat(clickHouse.functionsAreGlobal()).isTrue();
        assertThat(h2.functionsMayBeAliases()).isTrue();
        assertThat(h2.functionParametersFromProcedureMetadata()).isTrue();
        assertThat(mysql.definitionsResolveInCurrentNamespace()).isTrue();
        assertThat(mysql.definitionsCarryDefiner()).isTrue();
        assertThat(postgres.definitionsCarryDefiner()).isFalse();
        assertThat(of("oracle", "jdbc:oracle:thin:@h").proceduralBodiesEndWithEnd()).isTrue();
        assertThat(postgres.proceduralBodiesEndWithEnd()).isFalse();
    }

    @Test
    void exportScriptsCloseRoutineBlocksTheWayEachClientExpects() {
        assertThat(of("oracle", "jdbc:oracle:thin:@h").scriptBlockStyle()).isEqualTo(ScriptBlockStyle.ORACLE);
        assertThat(of("mariadb", "jdbc:mariadb://h/d").scriptBlockStyle()).isEqualTo(ScriptBlockStyle.MYSQL);
        assertThat(of("sqlserver", "jdbc:sqlserver://h").scriptBlockStyle()).isEqualTo(ScriptBlockStyle.SQL_SERVER);
        assertThat(of("postgresql", "jdbc:postgresql://h/d").scriptBlockStyle()).isEqualTo(ScriptBlockStyle.PLAIN);
    }

    @Test
    void clickHouseFiltersByPositionBecauseItHasNoLikeEscape() {
        assertThat(of("clickhouse", "jdbc:clickhouse://h").keywordFilter("a_b").sql()).startsWith("positionCaseInsensitive");
        SchemaObjectSql.Query like = of("postgresql", "jdbc:postgresql://h/d").keywordFilter("A_b%");
        assertThat(like.sql()).isEqualTo("LOWER(object_name) LIKE ? ESCAPE '!'");
        assertThat(like.parameters()).containsExactly("%a!_b!%%");
    }
}
