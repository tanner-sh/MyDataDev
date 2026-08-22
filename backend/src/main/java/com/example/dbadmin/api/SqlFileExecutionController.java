package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionPage;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionStartRequest;
import com.example.dbadmin.service.DataImportService;
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

    private final DataImportService dataImports;

    public SqlFileExecutionController(
            SqlFileExecutionService service,
            DataImportService dataImports
    ) {
        this.service = service;
        this.dataImports = dataImports;
    }

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

    /**
     * 上传一份 CSV，转成 INSERT 脚本并注册成待执行任务。
     *
     * <p>转换完成后与 SQL 文件走同一条管线：同样的 start/cancel/进度查询，因此百万行导入
     * 自然获得排队、进度与取消能力。</p>
     */
    @PostMapping(value = "/csv-imports", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<SqlFileExecutionResponse> uploadCsv(
            @RequestParam long connectionId,
            @RequestParam(required = false) String schemaName,
            @RequestParam String tableName,
            @RequestParam(required = false) String fileName,
            @RequestHeader(value = "X-User", required = false) String actor,
            HttpServletRequest request
    ) throws Exception {
        try (var input = request.getInputStream()) {
            return ResponseEntity.ok(dataImports.uploadCsv(connectionId, schemaName, tableName, fileName, input, actor));
        }
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
