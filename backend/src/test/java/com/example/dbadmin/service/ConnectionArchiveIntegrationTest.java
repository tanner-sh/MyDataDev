package com.example.dbadmin.service;

import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveConflictMode;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveEnvelope;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveExportRequest;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveImportRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.SshTunnelRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentCreateRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentGrant;
import com.example.dbadmin.dto.McpAdminDtos.McpConfigUpdateRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpLimits;
import com.example.dbadmin.mcp.McpAccessLevel;
import com.example.dbadmin.mcp.McpApiKeyRegistry;
import com.example.dbadmin.mcp.McpConfigurationService;
import com.example.dbadmin.mcp.McpRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 连接配置的加密导出与导入。
 *
 * <p>关键契约：归档里的密码必须能在目标端还原，而目标端用的是另一份主密钥 ——
 * 这正是不能直接搬密文的原因。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.crypto-key-file=target/test-secrets/archive-test.key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-archive-test-backups"
})
class ConnectionArchiveIntegrationTest {
    private static final String PASSPHRASE = "archive-passphrase-1";

    @Autowired ConnectionArchiveService archives;
    @Autowired ConnectionService connections;
    @Autowired ConfigArchiveCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired McpConfigurationService mcp;
    @Autowired McpApiKeyRegistry mcpKeys;

    @Test
    void exportsAndReimportsConnectionsIncludingSecrets() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ConnectionResponse source = connections.create(new ConnectionRequest(
                "archive-" + suffix, "H2", "jdbc:h2:mem:t" + suffix, "sa", "target-password",
                "dev", false, "组A", "tag1,tag2", "PUBLIC", null, "备注",
                new SshTunnelRequest(true, "10.0.0.1", 2222, "jump", "PASSWORD",
                        "ssh-secret", null, null, "SHA256:abc", false)
        ), "admin");

        ArchiveEnvelope envelope = archives.export(
                new ArchiveExportRequest(PASSPHRASE, List.of(source.id())), "admin");

        // 归档在磁盘上是密文：明文口令不该出现在任何字段里。
        assertThat(envelope.payload()).doesNotContain("target-password").doesNotContain("ssh-secret");

