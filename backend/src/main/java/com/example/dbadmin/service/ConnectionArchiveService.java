package com.example.dbadmin.service;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.SshTunnelRequest;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveConflictMode;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveEnvelope;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveExportRequest;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveImportRequest;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveImportResult;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchivePayload;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchivedConnection;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchivedSshTunnel;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.SshTunnelSettings;
import com.example.dbadmin.repo.AuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 连接配置的加密导出与导入。
 *
 * <p>桌面版和 Web 版用的是完全独立的元数据库与加密密钥，换机器或从桌面转 Web 时，几十条连接
 * 只能一条条手工重建。这里把它们打成一个用口令加密的归档文件。</p>
 *
 * <p>密码在归档内部是明文，靠整体加密保护 —— 不可能是别的样子：源端密文用源端的
 * 本机托管的主密钥加密，目标端根本解不开。所以导出必然是一次「解密后重新加密」，
 * 而口令是唯一能跨装机的共享秘密。正因如此，这个接口只对管理员开放，两端都写审计，
 * 并且拒绝弱口令。</p>
 */
@Service
public class ConnectionArchiveService {
    /** 重名时新连接的后缀。 */
    private static final String RENAME_SUFFIX = "（导入）";
    private static final int MAX_NAME_LENGTH = 120;

    private final ConnectionService connections;
    private final ConnectionAccessService access;
    private final ConfigArchiveCrypto archiveCrypto;
    private final AuditRepository audit;
    private final ObjectMapper mapper;

    public ConnectionArchiveService(
            ConnectionService connections,
            ConnectionAccessService access,
            ConfigArchiveCrypto archiveCrypto,
            AuditRepository audit,
            ObjectMapper mapper
    ) {
        this.connections = connections;
        this.access = access;
        this.archiveCrypto = archiveCrypto;
        this.audit = audit;
        this.mapper = mapper;
    }

    public ArchiveEnvelope export(ArchiveExportRequest request, String actor) {
        Set<Long> requested = request.connectionIds() == null
                ? Set.of()
                : new LinkedHashSet<>(request.connectionIds());
        // 只导出调用者本来就能管理的连接：管理员身份能进这个接口，但连接级授权仍然说了算。
        List<ConnectionResponse> visible = access.visibleConnections(connections.list()).stream()
                .filter(connection -> requested.isEmpty() || requested.contains(connection.id()))
                .filter(connection -> access.can(connection.id(), ConnectionPermission.CONNECTION_ADMIN))
                .toList();
        if (visible.isEmpty()) throw new IllegalArgumentException("没有可导出的连接。");

        List<ArchivedConnection> archived = new ArrayList<>();
        for (ConnectionResponse summary : visible) {
            DbConnection connection = connections.require(summary.id());
            archived.add(new ArchivedConnection(
                    connection.name(),
                    connection.dbType(),
                    connection.jdbcUrl(),
                    connection.username(),
                    connections.password(connection.id()),
                    connection.environment(),
                    connection.readonly(),
                    connection.groupName(),
                    connection.tags(),
                    connection.defaultSchema(),
                    connection.initSql(),
                    connection.description(),
                    archivedTunnel(connection.sshTunnel())
            ));
            audit.onConnection(actor, "CONNECTION_ARCHIVE_EXPORT", connection.id(), connection.name(),
                    "含密码与 SSH 密钥材料");
        }
        ArchivePayload payload = new ArchivePayload(Instant.now(), actor, null, archived);
        ArchiveEnvelope envelope = archiveCrypto.seal(serialize(payload), request.passphrase().toCharArray());
        audit.global(actor, "CONNECTION_ARCHIVE_EXPORT", "connections", "connections=" + archived.size());
        return envelope;
    }

