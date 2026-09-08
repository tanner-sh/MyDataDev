package com.example.dbadmin.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

import java.sql.SQLException;
import java.time.format.DateTimeParseException;
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
    void staysQuietWhenTheClientHungUpMidResponse() {
        // 浏览器刷新、切连接让 SSE 重连、代理掐掉空闲长连接都会走到这里。此前它落进兜底分支，
        // 于是应用起着不动也会定期刷一条带整页栈的 ERROR。
        ResponseEntity<Map<String, Object>> response = handler.io(
                new AsyncRequestNotUsableException("Servlet container error notification for disconnected client"));

        // 连接都没了，没有谁还能收下这个响应体。
        assertThat(response).isNull();
    }

    @Test
    void stillReportsIoFailuresThatAreNotAClientHangUp() {
        java.io.IOException error = new java.io.IOException("Input/output error");
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.io.FileInputStream", "read", "FileInputStream.java", 1)
        });

        ResponseEntity<Map<String, Object>> response = handler.io(error);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("code", "INTERNAL_ERROR");
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

    /*
      DateTimeParseException 继承的是 DateTimeException 而不是 IllegalArgumentException，
      于是它绕过了「非法入参 → 400」那条分支，被压成一句「服务器内部错误，请稍后重试」——
      而它描述的是「你填的这个值不是日期」，重试多少次都不会好。
    */
    @Test
    void reportsAnUnparsableDateTimeAsABadRequestInsteadOfAnInternalError() {
        ResponseEntity<Map<String, Object>> response = handler.dateTime(
                new DateTimeParseException("字段 CREATED_AT 的值无法识别：「昨天」", "昨天", 0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "BAD_REQUEST");
        assertThat(String.valueOf(response.getBody().get("message"))).contains("CREATED_AT");
    }

    /* Spring 包一层之后，上面那条 SQLException 分支就不认了，驱动原文只会留在服务端日志里。 */
    @Test
    void unwrapsASpringDataAccessFailureToTheDriverMessage() {
        ResponseEntity<Map<String, Object>> response = handler.dataAccess(new DataIntegrityViolationException(
                "could not execute statement", new SQLException("ORA-01407: cannot update to NULL", "23000")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "SQL_ERROR");
        assertThat(response.getBody()).containsEntry("sqlState", "23000");
        assertThat(String.valueOf(response.getBody().get("message"))).contains("ORA-01407");
    }

    /* 连不上库仍然是服务端的事：要 traceId，也要那条带栈的 ERROR 日志。 */
    @Test
    void stillTreatsADatabaseOutageWrappedBySpringAsAnInternalError() {
        ResponseEntity<Map<String, Object>> response = handler.dataAccess(new QueryTimeoutException(
                "connection lost", new SQLException("Communications link failure", "08S01")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("code", "INTERNAL_ERROR");
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
