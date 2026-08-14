package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.StorageDtos.StorageProfileRequest;
import com.example.dbadmin.dto.StorageDtos.StorageProfileResponse;
import com.example.dbadmin.dto.StorageDtos.StorageTestResponse;
import com.example.dbadmin.model.StorageProfile;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.StorageProfileRepository;
import com.example.dbadmin.storage.BackupStorageRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StorageProfileService {
    public static final String SECRET_MASK = "******";
    private final StorageProfileRepository repository;
    private final CryptoService crypto;
    private final BackupStorageRegistry storage;
    private final AuditRepository audit;

    public StorageProfileService(StorageProfileRepository repository, CryptoService crypto, BackupStorageRegistry storage, AuditRepository audit) {
        this.repository = repository;
        this.crypto = crypto;
        this.storage = storage;
        this.audit = audit;
    }

    public List<StorageProfileResponse> list() {
        return repository.findAll().stream().map(this::response).toList();
    }

    public StorageProfileResponse create(StorageProfileRequest request, String actor) {
        StorageProfile profile = model(0, request, null);
        long id = repository.insert(profile);
        audit.log(actor, "STORAGE_PROFILE_CREATE", profile.name(), profile.type());
        return response(require(id));
    }

    public StorageProfileResponse update(long id, StorageProfileRequest request, String actor) {
        StorageProfile old = require(id);
        if (!old.type().equalsIgnoreCase(request.type())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "STORAGE_PROFILE_TYPE_IMMUTABLE", "文件服务类型创建后不能修改，请新建配置。");
        }
        if (!request.enabled() && old.enabled() && repository.enabledScheduledTaskReferences(id) > 0) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "STORAGE_PROFILE_IN_USE", "该文件服务仍被已启用的定时备份任务引用，请先暂停任务。");
        }
        StorageProfile profile = model(id, request, old);
        repository.update(id, profile);
        audit.log(actor, "STORAGE_PROFILE_UPDATE", profile.name(), profile.type());
        return response(require(id));
    }

    public void delete(long id, String actor) {
        StorageProfile profile = require(id);
        int taskRefs = repository.taskReferences(id);
        int historyRefs = repository.historyReferences(id);
        if (taskRefs > 0 || historyRefs > 0) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "STORAGE_PROFILE_IN_USE",
                    "文件服务仍被备份任务或历史记录引用，暂不能删除。");
        }
        repository.delete(id);
        audit.log(actor, "STORAGE_PROFILE_DELETE", profile.name(), profile.type());
    }

    public StorageTestResponse testDraft(StorageProfileRequest request) throws Exception {
        StorageProfile profile = model(0, request, null);
        try {
            storage.test(profile);
            return new StorageTestResponse(true, "文件服务连接及读写删除测试通过。");
        } catch (Exception error) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "STORAGE_CONNECTION_FAILED", "文件服务测试失败：" + safeMessage(error));
        }
    }

    public StorageTestResponse test(long id, String actor) throws Exception {
        StorageProfile profile = require(id);
        try {
            storage.test(profile);
            repository.updateTest(id, true, "文件服务连接及读写删除测试通过。");
            audit.log(actor, "STORAGE_PROFILE_TEST", profile.name(), "SUCCESS");
            return new StorageTestResponse(true, "文件服务连接及读写删除测试通过。");
        } catch (Exception error) {
            String message = safeMessage(error);
            repository.updateTest(id, false, message);
            audit.log(actor, "STORAGE_PROFILE_TEST", profile.name(), "FAILED: " + message);
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "STORAGE_CONNECTION_FAILED", "文件服务测试失败：" + message);
        }
    }

    public StorageProfile require(long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("文件服务配置不存在：" + id));
    }

    private StorageProfile model(long id, StorageProfileRequest request, StorageProfile old) {
        String type = normalize(request.type());
        if (!Set.of("SMB", "NFS", "FTP", "SFTP").contains(type)) throw new IllegalArgumentException("不支持的文件服务类型：" + request.type());
        int port = request.port() == null ? defaultPort(type) : request.port();
        String basePath = safePath(request.basePath(), "基础目录");
        String username = blank(request.username());
        String smbShare = null;
        String smbDomain = null;
        String exportPath = null;
        Integer uid = null;
        Integer gid = null;
        String groups = null;
        String ftpTls = null;
        String sftpAuth = null;
        String fingerprint = blank(request.serverFingerprint());
        if ("SMB".equals(type)) {
            requireText(username, "SMB 用户名不能为空。");
            smbShare = requireText(blank(request.smbShare()), "SMB 共享名不能为空。");
            if (smbShare.contains("/") || smbShare.contains("\\")) throw new IllegalArgumentException("SMB 共享名不能包含路径分隔符。");
            smbDomain = blank(request.smbDomain());
        } else if ("NFS".equals(type)) {
            if (port != 2049) throw new IllegalArgumentException("当前 NFSv3 直连仅支持标准端口 2049。");
            exportPath = requireText(blank(request.nfsExportPath()), "NFS export 路径不能为空。");
            if (!exportPath.startsWith("/")) throw new IllegalArgumentException("NFS export 路径必须以 / 开头。");
            uid = request.nfsUid();
            gid = request.nfsGid();
            if (uid == null || gid == null) throw new IllegalArgumentException("NFS UID 和 GID 不能为空。");
            groups = request.nfsGroups() == null ? "" : request.nfsGroups().stream().distinct().map(String::valueOf).collect(Collectors.joining(","));
        } else if ("FTP".equals(type)) {
            requireText(username, "FTP 用户名不能为空。");
            ftpTls = normalizeDefault(request.ftpTlsMode(), "NONE");
            if (!Set.of("NONE", "EXPLICIT").contains(ftpTls)) throw new IllegalArgumentException("FTP TLS 模式仅支持 NONE 或 EXPLICIT。");
        } else {
            requireText(username, "SFTP 用户名不能为空。");
            sftpAuth = normalizeDefault(request.sftpAuthMode(), "PASSWORD");
            if (!Set.of("PASSWORD", "PRIVATE_KEY").contains(sftpAuth)) throw new IllegalArgumentException("SFTP 认证模式不受支持。");
            if (!request.skipServerVerification() && fingerprint == null) throw new IllegalArgumentException("SFTP 必须配置服务端 SHA-256 主机密钥指纹，或明确跳过校验。");
        }
        String encryptedPassword = secret(request.password(), old == null ? null : old.encryptedPassword());
        String encryptedPrivateKey = secret(request.privateKey(), old == null ? null : old.encryptedPrivateKey());
        String encryptedPassphrase = secret(request.privateKeyPassphrase(), old == null ? null : old.encryptedPrivateKeyPassphrase());
        if ("SFTP".equals(type) && "PRIVATE_KEY".equals(sftpAuth) && encryptedPrivateKey == null) throw new IllegalArgumentException("SFTP 私钥不能为空。");
        if ("SFTP".equals(type) && "PASSWORD".equals(sftpAuth) && encryptedPassword == null) throw new IllegalArgumentException("SFTP 密码不能为空。");
        Instant now = Instant.now();
        return new StorageProfile(id, request.name().trim(), type, request.host().trim(), port, basePath, username,
                encryptedPassword, smbShare, smbDomain, exportPath, uid, gid, groups, ftpTls, sftpAuth,
                encryptedPrivateKey, encryptedPassphrase, fingerprint, request.skipServerVerification(), request.enabled(),
                old == null ? null : old.lastTestStatus(), old == null ? null : old.lastTestMessage(),
                old == null ? null : old.lastTestedAt(), old == null ? now : old.createdAt(), now);
    }

    private StorageProfileResponse response(StorageProfile profile) {
        List<Integer> groups = profile.nfsGroups() == null || profile.nfsGroups().isBlank() ? List.of()
                : Arrays.stream(profile.nfsGroups().split(",")).map(Integer::valueOf).toList();
        return new StorageProfileResponse(profile.id(), profile.name(), profile.type(), profile.host(), profile.port(),
                profile.basePath(), profile.username(), profile.encryptedPassword() != null, profile.smbShare(), profile.smbDomain(),
                profile.nfsExportPath(), profile.nfsUid(), profile.nfsGid(), groups, profile.ftpTlsMode(), profile.sftpAuthMode(),
                profile.encryptedPrivateKey() != null, profile.encryptedPrivateKeyPassphrase() != null,
                profile.serverFingerprint(), profile.skipServerVerification(), profile.enabled(), profile.lastTestStatus(),
                profile.lastTestMessage(), profile.lastTestedAt(), repository.taskReferences(profile.id()), repository.historyReferences(profile.id()));
    }

    private String secret(String requested, String existing) {
        if (requested == null || SECRET_MASK.equals(requested)) return existing;
        return crypto.encrypt(requested);
    }

    private String safePath(String path, String label) {
        String value = blank(path);
        if (value == null || "/".equals(value)) return "";
        String normalized = value.replace('\\', '/').replaceAll("/+", "/").replaceAll("^/|/$", "");
        for (String segment : normalized.split("/")) if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) throw new IllegalArgumentException(label + "包含不安全的目录片段。");
        return normalized;
    }

    private int defaultPort(String type) { return switch (type) { case "SMB" -> 445; case "NFS" -> 2049; case "SFTP" -> 22; default -> 21; }; }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String normalizeDefault(String value, String fallback) { String result = normalize(value); return result.isBlank() ? fallback : result; }
    private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String requireText(String value, String message) { if (value == null || value.isBlank()) throw new IllegalArgumentException(message); return value; }
    private String safeMessage(Exception error) { return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage(); }
}
