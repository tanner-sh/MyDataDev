package com.example.dbadmin.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiProblemException.class)
    public ResponseEntity<Map<String, Object>> problem(ApiProblemException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", e.code());
        body.put("message", safeMessage(e));
        body.putAll(e.details());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "code", "VALIDATION_FAILED",
                "message", String.valueOf(e.getBindingResult().getAllErrors().get(0).getDefaultMessage())));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        log.debug("Rejected request", e);
        return ResponseEntity.badRequest().body(Map.of("ok", false, "code", "BAD_REQUEST", "message", safeMessage(e)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        // A wrong URL is the caller's mistake, not a server failure. Without this
        // the generic handler turns every unknown path into a 500 plus an ERROR
        // log line, which the Web bundle hits constantly because it also serves
        // the static UI.
        log.debug("No handler for {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("ok", false, "code", "NOT_FOUND", "message", "请求的地址不存在。"));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> sql(SQLException e) {
        String sqlState = String.valueOf(e.getSQLState());
        // SQLState class 08 is "connection exception": the target database could
        // not be reached, which is not the caller's mistake.
        boolean connectionFailure = sqlState.startsWith("08");
        HttpStatus status = connectionFailure ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        if (connectionFailure) log.warn("Target database unreachable sqlState={}", sqlState, e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", connectionFailure ? "TARGET_DATABASE_UNAVAILABLE" : "SQL_ERROR");
        // The driver's own text names the offending column or syntax, so it is
        // genuinely useful to the operator and is kept verbatim.
        body.put("message", databaseMessage(e));
        body.put("sqlState", sqlState);
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        // Unexpected failures used to be returned verbatim and never logged,
        // leaving no server-side trace and exposing raw exception text (JDBC
        // URLs, file paths) or a bare class name to the browser.
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled API failure traceId={}", traceId, e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", "INTERNAL_ERROR");
        body.put("message", "服务器内部错误，请稍后重试。如需反馈请提供错误编号 " + traceId + "。");
        body.put("traceId", traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "请求无法完成，请检查输入后重试。" : e.getMessage();
    }

    private String databaseMessage(SQLException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "数据库返回了未附带描述的错误。" : e.getMessage();
    }
}
