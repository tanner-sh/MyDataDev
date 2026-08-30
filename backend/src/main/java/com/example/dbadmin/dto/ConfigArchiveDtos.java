package com.example.dbadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 连接配置归档。
 *
 * <p>只搬连接（含 SSH 隧道），不搬用户、Agent、备份任务和审计：那些要么绑定本机路径与密钥，
 * 要么搬过去就等于把凭据体系复制一份，风险远大于便利。</p>
 */
public final class ConfigArchiveDtos {
    private ConfigArchiveDtos() {
    }

    /**
     * 归档文件的信封：载荷是密文，其余字段是解密所需的公开参数。
     *
     * <p>放在 dto 而不是加密服务里，是因为它同时是接口的请求/响应体 —— 让 api 层去 import
     * service 里的内部类会把依赖方向拧反。</p>
     */
    public record ArchiveEnvelope(
            String format,
            int version,
            String kdf,
            int iterations,
            String salt,
            String cipher,
            String iv,
            String payload
    ) {
    }

    /** 归档里的一条 SSH 隧道配置。密钥材料在归档内部是明文，整体由口令加密保护。 */
    public record ArchivedSshTunnel(
            boolean enabled,
            String host,
            int port,
            String username,
            String authMode,
            String password,
            String privateKey,
            String passphrase,
            String serverFingerprint,
            boolean skipHostKeyCheck
    ) {
    }

    public record ArchivedConnection(
            String name,
            String dbType,
            String jdbcUrl,
            String username,
            String password,
            String environment,
            boolean readonly,
            String groupName,
            String tags,
            String defaultSchema,
            String initSql,
            String description,
            ArchivedSshTunnel ssh
    ) {
    }

    /** 归档载荷；加密后放进 ConfigArchiveCrypto.Envelope 的 payload。 */
    public record ArchivePayload(
            Instant exportedAt,
            String exportedBy,
            String sourceVersion,
            List<ArchivedConnection> connections
    ) {
    }

    public record ArchiveExportRequest(
            @NotBlank @Size(min = 12, max = 200) String passphrase,
            /** 要导出的连接 id；为空表示导出全部有权限的连接。 */
            List<Long> connectionIds
    ) {
    }

    /** 同名连接的处理方式。 */
    public enum ArchiveConflictMode {
        /** 跳过，保留本机现有的那条。 */
        SKIP,
        /** 以「原名 (导入)」这样的后缀新建一条。 */
        RENAME
    }

    public record ArchiveImportRequest(
            @NotBlank @Size(min = 12, max = 200) String passphrase,
            @NotNull ArchiveEnvelope archive,
            ArchiveConflictMode onConflict
    ) {
    }

    public record ArchiveImportResult(
            int imported,
            int skipped,
            int renamed,
            List<String> importedNames,
            List<String> skippedNames
    ) {
    }
}
