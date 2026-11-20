package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.MigrationDtos.AiTranslateObjectRequest;
import com.example.dbadmin.dto.MigrationDtos.AiTranslateObjectResponse;
import com.example.dbadmin.dto.MigrationDtos.MigrationArtifactPage;
import com.example.dbadmin.dto.MigrationDtos.MigrationAssessRequest;
import com.example.dbadmin.dto.MigrationDtos.MigrationAssessmentResponse;
import com.example.dbadmin.dto.MigrationDtos.MigrationJobDetail;
import com.example.dbadmin.dto.MigrationDtos.MigrationJobPage;
import com.example.dbadmin.dto.MigrationDtos.MigrationRetryRequest;
import com.example.dbadmin.dto.MigrationDtos.MigrationStartRequest;
import com.example.dbadmin.dto.MigrationDtos.MigrationVerificationReport;
import com.example.dbadmin.dto.MigrationDtos.MigrationVerifyRequest;
import com.example.dbadmin.dto.MigrationDtos.RegisterStorageArtifactRequest;
import com.example.dbadmin.dto.MigrationDtos.SupportedDialectMatrix;
import com.example.dbadmin.model.MigrationArtifact;
import com.example.dbadmin.model.MigrationJob;
import com.example.dbadmin.service.migration.MigrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/migrations")
public class MigrationController {
    private final MigrationService service;
    private final ConnectionAccessService access;

