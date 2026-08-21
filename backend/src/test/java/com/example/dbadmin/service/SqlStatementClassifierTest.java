package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import static com.example.dbadmin.service.SqlStatementClassifier.Kind.DDL;
import static com.example.dbadmin.service.SqlStatementClassifier.Kind.MUTATION;
import static com.example.dbadmin.service.SqlStatementClassifier.Kind.QUERY;
import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementClassifierTest {
    private final SqlStatementClassifier classifier = new SqlStatementClassifier();

    @Test
    void classifiesQueriesWithoutBeingFooledByCommentsOrStringLiterals() {
        assertThat(classifier.classify("/* UPDATE users */ SELECT 'FOR UPDATE', \"DELETE\" FROM users"))
                .isEqualTo(QUERY);
        assertThat(classifier.classify("WITH q AS (SELECT $$DELETE FROM users$$ AS text) SELECT * FROM q"))
                .isEqualTo(QUERY);
        assertThat(classifier.classify("WITH q AS (SELECT q'[DROP TABLE users]' AS text FROM dual) SELECT * FROM q"))
                .isEqualTo(QUERY);
    }

    @Test
    void detectsDataChangingCtesAndExplainAnalyzeWrites() {
        assertThat(classifier.classify("WITH removed AS (DELETE FROM users RETURNING *) SELECT * FROM removed"))
                .isEqualTo(MUTATION);
        assertThat(classifier.classify("EXPLAIN ANALYZE UPDATE users SET name = 'x'"))
                .isEqualTo(MUTATION);
        assertThat(classifier.classify("WITH t AS (INSERT INTO audit(id) VALUES (1) RETURNING *) SELECT * FROM t"))
                .isEqualTo(MUTATION);
        assertThat(classifier.classify("WITH a AS (SELECT 1), b AS (UPDATE users SET active = TRUE RETURNING id) SELECT * FROM b"))
                .isEqualTo(MUTATION);
        assertThat(classifier.classify("WITH t AS ((DELETE FROM users RETURNING *)) SELECT * FROM t"))
                .isEqualTo(MUTATION);
    }

    @Test
    void doesNotTreatFunctionsThatShareKeywordNamesInsideCtesAsWrites() {
        // REPLACE, TRUNCATE, LOAD, ANALYZE and friends are ordinary functions in
        // several dialects. Only statement position may mark a CTE as writing.
        assertThat(classifier.classify("WITH t AS (SELECT REPLACE(name, 'a', 'b') AS n FROM users) SELECT * FROM t"))
                .isEqualTo(QUERY);
        assertThat(classifier.classify("WITH t AS (SELECT TRUNCATE(price, 2) AS p FROM orders) SELECT * FROM t"))
                .isEqualTo(QUERY);
        assertThat(classifier.classify("WITH t AS (SELECT id, COMMENT AS c FROM notes) SELECT * FROM t"))
                .isEqualTo(QUERY);
        assertThat(classifier.classify("WITH t AS (SELECT LOAD(payload) FROM jobs) SELECT * FROM t"))
                .isEqualTo(QUERY);
        assertThat(classifier.isQuery("WITH t AS (SELECT REPLACE(name, 'a', 'b') AS n FROM users) SELECT * FROM t"))
                .isTrue();
    }

    @Test
    void detectsSelectFormsThatWriteOrAcquireWriteLocksEvenWithComments() {
        assertThat(classifier.classify("SELECT * FROM users INTO /* output */ OUTFILE '/tmp/users.csv'"))
                .isEqualTo(MUTATION);
        assertThat(classifier.classify("SELECT id INTO new_users FROM users"))
                .isEqualTo(MUTATION);
        assertThat(classifier.classify("SELECT * FROM users FOR /* lock */ UPDATE"))
                .isEqualTo(MUTATION);
        assertThat(classifier.classify("SELECT sequence_name.NEXTVAL FROM dual"))
                .isEqualTo(MUTATION);
    }

    @Test
    void classifiesOrdinaryMutationAndDdlStatements() {
        assertThat(classifier.classify("UPDATE users SET name = 'Alice'"))
                .isEqualTo(MUTATION);
        assertThat(classifier.classify("WITH source AS (SELECT 1) INSERT INTO users(id) SELECT * FROM source"))
                .isEqualTo(MUTATION);
        assertThat(classifier.classify("CREATE TABLE users(id INT)"))
                .isEqualTo(DDL);
        assertThat(classifier.classify("SET search_path TO reporting"))
                .isEqualTo(DDL);
        assertThat(classifier.changesSession("SET search_path TO reporting")).isTrue();
        assertThat(classifier.changesSession("CREATE TABLE users(id INT)")).isFalse();
    }

    @Test
    void onlyEnablesAutomaticPagingForPlainTopLevelSelects() {
        assertThat(classifier.isAutomaticallyPageable("SELECT * FROM users ORDER BY id")).isTrue();
        assertThat(classifier.isAutomaticallyPageable("WITH q AS (SELECT * FROM users LIMIT 2) SELECT * FROM q")).isTrue();
        assertThat(classifier.isAutomaticallyPageable("SELECT * FROM users LIMIT 20")).isFalse();
        assertThat(classifier.isAutomaticallyPageable("SELECT TOP 20 * FROM users")).isFalse();
        assertThat(classifier.isAutomaticallyPageable("SHOW TABLES")).isFalse();
    }

    @Test
    void requiresConfirmationForUpdateAndDeleteWithoutTopLevelWhere() {
        assertThat(classifier.requiresUnscopedMutationConfirmation("UPDATE users SET name = 'Alice'")).isTrue();
        assertThat(classifier.requiresUnscopedMutationConfirmation("DELETE FROM users")).isTrue();
        assertThat(classifier.requiresUnscopedMutationConfirmation("UPDATE users SET note = 'WHERE id = 1' /* WHERE id = 2 */")).isTrue();
        assertThat(classifier.requiresUnscopedMutationConfirmation("UPDATE users SET owner_id = (SELECT id FROM owners WHERE active = TRUE)")).isTrue();
        assertThat(classifier.requiresUnscopedMutationConfirmation("WITH source AS (SELECT id FROM owners WHERE active = TRUE) UPDATE users SET active = FALSE")).isTrue();
    }

    @Test
    void acceptsTopLevelWhereForUpdateAndDelete() {
        assertThat(classifier.requiresUnscopedMutationConfirmation("UPDATE users SET name = 'Alice' WHERE id = 1")).isFalse();
        assertThat(classifier.requiresUnscopedMutationConfirmation("DELETE FROM users WHERE active = FALSE")).isFalse();
        assertThat(classifier.requiresUnscopedMutationConfirmation("SELECT * FROM users")).isFalse();
        assertThat(classifier.requiresUnscopedMutationConfirmation("INSERT INTO users(id) VALUES (1)")).isFalse();
        assertThat(classifier.requiresUnscopedMutationConfirmation("SELECT * FROM orders FOR UPDATE")).isFalse();
    }

    @Test
    void keepsExplainOfPlainQueryAsQuery() {
        // These column names collide with keywords in the mutation/DDL sets.
        assertThat(classifier.classify("EXPLAIN SELECT comment FROM posts")).isEqualTo(SqlStatementClassifier.Kind.QUERY);
        assertThat(classifier.classify("EXPLAIN SELECT start FROM events")).isEqualTo(SqlStatementClassifier.Kind.QUERY);
        assertThat(classifier.classify("EXPLAIN SELECT id FROM users ORDER BY grant_id")).isEqualTo(SqlStatementClassifier.Kind.QUERY);
        assertThat(classifier.classify("EXPLAIN ANALYZE SELECT comment FROM posts")).isEqualTo(SqlStatementClassifier.Kind.QUERY);
        assertThat(classifier.classify("EXPLAIN (ANALYZE, BUFFERS) SELECT comment FROM posts")).isEqualTo(SqlStatementClassifier.Kind.QUERY);
        assertThat(classifier.classify("EXPLAIN PLAN FOR SELECT comment FROM posts")).isEqualTo(SqlStatementClassifier.Kind.QUERY);
        assertThat(classifier.classify("EXPLAIN QUERY PLAN SELECT comment FROM posts")).isEqualTo(SqlStatementClassifier.Kind.QUERY);
        assertThat(classifier.isQuery("EXPLAIN SELECT comment FROM posts")).isTrue();
    }

    @Test
    void treatsExplainOfWriteAsMutation() {
        assertThat(classifier.classify("EXPLAIN DELETE FROM users")).isEqualTo(SqlStatementClassifier.Kind.MUTATION);
        assertThat(classifier.classify("EXPLAIN UPDATE users SET active = FALSE")).isEqualTo(SqlStatementClassifier.Kind.MUTATION);
        assertThat(classifier.classify("EXPLAIN ANALYZE DELETE FROM users")).isEqualTo(SqlStatementClassifier.Kind.MUTATION);
        assertThat(classifier.classify("EXPLAIN FORMAT=JSON DELETE FROM users")).isEqualTo(SqlStatementClassifier.Kind.MUTATION);
        // hasQuerySideEffect only ever escalates to MUTATION; ExecutionGuard treats
        // anything that is not QUERY the same way, so DDL vs MUTATION is not observable here.
        assertThat(classifier.classify("EXPLAIN TRUNCATE TABLE users")).isEqualTo(SqlStatementClassifier.Kind.MUTATION);
        assertThat(classifier.classify("EXPLAIN WITH removed AS (DELETE FROM users RETURNING *) SELECT * FROM removed"))
                .isEqualTo(SqlStatementClassifier.Kind.MUTATION);
        assertThat(classifier.classify("EXPLAIN SELECT * INTO archive FROM users")).isEqualTo(SqlStatementClassifier.Kind.MUTATION);
    }

    @Test
    void requiresConfirmationForUnscopedWriteInsideDataModifyingCte() {
        assertThat(classifier.requiresUnscopedMutationConfirmation(
                "WITH removed AS (DELETE FROM users RETURNING *) SELECT count(*) FROM removed")).isTrue();
        assertThat(classifier.requiresUnscopedMutationConfirmation(
                "WITH bumped AS (UPDATE users SET active = FALSE RETURNING id) SELECT * FROM bumped")).isTrue();
        // A sibling CTE's WHERE scopes only that sibling, never the write.
        assertThat(classifier.requiresUnscopedMutationConfirmation(
                "WITH a AS (DELETE FROM audit RETURNING id), b AS (SELECT id FROM users WHERE active) SELECT * FROM a")).isTrue();
    }

    @Test
    void acceptsScopedWriteInsideDataModifyingCte() {
        assertThat(classifier.requiresUnscopedMutationConfirmation(
                "WITH removed AS (DELETE FROM users WHERE active = FALSE RETURNING *) SELECT count(*) FROM removed")).isFalse();
        assertThat(classifier.requiresUnscopedMutationConfirmation(
                "WITH stale AS (SELECT id FROM users WHERE active = FALSE) SELECT * FROM stale")).isFalse();
    }
}
