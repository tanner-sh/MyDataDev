package com.example.dbadmin.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void hidesRawDetailOfUnexpectedFailuresBehindATraceableCode() {
        ResponseEntity<Map<String, Object>> response =
                handler.generic(new NullPointerException("jdbc:mysql://10.0.0.7:3306/secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo("INTERNAL_ERROR");
        assertThat(String.valueOf(body.get("message")))
                .doesNotContain("jdbc:mysql")
                .doesNotContain("NullPointerException")
                .contains(String.valueOf(body.get("traceId")));
    }

    @Test
    void reportsAnUnreachableTargetDatabaseAsServiceUnavailable() {
        ResponseEntity<Map<String, Object>> response =
                handler.sql(new SQLException("Communications link failure", "08S01"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("code", "TARGET_DATABASE_UNAVAILABLE");
    }

    @Test
    void keepsDriverTextForStatementErrorsAndStillReportsThemAsBadRequest() {
        ResponseEntity<Map<String, Object>> response =
                handler.sql(new SQLException("Unknown column 'nope' in 'field list'", "42S22"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "SQL_ERROR");
        assertThat(String.valueOf(response.getBody().get("message"))).contains("Unknown column");
    }

    @Test
    void reportsAnUnknownPathAsNotFoundInsteadOfAnInternalError() {
        ResponseEntity<Map<String, Object>> response =
                handler.notFound(new NoResourceFoundException(HttpMethod.GET, "/h2-console"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
        assertThat(response.getBody()).doesNotContainKey("traceId");
    }

    @Test
    void neverReturnsABareExceptionClassNameForBlankMessages() {
        ResponseEntity<Map<String, Object>> response = handler.badRequest(new IllegalStateException());

        assertThat(String.valueOf(response.getBody().get("message")))
                .isNotBlank()
                .doesNotContain("IllegalStateException");
    }
}