    public MigrationController(MigrationService service, ConnectionAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/dialects")
    public SupportedDialectMatrix dialects() {
        return service.supportedDialects();
    }

    @PostMapping(value = "/artifacts/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public MigrationArtifact uploadArtifact(
            HttpServletRequest request,
            @RequestParam String filename,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        access.requireAnyConnection(ConnectionPermission.DATA_WRITE);
        Long ownerId = access.currentIdentity().map(id -> id.userId()).orElse(null);
        return service.uploadArtifactStream(request.getInputStream(), filename, request.getContentLengthLong(), ownerId, actor);
    }

    @PostMapping("/artifacts/from-storage")
    public MigrationArtifact registerFromStorage(
            @Valid @RequestBody RegisterStorageArtifactRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        access.requireAnyConnection(ConnectionPermission.DATA_WRITE);
        Long ownerId = access.currentIdentity().map(id -> id.userId()).orElse(null);
        return service.registerFromStorage(request, ownerId, actor);
    }

    @GetMapping("/artifacts")
    public MigrationArtifactPage listArtifacts(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        Long ownerFilter = access.currentIdentity()
                .filter(id -> !"ADMIN".equals(id.role()))
                .map(id -> id.userId())
                .orElse(null);
        return service.listArtifacts(ownerFilter, page, pageSize);
    }

    @GetMapping("/artifacts/{id}")
    public MigrationArtifact getArtifact(@PathVariable long id) {
        MigrationArtifact artifact = service.requireArtifact(id);
        requireArtifactOwnership(artifact);
        return artifact;
    }

    @GetMapping("/artifacts/{id}/download")
    public ResponseEntity<StreamingResponseBody> downloadArtifact(@PathVariable long id) throws Exception {
        MigrationArtifact artifact = service.requireArtifact(id);
        requireArtifactOwnership(artifact);
        Path file = Path.of(artifact.filePath());
        if (!Files.isRegularFile(file)) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "ARTIFACT_FILE_MISSING", "制品文件已不存在于磁盘。");
        }
        long size = Files.size(file);
        StreamingResponseBody body = output -> {
            byte[] buf = new byte[128 * 1024];
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) output.write(buf, 0, n);
                }
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(size);
        headers.setContentDisposition(ContentDisposition.attachment().filename(artifact.name(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @DeleteMapping("/artifacts/{id}")
    public ResponseEntity<Void> deleteArtifact(
            @PathVariable long id,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        MigrationArtifact artifact = service.requireArtifact(id);
        requireArtifactOwnership(artifact);
        service.deleteArtifact(id, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assess")
    public MigrationAssessmentResponse assess(
            @Valid @RequestBody MigrationAssessRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        if (request.sourceConnectionId() != null) {
            access.require(request.sourceConnectionId(), ConnectionPermission.VIEW_METADATA);
            access.require(request.sourceConnectionId(), ConnectionPermission.QUERY);
            access.require(request.sourceConnectionId(), ConnectionPermission.EXPORT);
        }
        if (request.sourceArtifactId() != null) {
            requireArtifactOwnership(service.requireArtifact(request.sourceArtifactId()));
        }
        if (request.targetConnectionId() != null) {
            access.require(request.targetConnectionId(), ConnectionPermission.DDL);
            access.require(request.targetConnectionId(), ConnectionPermission.DATA_WRITE);
        }
        return service.assess(request, actor);
    }

    @PostMapping("/ai-translate")
    public AiTranslateObjectResponse aiTranslate(
            @Valid @RequestBody AiTranslateObjectRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        requirePlanAccess(request.planToken(), false);
        return service.aiTranslateObject(request, actor);
    }

    @GetMapping("/plans/{planToken}/tables/{tableId}")
    public com.example.dbadmin.dto.MigrationDtos.TableAssessment getPlanTable(
            @PathVariable String planToken,
            @PathVariable String tableId
    ) {
        requirePlanAccess(planToken, false);
        return service.getPlanTableAssessment(planToken, tableId);
    }

    @GetMapping("/plans/{planToken}/ddl")
    public ResponseEntity<StreamingResponseBody> downloadPlanDdl(@PathVariable String planToken) {
        requirePlanAccess(planToken, false);
        StreamingResponseBody body = out -> {
            try {
                service.writePlanDdl(planToken, out);
            } catch (Exception e) {
                throw new java.io.IOException("导出完整 DDL 失败：" + e.getMessage(), e);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("migration-ddl.sql", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("text/plain; charset=utf-8"))
                .body(body);
    }

    @PostMapping("/jobs")
    public ResponseEntity<MigrationJob> start(
            @Valid @RequestBody MigrationStartRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        requirePlanAccess(request.planToken(), true);
        Long ownerId = access.currentIdentity().map(id -> id.userId()).orElse(null);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(request, actor, ownerId));
    }

    @GetMapping("/jobs")
    public MigrationJobPage listJobs(
            @RequestParam(required = false) Long connectionId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        if (connectionId != null) {
            access.require(connectionId, ConnectionPermission.VIEW_METADATA);
        }
        return service.listJobs(connectionId, page, pageSize);
    }

    @GetMapping("/jobs/{id}")
    public MigrationJobDetail getJob(@PathVariable long id) {
        MigrationJobDetail detail = service.getJobDetail(id);
        requireJobAccess(detail.job());
        return detail;
    }

    @PostMapping("/jobs/{id}/cancel")
    public MigrationJob cancelJob(
            @PathVariable long id,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        requireJobAccess(service.requireJob(id));
        return service.cancel(id, actor);
    }

    @PostMapping("/jobs/{id}/retry-failed")
    public ResponseEntity<MigrationJob> retryFailed(
            @PathVariable long id,
            @RequestBody(required = false) MigrationRetryRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        MigrationJob job = service.requireJob(id);
        if (job.sourceConnectionId() != null) {
            access.require(job.sourceConnectionId(), ConnectionPermission.QUERY);
            access.require(job.sourceConnectionId(), ConnectionPermission.EXPORT);
        }
        if (job.targetConnectionId() != null) {
            access.require(job.targetConnectionId(), ConnectionPermission.DDL);
            access.require(job.targetConnectionId(), ConnectionPermission.DATA_WRITE);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retryFailed(id, request, actor));
    }

    @PostMapping("/jobs/{id}/verify")
    public MigrationVerificationReport verifyJob(
            @PathVariable long id,
            @RequestBody(required = false) MigrationVerifyRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        MigrationJob job = service.requireJob(id);
        if (job.sourceConnectionId() != null) access.require(job.sourceConnectionId(), ConnectionPermission.QUERY);
        if (job.targetConnectionId() != null) access.require(job.targetConnectionId(), ConnectionPermission.QUERY);
        return service.verify(id, request, actor);
    }

    @GetMapping(value = "/jobs/{id}/verify/report.md", produces = "text/markdown; charset=UTF-8")
    public ResponseEntity<String> downloadMarkdownReport(@PathVariable long id) throws Exception {
        requireJobAccess(service.requireJob(id));
        String markdown = service.renderVerificationMarkdown(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("migration-verify-job-" + id + ".md", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(markdown);
    }

    private void requirePlanAccess(String planToken, boolean writeTarget) {
        var endpoints = service.inspectPlanEndpoints(planToken);
        if (endpoints.sourceConnectionId() != null) {
            access.require(endpoints.sourceConnectionId(), ConnectionPermission.VIEW_METADATA);
            access.require(endpoints.sourceConnectionId(), ConnectionPermission.QUERY);
            access.require(endpoints.sourceConnectionId(), ConnectionPermission.EXPORT);
        }
        if (endpoints.sourceArtifactId() != null) {
            requireArtifactOwnership(service.requireArtifact(endpoints.sourceArtifactId()));
        }
        if (endpoints.targetConnectionId() != null) {
            if (writeTarget) {
                access.require(endpoints.targetConnectionId(), ConnectionPermission.DDL);
                access.require(endpoints.targetConnectionId(), ConnectionPermission.DATA_WRITE);
            } else {
                access.require(endpoints.targetConnectionId(), ConnectionPermission.VIEW_METADATA);
            }
        }
    }

    private void requireArtifactOwnership(MigrationArtifact artifact) {
        var identity = access.currentIdentity().orElse(null);
        if (identity == null || "ADMIN".equals(identity.role())) return;
        if (artifact.ownerUserId() != null && !artifact.ownerUserId().equals(identity.userId())) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "ARTIFACT_ACCESS_DENIED", "无权访问该迁移制品。");
        }
    }

    private void requireJobAccess(MigrationJob job) {
        if (job.sourceConnectionId() != null) access.require(job.sourceConnectionId(), ConnectionPermission.VIEW_METADATA);
        if (job.targetConnectionId() != null) access.require(job.targetConnectionId(), ConnectionPermission.VIEW_METADATA);
    }
}