    public ArchiveImportResult importArchive(ArchiveImportRequest request, String actor) {
        ArchivePayload payload = deserialize(
                archiveCrypto.open(request.archive(), request.passphrase().toCharArray()));
        if (payload.connections() == null || payload.connections().isEmpty()) {
            throw new IllegalArgumentException("归档里没有连接配置。");
        }
        ArchiveConflictMode mode = request.onConflict() == null ? ArchiveConflictMode.SKIP : request.onConflict();
        Set<String> existing = connections.list().stream()
                .map(connection -> fold(connection.name()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int renamed = 0;
        for (ArchivedConnection archived : payload.connections()) {
            String name = archived.name() == null ? "" : archived.name().trim();
            if (name.isEmpty()) {
                skipped.add("(未命名)");
                continue;
            }
            String targetName = name;
            if (existing.contains(fold(name))) {
                if (mode == ArchiveConflictMode.SKIP) {
                    skipped.add(name);
                    continue;
                }
                targetName = availableName(name, existing);
                renamed++;
            }
            ConnectionResponse created = connections.create(toRequest(archived, targetName), actor);
            access.initializeNewConnection(created.id());
            existing.add(fold(targetName));
            imported.add(targetName);
            audit.onConnection(actor, "CONNECTION_ARCHIVE_IMPORT", created.id(), targetName,
                    name.equals(targetName) ? "新建" : "重名改为：" + targetName);
        }
        audit.global(actor, "CONNECTION_ARCHIVE_IMPORT", "connections",
                "imported=" + imported.size() + ";skipped=" + skipped.size() + ";renamed=" + renamed);
        return new ArchiveImportResult(imported.size(), skipped.size(), renamed, imported, skipped);
    }

    private ConnectionRequest toRequest(ArchivedConnection archived, String name) {
        ArchivedSshTunnel ssh = archived.ssh();
        SshTunnelRequest tunnel = ssh == null ? null : new SshTunnelRequest(
                ssh.enabled(), ssh.host(), ssh.port(), ssh.username(), ssh.authMode(),
                ssh.password(), ssh.privateKey(), ssh.passphrase(), ssh.serverFingerprint(), ssh.skipHostKeyCheck()
        );
        return new ConnectionRequest(
                name, archived.dbType(), archived.jdbcUrl(), archived.username(), archived.password(),
                archived.environment(), archived.readonly(), archived.groupName(), archived.tags(),
                archived.defaultSchema(), archived.initSql(), archived.description(), tunnel
        );
    }

    private ArchivedSshTunnel archivedTunnel(SshTunnelSettings tunnel) {
        if (tunnel == null || !tunnel.enabled()) return null;
        return new ArchivedSshTunnel(
                true, tunnel.host(), tunnel.port(), tunnel.username(), tunnel.authMode(),
                connections.decryptSecret(tunnel.encryptedPassword()),
                connections.decryptSecret(tunnel.encryptedPrivateKey()),
                connections.decryptSecret(tunnel.encryptedPassphrase()),
                tunnel.serverFingerprint(), tunnel.skipHostKeyCheck()
        );
    }

    /** 重名时找一个没被占用的名字，而不是覆盖已有连接 —— 覆盖会静默改掉别人正在用的配置。 */
    private static String availableName(String name, Set<String> taken) {
        String base = truncate(name + RENAME_SUFFIX);
        if (!taken.contains(fold(base))) return base;
        for (int index = 2; index < 1000; index++) {
            String candidate = truncate(name + RENAME_SUFFIX + " " + index);
            if (!taken.contains(fold(candidate))) return candidate;
        }
        throw new IllegalStateException("无法为导入的连接生成唯一名称：" + name);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_NAME_LENGTH ? value : value.substring(0, MAX_NAME_LENGTH);
    }

    private static String fold(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String serialize(ArchivePayload payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("无法序列化配置归档", error);
        }
    }

    private ArchivePayload deserialize(String json) {
        try {
            return mapper.readValue(json, ArchivePayload.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("归档内容无法解析，文件可能已损坏。");
        }
    }
}
