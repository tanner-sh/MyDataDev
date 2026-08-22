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

    @Test
    void acceptsThePercentEncodedConfirmationTheBrowserIsForcedToSend() {
        // HTTP 头值只能是 ISO-8859-1，中文连接名必须先 encodeURIComponent 才能发出去。
        DbConnection connection = connection("生产订单库", "prod", false);

        assertThatCode(() -> guard.requireMutationAllowed(
                connection, "%E7%94%9F%E4%BA%A7%E8%AE%A2%E5%8D%95%E5%BA%93"
        )).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireMutationAllowed(
                connection, "%E7%94%9F%E4%BA%A7%E5%BA%93"
        )).isInstanceOf(ApiProblemException.class);
    }

    private DbConnection connection(String name, String environment, boolean readonly) {
        return new DbConnection(1L, name, "h2", "jdbc:h2:mem:test", "sa", "", environment, readonly, Instant.now(), Instant.now());
    }
}
