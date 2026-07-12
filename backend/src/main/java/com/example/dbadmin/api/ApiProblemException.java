package com.example.dbadmin.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiProblemException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiProblemException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiProblemException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
