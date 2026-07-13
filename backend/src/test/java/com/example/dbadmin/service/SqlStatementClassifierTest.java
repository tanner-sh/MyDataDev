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
}