        // 模拟「另一台装机」：删掉源连接后重新导入，密码必须还原。
        connections.delete(source.id(), "admin");
        var result = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, envelope, ArchiveConflictMode.SKIP), "admin");

        assertThat(result.imported()).isEqualTo(1);
        ConnectionResponse restored = connections.list().stream()
                .filter(connection -> connection.name().equals("archive-" + suffix)).findFirst().orElseThrow();
        assertThat(connections.password(restored.id())).isEqualTo("target-password");
        var model = connections.require(restored.id());
        assertThat(model.groupName()).isEqualTo("组A");
        assertThat(model.tags()).isEqualTo("tag1,tag2");
        assertThat(model.description()).isEqualTo("备注");
        assertThat(model.usesSshTunnel()).isTrue();
        assertThat(model.sshTunnel().host()).isEqualTo("10.0.0.1");
        assertThat(connections.decryptSecret(model.sshTunnel().encryptedPassword())).isEqualTo("ssh-secret");
        // 目标端重新加密过：密文不可能和归档里的明文一样。
        assertThat(model.sshTunnel().encryptedPassword()).isNotEqualTo("ssh-secret");
    }

    @Test
    void duplicateNamesAreSkippedOrRenamedButNeverOverwritten() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "dup-" + suffix;
        ConnectionResponse original = connections.create(new ConnectionRequest(
                name, "H2", "jdbc:h2:mem:a" + suffix, "sa", "original-password", "dev", false), "admin");
        ArchiveEnvelope envelope = archives.export(
                new ArchiveExportRequest(PASSPHRASE, List.of(original.id())), "admin");
        // 改掉本机这条的密码：导入绝不能把它覆盖回去。
        connections.update(original.id(), new ConnectionRequest(
                name, "H2", "jdbc:h2:mem:a" + suffix, "sa", "changed-password", "dev", false), "admin");

        var skipped = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, envelope, ArchiveConflictMode.SKIP), "admin");
        assertThat(skipped.imported()).isZero();
        assertThat(skipped.skipped()).isEqualTo(1);
        assertThat(connections.password(original.id())).isEqualTo("changed-password");

        var renamed = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, envelope, ArchiveConflictMode.RENAME), "admin");
        assertThat(renamed.imported()).isEqualTo(1);
        assertThat(renamed.renamed()).isEqualTo(1);
        assertThat(renamed.importedNames().get(0)).isEqualTo(name + "（导入）");
        // 原来那条仍然是改过之后的密码。
        assertThat(connections.password(original.id())).isEqualTo("changed-password");
    }

    @Test
    void wrongPassphraseCannotImport() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ConnectionResponse source = connections.create(new ConnectionRequest(
                "wrong-pass-" + suffix, "H2", "jdbc:h2:mem:w" + suffix, "sa", "secret", "dev", false), "admin");
        ArchiveEnvelope envelope = archives.export(
                new ArchiveExportRequest(PASSPHRASE, List.of(source.id())), "admin");

        assertThatThrownBy(() -> archives.importArchive(
                new ArchiveImportRequest("a-different-passphrase", envelope, ArchiveConflictMode.SKIP), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("口令不正确");
    }

    @Test
    void bothSidesAreAudited() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ConnectionResponse source = connections.create(new ConnectionRequest(
                "audited-" + suffix, "H2", "jdbc:h2:mem:x" + suffix, "sa", "secret", "dev", false), "admin");
        ArchiveEnvelope envelope = archives.export(
                new ArchiveExportRequest(PASSPHRASE, List.of(source.id())), "admin");
        connections.delete(source.id(), "admin");
        archives.importArchive(new ArchiveImportRequest(PASSPHRASE, envelope, ArchiveConflictMode.SKIP), "admin");

        // 导出把全部密码打成一个文件，是必须留痕的操作。
        assertThat(countAudit("CONNECTION_ARCHIVE_EXPORT")).isGreaterThanOrEqualTo(2);
        assertThat(countAudit("CONNECTION_ARCHIVE_IMPORT")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void refusesWeakPassphraseBeforeTouchingAnySecret() {
        assertThatThrownBy(() -> archives.export(new ArchiveExportRequest("short", List.of()), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mcpAgentsTravelWithTheirKeysAndGrantsFollowConnectionNames() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ConnectionResponse source = connections.create(new ConnectionRequest(
                "mcp-src-" + suffix, "H2", "jdbc:h2:mem:m" + suffix, "sa", "secret", "dev", false), "admin");
        var credential = mcp.createAgent(new McpAgentCreateRequest("agent-" + suffix,
                List.of(new McpAgentGrant(source.id(), "DATA_WRITE")), false), "admin");

        ArchiveEnvelope envelope = archives.export(
                new ArchiveExportRequest(PASSPHRASE, List.of(source.id()), true), "admin");
        // 归档里只有哈希；原始 Key 本来就不在服务端，更不该出现在文件里。
        assertThat(envelope.payload()).doesNotContain(credential.credential());

        // 模拟换装机：Agent 与连接都不在了。
        mcp.deleteAgent(credential.agent().id(), "admin");
        connections.delete(source.id(), "admin");

        var result = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, envelope, ArchiveConflictMode.SKIP), "admin");

        assertThat(result.mcp()).isNotNull();
        assertThat(result.mcp().importedAgents()).containsExactly("agent-" + suffix);
        assertThat(result.mcp().droppedGrants()).isEmpty();
        Long restoredId = connections.list().stream()
                .filter(connection -> connection.name().equals("mcp-src-" + suffix))
                .map(ConnectionResponse::id).findFirst().orElseThrow();
        McpRuntimeConfig.Agent agent = mcp.snapshot().agents().get("agent-" + suffix);
        // 授权挂到了新连接 id 上，档位原样保留。
        assertThat(agent.connectionLevels()).containsExactlyEntriesOf(java.util.Map.of(restoredId, McpAccessLevel.DATA_WRITE));
        // 这是整个功能的目的：AI 客户端里那把 Key 不用换。
        assertThat(mcpKeys.authenticate(credential.credential())).isPresent();
        assertThat(countAudit("MCP_AGENT_IMPORT")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void grantsAreNeverAttachedToALocalConnectionThatMerelySharesTheName() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ConnectionResponse kept = connections.create(new ConnectionRequest(
                "mcp-kept-" + suffix, "H2", "jdbc:h2:mem:k" + suffix, "sa", "secret", "dev", false), "admin");
        ConnectionResponse moved = connections.create(new ConnectionRequest(
                "mcp-moved-" + suffix, "H2", "jdbc:h2:mem:v" + suffix, "sa", "secret", "dev", false), "admin");
        var credential = mcp.createAgent(new McpAgentCreateRequest("agent-dup-" + suffix, List.of(
                new McpAgentGrant(kept.id(), "READ_ONLY"),
                new McpAgentGrant(moved.id(), "READ_ONLY")), false), "admin");
        ArchiveEnvelope envelope = archives.export(
                new ArchiveExportRequest(PASSPHRASE, List.of(kept.id(), moved.id()), true), "admin");

        mcp.deleteAgent(credential.agent().id(), "admin");
        connections.delete(moved.id(), "admin");
        // kept 仍在本机：导入时因重名被跳过，它的授权不能挂到本机这条上。
        var result = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, envelope, ArchiveConflictMode.SKIP), "admin");

        assertThat(result.skippedNames()).containsExactly("mcp-kept-" + suffix);
        assertThat(result.mcp().importedAgents()).containsExactly("agent-dup-" + suffix);
        assertThat(result.mcp().droppedGrants()).singleElement().asString().contains("mcp-kept-" + suffix);
        McpRuntimeConfig.Agent agent = mcp.snapshot().agents().get("agent-dup-" + suffix);
        assertThat(agent.connectionLevels()).doesNotContainKey(kept.id()).hasSize(1);
    }

    @Test
    void existingAgentIsNotOverwrittenSoItsCurrentKeyKeepsWorking() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ConnectionResponse source = connections.create(new ConnectionRequest(
                "mcp-own-" + suffix, "H2", "jdbc:h2:mem:o" + suffix, "sa", "secret", "dev", false), "admin");
        var credential = mcp.createAgent(new McpAgentCreateRequest("agent-own-" + suffix,
                List.of(new McpAgentGrant(source.id(), "READ_ONLY")), false), "admin");
        ArchiveEnvelope envelope = archives.export(
                new ArchiveExportRequest(PASSPHRASE, List.of(source.id()), true), "admin");
        var rotated = mcp.rotateAgentKey(credential.agent().id(), "admin");

        var result = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, envelope, ArchiveConflictMode.RENAME), "admin");

        assertThat(result.mcp().importedAgents()).isEmpty();
        assertThat(result.mcp().skippedAgents()).singleElement().asString().contains("本机已有同名 Agent");
        assertThat(mcpKeys.authenticate(rotated.credential())).isPresent();
        assertThat(mcpKeys.authenticate(credential.credential())).isEmpty();
    }

    @Test
    void mcpIsLeftAloneUnlessRequestedOnBothSides() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ConnectionResponse source = connections.create(new ConnectionRequest(
                "mcp-opt-" + suffix, "H2", "jdbc:h2:mem:p" + suffix, "sa", "secret", "dev", false), "admin");
        var credential = mcp.createAgent(new McpAgentCreateRequest("agent-opt-" + suffix,
                List.of(new McpAgentGrant(source.id(), "READ_ONLY")), false), "admin");

        // 导出时没勾：归档里没有 MCP 段。
        ArchiveEnvelope withoutMcp = archives.export(new ArchiveExportRequest(PASSPHRASE, List.of(source.id())), "admin");
        ArchiveEnvelope withMcp = archives.export(new ArchiveExportRequest(PASSPHRASE, List.of(source.id()), true), "admin");
        mcp.deleteAgent(credential.agent().id(), "admin");
        connections.delete(source.id(), "admin");

        var plain = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, withoutMcp, ArchiveConflictMode.SKIP), "admin");
        assertThat(plain.mcp()).isNull();

        // 导入时取消勾选：即使归档里有，也不碰本机 MCP 配置。
        var declined = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, withMcp, ArchiveConflictMode.RENAME, false), "admin");
        assertThat(declined.mcp()).isNull();
        assertThat(mcp.snapshot().agents()).doesNotContainKey("agent-opt-" + suffix);
    }

    @Test
    void globalSettingsAreAppliedOnlyWhereNoAgentExistsYet() {
        McpRuntimeConfig.Settings original = mcp.snapshot().settings();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ConnectionResponse source = connections.create(new ConnectionRequest(
                "mcp-set-" + suffix, "H2", "jdbc:h2:mem:s" + suffix, "sa", "secret", "dev", false), "admin");
        var credential = mcp.createAgent(new McpAgentCreateRequest("agent-set-" + suffix,
                List.of(new McpAgentGrant(source.id(), "READ_ONLY")), false), "admin");
        mcp.updateConfig(new McpConfigUpdateRequest(List.of(), limits(original, 77)), "admin");
        ArchiveEnvelope envelope = archives.export(
                new ArchiveExportRequest(PASSPHRASE, List.of(source.id()), true), "admin");
        mcp.updateConfig(new McpConfigUpdateRequest(List.of(), limits(original, original.defaultQueryRows())), "admin");

        // 本机还有 Agent：设置保持本机的。
        var withAgents = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, envelope, ArchiveConflictMode.SKIP), "admin");
        assertThat(withAgents.mcp().settingsApplied()).isFalse();
        assertThat(mcp.snapshot().settings().defaultQueryRows()).isEqualTo(original.defaultQueryRows());

        // 全新装机：清空 Agent 后再导入，设置随归档过来。
        mcp.snapshot().agentList().forEach(agent -> mcp.deleteAgent(agent.id(), "admin"));
        connections.delete(source.id(), "admin");
        var fresh = archives.importArchive(
                new ArchiveImportRequest(PASSPHRASE, envelope, ArchiveConflictMode.SKIP), "admin");
        assertThat(fresh.mcp().settingsApplied()).isTrue();
        assertThat(mcp.snapshot().settings().defaultQueryRows()).isEqualTo(77);
        assertThat(mcpKeys.authenticate(credential.credential())).isPresent();

        mcp.updateConfig(new McpConfigUpdateRequest(List.of(), limits(original, original.defaultQueryRows())), "admin");
    }

    private static McpLimits limits(McpRuntimeConfig.Settings settings, int defaultQueryRows) {
        return new McpLimits(
                defaultQueryRows, Math.max(settings.maxQueryRows(), defaultQueryRows), settings.maxResultCells(),
                settings.maxResultTextChars(), settings.maxCellTextChars(), settings.maxSqlChars(),
                settings.queryTimeoutSeconds(), settings.metadataPageSize(), settings.maxMetadataPageSize(),
                settings.tablePageSize(), settings.maxTablePageSize(), settings.sessionTtlMinutes(),
                settings.maxScriptsPerConnection(), settings.executeTimeoutSeconds()
        );
    }

    private int countAudit(String action) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Integer.class, action);
        return count == null ? 0 : count;
    }
}
