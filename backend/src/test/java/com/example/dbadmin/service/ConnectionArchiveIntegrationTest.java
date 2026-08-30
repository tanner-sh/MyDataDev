package com.example.dbadmin.service;

import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveConflictMode;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveEnvelope;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveExportRequest;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveImportRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.SshTunnelRequest;
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
 * <p>关键契约：归档里的密码必须能在目标端还原，而目标端用的是另一把 app.crypto-key ——
 * 这正是不能直接搬密文的原因。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.crypto-key=archive-test-crypto-key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-archive-test-backups"
})
class ConnectionArchiveIntegrationTest {
    private static final String PASSPHRASE = "archive-passphrase-1";

    @Autowired ConnectionArchiveService archives;
    @Autowired ConnectionService connections;
    @Autowired ConfigArchiveCrypto crypto;
    @Autowired JdbcTemplate jdbc;

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

    private int countAudit(String action) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Integer.class, action);
        return count == null ? 0 : count;
    }
}
