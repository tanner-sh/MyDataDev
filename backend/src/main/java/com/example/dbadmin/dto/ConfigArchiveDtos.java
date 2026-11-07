package com.example.dbadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 连接配置归档。
 *
 * <p>搬连接（含 SSH 隧道），可选地连同 MCP 配置（全局上限与 Agent）一起搬。不搬用户、备份任务和审计：
 * 那些要么绑定本机路径与密钥，要么搬过去就等于把凭据体系复制一份，风险远大于便利。</p>
 *
 * <p>MCP Agent 能搬，是因为它的凭据在服务端只是一个 BCrypt 哈希，不依赖本机主密钥：原样带过去，
 * Agent 手里那把 Key 在目标端照样能用，用户不必去改每个 AI 客户端的配置。</p>
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

    /**
     * 归档里的一条 MCP 授权。
     *
     * <p>按连接<b>名</b>而不是 id 记：目标端的连接 id 必然不同，按 id 记的话授权会落到一条不相干的连接上。</p>
     */
    public record ArchivedMcpGrant(String connectionName, String accessLevel) {
    }

    /** 归档里的一个 MCP Agent。{@code keyHash} 是 BCrypt 哈希，原始 Key 从来不在服务端。 */
    public record ArchivedMcpAgent(
            String agentId,
            String keyHash,
            boolean enabled,
            boolean allowProduction,
            List<ArchivedMcpGrant> grants
    ) {
    }

    /**
     * 归档里的 MCP 配置。
     *
     * <p>刻意不带「允许的 Origin」：那是源端部署地址相关的跨域白名单，搬到另一台装机上没有意义，
     * 只会悄悄放宽目标端的跨域范围。命令行类 Agent 不发 Origin，不受影响。</p>
     */
    public record ArchivedMcpConfig(
            boolean enabled,
            McpAdminDtos.McpLimits limits,
            List<ArchivedMcpAgent> agents
    ) {
    }

    /** 归档载荷；加密后放进 ConfigArchiveCrypto.Envelope 的 payload。{@code mcp} 为空表示没有导出 MCP 配置（含旧版本归档）。 */
    public record ArchivePayload(
            Instant exportedAt,
            String exportedBy,
            String sourceVersion,
            List<ArchivedConnection> connections,
            ArchivedMcpConfig mcp
    ) {
    }

    public record ArchiveExportRequest(
            @NotBlank @Size(min = 12, max = 200) String passphrase,
            /** 要导出的连接 id；为空表示导出全部有权限的连接。 */
            List<Long> connectionIds,
            /** 是否同时导出 MCP 配置；为空视为否。 */
            Boolean includeMcp
    ) {
        public ArchiveExportRequest(String passphrase, List<Long> connectionIds) {
            this(passphrase, connectionIds, null);
        }

        public boolean mcpIncluded() {
            return Boolean.TRUE.equals(includeMcp);
        }
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
            ArchiveConflictMode onConflict,
            /** 归档里有 MCP 配置时是否一并导入；为空视为是。 */
            Boolean importMcp
    ) {
        public ArchiveImportRequest(String passphrase, ArchiveEnvelope archive, ArchiveConflictMode onConflict) {
            this(passphrase, archive, onConflict, null);
        }

        public boolean mcpRequested() {
            return !Boolean.FALSE.equals(importMcp);
        }
    }

    /**
     * MCP 部分的导入结果。
     *
     * <p>跳过的 Agent 与丢掉的授权都要逐条说明原因：用户以为 Agent 的权限原样搬过来了，实际有一条连接
     * 因重名没进来、授权也就没挂上，不说清楚的话他会在 AI 客户端里撞上一句「无权访问」才发现。</p>
     */
    public record ArchiveMcpImportResult(
            boolean settingsApplied,
            String settingsNote,
            List<String> importedAgents,
            /** 「agentId：原因」。 */
            List<String> skippedAgents,
            /** 「agentId → 连接名：原因」。 */
            List<String> droppedGrants
    ) {
    }

    public record ArchiveImportResult(
            int imported,
            int skipped,
            int renamed,
            List<String> importedNames,
            List<String> skippedNames,
            /** 没有导入 MCP 配置时为空。 */
            ArchiveMcpImportResult mcp
    ) {
    }
}
