package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
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
import com.example.dbadmin.repo.AuditRepository;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
    private final BackupService service;
    private final ConnectionAccessService access;
    private final AuditRepository audit;

    public BackupController(BackupService service, ConnectionAccessService access, AuditRepository audit) {
        this.service = service;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping
    public List<BackupTask> list(@RequestParam(value = "connectionId", required = false) Long connectionId) {
        if (connectionId != null) access.require(connectionId, ConnectionPermission.BACKUP_RESTORE);
        return access.visibleBackupTasks(service.list(connectionId));
    }

    @GetMapping("/page")
    public BackupTaskPage page(@RequestParam long connectionId,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer pageSize) {
        access.require(connectionId, ConnectionPermission.BACKUP_RESTORE);
        return service.page(connectionId, keyword, status, page, pageSize);
    }

    @GetMapping("/history")
    public BackupHistoryPage historyByConnection(@RequestParam long connectionId,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer pageSize) {
        access.require(connectionId, ConnectionPermission.BACKUP_RESTORE);
        return service.historyByConnection(connectionId, page, pageSize);
    }

    @PostMapping
    public BackupTask create(@Valid @RequestBody BackupTaskRequest request, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.BACKUP_RESTORE);
        return service.create(request, actor);
    }

    @PutMapping("/{id}")
    public BackupTask update(@PathVariable long id, @Valid @RequestBody BackupTaskRequest request, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        access.requireBackupTask(id);
        access.require(request.connectionId(), ConnectionPermission.BACKUP_RESTORE);
        return service.update(id, request, actor);
    }

    @PostMapping("/schedule/preview")
    public CronPreviewResponse previewSchedule(@Valid @RequestBody CronPreviewRequest request) {
        return service.previewSchedule(request.cron(), request.zoneId());
    }

    @PatchMapping("/{id}/enabled")
    public BackupTask setEnabled(@PathVariable long id, @Valid @RequestBody BackupEnabledRequest request, @RequestHeader(value = "X-User", required = false) String actor) {
        access.requireBackupTask(id);
        return service.setEnabled(id, request.enabled(), actor);
    }

    @DeleteMapping("/{id}")
    public MessageResponse delete(@PathVariable long id, @RequestParam(value = "deleteFile", defaultValue = "false") boolean deleteFile, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        access.requireBackupTask(id);
        service.delete(id, deleteFile, actor);
        return new MessageResponse(true, "Backup task deleted");
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<BackupRunResponse> run(@PathVariable long id, @RequestHeader(value = "X-User", required = false) String actor) {
        access.requireBackupTask(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.enqueueWithExecution(id, actor));
    }

    @PostMapping("/{id}/history/{historyId}/cancel")
    public BackupHistory cancel(@PathVariable long id, @PathVariable long historyId,
                                @RequestHeader(value = "X-User", required = false) String actor) {
        access.requireBackupTask(id);
        return service.cancel(id, historyId, actor);
    }

    @PostMapping("/{id}/history/{historyId}/retry-upload")
    public ResponseEntity<BackupHistory> retryUpload(@PathVariable long id, @PathVariable long historyId,
                                                      @RequestHeader(value = "X-User", required = false) String actor) {
        access.requireBackupTask(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retryUpload(id, historyId, actor));
    }

    /**
     * 校验一份备份文件：完整读一遍并比对 SHA-256。
     *
     * <p>用 POST 而不是 GET：它要把整份文件过一遍网络和磁盘，不该被当成可缓存、可预取的读操作。</p>
     */
    @PostMapping("/{id}/history/{historyId}/verify")
    public com.example.dbadmin.dto.ApiDtos.BackupVerificationResponse verify(
            @PathVariable long id, @PathVariable long historyId,
            @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        access.requireBackupTask(id);
        return service.verifyHistory(id, historyId, actor);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable long id,
            @RequestHeader(value = "X-User", required = false) String actor) {
        BackupTask task = access.requireBackupTask(id);
        BackupService.DownloadInfo info = service.backupDownloadInfo(id);
        audit.onConnection(actor, "BACKUP_DOWNLOAD", task.connectionId(), "backup:" + task.name(),
                "task=" + id + ", file=" + info.fileName() + ", size=" + info.size());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(info.fileName(), StandardCharsets.UTF_8).build().toString());
        if (info.size() >= 0) response.contentLength(info.size());
        return response.body(output -> {
            try { service.writeBackupFile(id, output); }
            catch (java.io.IOException error) { throw error; }
            catch (Exception error) { throw new java.io.IOException(error); }
        });
    }

    @GetMapping("/{id}/history")
    public BackupHistoryPage history(
            @PathVariable long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        access.requireBackupTask(id);
        return service.history(id, page, pageSize);
    }

    @GetMapping("/{id}/history/{historyId}/download")
    public ResponseEntity<StreamingResponseBody> downloadHistory(@PathVariable long id, @PathVariable long historyId,
            @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        BackupTask task = access.requireBackupTask(id);
        BackupService.DownloadInfo info = service.historyDownloadInfo(id, historyId);
        audit.onConnection(actor, "BACKUP_HISTORY_DOWNLOAD", task.connectionId(), "backup:" + task.name(),
                "task=" + id + ", history=" + historyId + ", file=" + info.fileName() + ", size=" + info.size());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(info.fileName(), StandardCharsets.UTF_8).build().toString());
        if (info.size() >= 0) response.contentLength(info.size());
        return response.body(output -> {
            try { service.writeHistoryFile(id, historyId, output); }
            catch (java.io.IOException error) { throw error; }
            catch (Exception error) { throw new java.io.IOException(error); }
        });
    }

    @DeleteMapping("/{id}/history/{historyId}")
    public MessageResponse deleteHistory(@PathVariable long id, @PathVariable long historyId, @RequestParam(value = "deleteFile", defaultValue = "false") boolean deleteFile, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        access.requireBackupTask(id);
        service.deleteHistory(id, historyId, deleteFile, actor);
        return new MessageResponse(true, "Backup history deleted");
    }
}
