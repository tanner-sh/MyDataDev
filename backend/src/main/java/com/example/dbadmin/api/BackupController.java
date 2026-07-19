package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.BackupEnabledRequest;
import com.example.dbadmin.dto.ApiDtos.BackupTaskRequest;
import com.example.dbadmin.dto.ApiDtos.CronPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.CronPreviewResponse;
import com.example.dbadmin.dto.ApiDtos.BackupHistoryPage;
import com.example.dbadmin.dto.ApiDtos.BackupRunResponse;
import com.example.dbadmin.dto.ApiDtos.BackupTaskPage;
import com.example.dbadmin.dto.ApiDtos.MessageResponse;
import com.example.dbadmin.model.BackupHistory;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.service.BackupService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @GetMapping
    public List<BackupTask> list(@RequestParam(value = "connectionId", required = false) Long connectionId) {
        return service.list(connectionId);
    }

    @GetMapping("/page")
    public BackupTaskPage page(@RequestParam long connectionId,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer pageSize) {
        return service.page(connectionId, keyword, status, page, pageSize);
    }

    @GetMapping("/history")
    public BackupHistoryPage historyByConnection(@RequestParam long connectionId,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer pageSize) {
        return service.historyByConnection(connectionId, page, pageSize);
    }

    @PostMapping
    public BackupTask create(@Valid @RequestBody BackupTaskRequest request, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return service.create(request, actor);
    }

    @PutMapping("/{id}")
    public BackupTask update(@PathVariable long id, @Valid @RequestBody BackupTaskRequest request, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return service.update(id, request, actor);
    }

    @PostMapping("/schedule/preview")
    public CronPreviewResponse previewSchedule(@Valid @RequestBody CronPreviewRequest request) {
        return service.previewSchedule(request.cron());
    }

    @PatchMapping("/{id}/enabled")
    public BackupTask setEnabled(@PathVariable long id, @Valid @RequestBody BackupEnabledRequest request, @RequestHeader(value = "X-User", required = false) String actor) {
        return service.setEnabled(id, request.enabled(), actor);
    }

    @DeleteMapping("/{id}")
    public MessageResponse delete(@PathVariable long id, @RequestParam(value = "deleteFile", defaultValue = "false") boolean deleteFile, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        service.delete(id, deleteFile, actor);
        return new MessageResponse(true, "Backup task deleted");
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<BackupRunResponse> run(@PathVariable long id, @RequestHeader(value = "X-User", required = false) String actor) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.enqueueWithExecution(id, actor));
    }

    @PostMapping("/{id}/history/{historyId}/cancel")
    public BackupHistory cancel(@PathVariable long id, @PathVariable long historyId,
                                @RequestHeader(value = "X-User", required = false) String actor) {
        return service.cancel(id, historyId, actor);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        Path path = service.backupFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
                .body(new FileSystemResource(path));
    }

    @GetMapping("/{id}/history")
    public BackupHistoryPage history(
            @PathVariable long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        return service.history(id, page, pageSize);
    }

    @GetMapping("/{id}/history/{historyId}/download")
    public ResponseEntity<Resource> downloadHistory(@PathVariable long id, @PathVariable long historyId) {
        Path path = service.historyFile(id, historyId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
                .body(new FileSystemResource(path));
    }

    @DeleteMapping("/{id}/history/{historyId}")
    public MessageResponse deleteHistory(@PathVariable long id, @PathVariable long historyId, @RequestParam(value = "deleteFile", defaultValue = "false") boolean deleteFile, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        service.deleteHistory(id, historyId, deleteFile, actor);
        return new MessageResponse(true, "Backup history deleted");
    }
}
