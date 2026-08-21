package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.ActiveOperations;
import com.example.dbadmin.dto.ApiDtos.RestoreJobPage;
import com.example.dbadmin.dto.ApiDtos.RestorePreflightRequest;
import com.example.dbadmin.dto.ApiDtos.RestorePreflightResponse;
import com.example.dbadmin.dto.ApiDtos.RestoreStartRequest;
import com.example.dbadmin.model.RestoreJob;
import com.example.dbadmin.model.RestoreUpload;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import com.example.dbadmin.service.RestoreService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@RequestMapping("/api/restores")
public class RestoreController {
    private final RestoreService service;
    private final BackupHistoryRepository histories;
    private final SqlFileExecutionRepository sqlFiles;

    public RestoreController(RestoreService service, BackupHistoryRepository histories, SqlFileExecutionRepository sqlFiles) {
        this.service = service;
        this.histories = histories;
        this.sqlFiles = sqlFiles;
    }

    @PostMapping(value = "/uploads", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public RestoreUpload upload(HttpServletRequest request,
                                @RequestParam String filename,
                                @RequestParam String fileFormat,
                                @RequestParam(required = false) String sourceDbType) throws Exception {
        return service.uploadStream(request.getInputStream(), filename, fileFormat, sourceDbType, request.getContentLengthLong());
    }

    @PostMapping("/preflight")
    public RestorePreflightResponse preflight(@Valid @RequestBody RestorePreflightRequest request) throws Exception {
        return service.preflight(request);
    }

    @PostMapping
    public ResponseEntity<RestoreJob> start(@Valid @RequestBody RestoreStartRequest request,
                                            @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(request, actor));
    }

    @GetMapping
    public RestoreJobPage list(@RequestParam(required = false) Long connectionId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer pageSize) {
        return service.list(connectionId, page, pageSize);
    }

    @GetMapping("/{id}")
    public RestoreJob get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/cancel")
    public RestoreJob cancel(@PathVariable long id,
                             @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return service.cancel(id, actor);
    }

    /**
     * Reports every background job still in flight for one connection. The
     * header's background-task indicator polls this, so it covers SQL-file
     * executions too rather than only backups and restores.
     */
    @GetMapping("/operations/active")
    public ActiveOperations active(@RequestParam(required = false) Long connectionId) {
        return service.active(connectionId, histories.findActive(connectionId), sqlFiles.findActive(connectionId));
    }
}
