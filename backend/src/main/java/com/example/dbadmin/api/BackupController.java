package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.BackupTaskRequest;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.service.BackupService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    public List<BackupTask> list() {
        return service.list();
    }

    @PostMapping
    public BackupTask create(@Valid @RequestBody BackupTaskRequest request, @RequestHeader(value = "X-User", required = false) String actor) {
        return service.create(request, actor);
    }

    @PostMapping("/{id}/run")
    public BackupTask run(@PathVariable long id, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return service.run(id, actor);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        Path path = service.backupFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
                .body(new FileSystemResource(path));
    }
}
