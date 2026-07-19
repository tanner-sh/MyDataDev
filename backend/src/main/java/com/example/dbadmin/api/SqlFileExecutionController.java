package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionPage;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionStartRequest;
import com.example.dbadmin.service.SqlFileExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sql-file-executions")
public class SqlFileExecutionController {
    private final SqlFileExecutionService service;

    public SqlFileExecutionController(SqlFileExecutionService service) { this.service = service; }

    @PostMapping(value = "/uploads", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<SqlFileExecutionResponse> upload(
            @RequestParam long connectionId,
            @RequestParam String fileName,
            @RequestHeader(value = "X-User", required = false) String actor,
            HttpServletRequest request
    ) throws Exception {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                service.upload(connectionId, fileName, request.getContentLengthLong(), request.getInputStream(), actor));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<SqlFileExecutionResponse> start(
            @PathVariable long id,
            @RequestBody(required = false) SqlFileExecutionStartRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                service.start(id, request == null ? null : request.productionConfirmation(), actor));
    }

    @PostMapping("/{id}/cancel")
    public SqlFileExecutionResponse cancel(@PathVariable long id,
                                           @RequestHeader(value = "X-User", required = false) String actor) {
        return service.cancel(id, actor);
    }

    @GetMapping("/{id}")
    public SqlFileExecutionResponse get(@PathVariable long id) { return service.get(id); }

    @GetMapping
    public SqlFileExecutionPage list(@RequestParam(required = false) Long connectionId,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer pageSize) {
        return service.list(connectionId, page, pageSize);
    }
}
