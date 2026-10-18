package com.example.dbadmin.service;

import com.example.dbadmin.core.H2Dialect;
import com.example.dbadmin.core.OracleDialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompilationDiagnosticsTest {
    @Test
    void recognisesTheObjectEachCreateOrCompileStatementBuilds() {
        assertThat(CompilationDiagnostics.target("CREATE OR REPLACE PROCEDURE yy_etl.sp_load AS BEGIN NULL; END;"))
                .contains(new CompilationDiagnostics.Target("yy_etl", "sp_load", "PROCEDURE"));
        assertThat(CompilationDiagnostics.target("-- 部署\n/* v2 */ create or replace editionable package body \"Etl\".\"Pkg\" as end;"))
                .contains(new CompilationDiagnostics.Target("Etl", "Pkg", "PACKAGE BODY"));
        assertThat(CompilationDiagnostics.target("CREATE FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;"))
                .contains(new CompilationDiagnostics.Target(null, "f", "FUNCTION"));
        assertThat(CompilationDiagnostics.target("CREATE OR REPLACE TYPE BODY t AS END;"))
                .contains(new CompilationDiagnostics.Target(null, "t", "TYPE BODY"));
        assertThat(CompilationDiagnostics.target("ALTER PROCEDURE app.sp_x COMPILE"))
                .contains(new CompilationDiagnostics.Target("app", "sp_x", "PROCEDURE"));
        assertThat(CompilationDiagnostics.target("ALTER PACKAGE app.pkg COMPILE BODY"))
                .contains(new CompilationDiagnostics.Target("app", "pkg", "PACKAGE BODY"));
        assertThat(CompilationDiagnostics.target("ALTER VIEW v COMPILE"))
                .contains(new CompilationDiagnostics.Target(null, "v", "VIEW"));
    }

    @Test
    void ignoresStatementsThatDoNotBuildRoutineObjects() {
        assertThat(CompilationDiagnostics.target("CREATE TABLE t (a NUMBER)")).isEmpty();
        assertThat(CompilationDiagnostics.target("ALTER PROCEDURE p RENAME TO q")).isEmpty();
        assertThat(CompilationDiagnostics.target("BEGIN EXECUTE IMMEDIATE 'CREATE OR REPLACE PROCEDURE p AS BEGIN NULL; END;'; END;")).isEmpty();
        assertThat(CompilationDiagnostics.target("INSERT INTO t VALUES (1)")).isEmpty();
        assertThat(CompilationDiagnostics.target(null)).isEmpty();
    }

    @Test
    void readsErrorsCaseInsensitivelyAndFallsBackToTheActivatedSchema() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE fake_errors(owner VARCHAR(40), name VARCHAR(40), type VARCHAR(40), "
                    + "sequence INT, line INT, position INT, text VARCHAR(200))");
            connection.createStatement().execute("INSERT INTO fake_errors VALUES "
                    + "('ETL', 'SP_LOAD', 'PROCEDURE', 1, 3, 5, 'PLS-00201: identifier ''X'' must be declared'),"
                    + "('ETL', 'SP_LOAD', 'PROCEDURE', 2, 3, 1, 'PL/SQL: Statement ignored'),"
                    + "('APP', 'SP_LOAD', 'PROCEDURE', 1, 9, 9, 'other schema')");
            var dialect = new FakeErrorsDialect();

            var errors = CompilationDiagnostics.read(connection, dialect, "create or replace procedure sp_load as begin x; end;", "etl", 5);
            assertThat(errors).extracting(error -> error.text()).containsExactly(
                    "PLS-00201: identifier 'X' must be declared", "PL/SQL: Statement ignored");
            assertThat(errors.get(0).line()).isEqualTo(3);
            var target = CompilationDiagnostics.target("create or replace procedure etl.sp_load as begin x; end;").orElseThrow();
            assertThat(CompilationDiagnostics.describe(target, errors.get(0)))
                    .isEqualTo("etl.sp_load（PROCEDURE） 第 3 行第 5 列：PLS-00201: identifier 'X' must be declared");

            // 不建例程的语句、不需要这一步的方言都不发查询；字典表读不了就当没有错误。
            assertThat(CompilationDiagnostics.read(connection, dialect, "create table t(a int)", "etl", 5)).isEmpty();
            assertThat(CompilationDiagnostics.read(connection, new H2Dialect(), "create procedure p as begin null; end;", "etl", 5)).isEmpty();
            assertThat(CompilationDiagnostics.read(connection, new OracleDialect(), "create procedure p as begin null; end;", "etl", 5)).isEmpty();
        }
    }

    /** 与 OracleDialect 的 ALL_ERRORS 查询同样的参数顺序与大小写不敏感比对。 */
    private static final class FakeErrorsDialect extends H2Dialect {
        @Override
        public String compilationErrorsSql() {
            return "SELECT line, position, text FROM fake_errors WHERE UPPER(owner) = UPPER(COALESCE(?, 'PUBLIC')) "
                    + "AND UPPER(name) = UPPER(?) AND type = ? ORDER BY sequence";
        }
    }
}
