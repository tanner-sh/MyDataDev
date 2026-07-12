package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionGuardTest {
    private final ExecutionGuard guard = new ExecutionGuard();

    @Test
    void readonlyConnectionAlwaysRejectsWrites() {
        DbConnection connection = connection("只读库", "dev", true);

        assertThatThrownBy(() -> guard.requireMutationAllowed(connection, "只读库"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(problem.code()).isEqualTo("READONLY_CONNECTION");
                });
    }

    @Test
    void productionConnectionRequiresExactConnectionNameForWrites() {
        DbConnection connection = connection("生产主库", "prod", false);

        assertThatThrownBy(() -> guard.requireMutationAllowed(connection, "生产库"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(problem.code()).isEqualTo("PRODUCTION_CONFIRMATION_REQUIRED");
                    assertThat(problem.details()).containsEntry("confirmationText", "生产主库");
                });
        assertThatCode(() -> guard.requireMutationAllowed(connection, "生产主库")).doesNotThrowAnyException();
    }

    @Test
    void arbitraryProductionQueriesRequireConfirmationBecauseFunctionsMayHaveSideEffects() {
        assertThatThrownBy(() -> guard.requireQueryAllowed(connection("生产主库", "prod", false), SqlStatementClassifier.Kind.QUERY, null))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("PRODUCTION_CONFIRMATION_REQUIRED"));
        assertThatCode(() -> guard.requireQueryAllowed(connection("生产主库", "prod", false), SqlStatementClassifier.Kind.QUERY, "生产主库"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.requireQueryAllowed(connection("开发库", "dev", false), SqlStatementClassifier.Kind.QUERY, null))
                .doesNotThrowAnyException();
    }

    private DbConnection connection(String name, String environment, boolean readonly) {
        return new DbConnection(1L, name, "h2", "jdbc:h2:mem:test", "sa", "", environment, readonly, Instant.now(), Instant.now());
    }
}
