package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.TestConnectionRequest;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.ConnectionRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConnectionService {
    public static final String PASSWORD_MASK = "******";

    /**
     * Connection rows resolved by id.
     *
     * <p>Nearly every API call needs the row at least once, and several need it
     * twice — {@code open(id)} looks it up and the caller then looks it up again
     * for its dialect — so an H2 round trip and an AES decrypt were paying for
     * the same immutable snapshot repeatedly. {@link ConnectionRepository} is
     * only reachable through this service, so eviction on update and delete
     * covers every writer.</p>
     */
    private final Map<Long, DbConnection> cachedConnections = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedPasswords = new ConcurrentHashMap<>();

    private final ConnectionRepository repository;
    private final CryptoService crypto;
    private final AuditRepository audit;
    private final BackupTaskRepository backupTasks;
    private final MetadataCacheService metadataCache;
    private final RemoteDataSourceRegistry dataSources;
    private final DialectRegistry dialectRegistry;
    private final RestoreJobRepository restoreJobs;

    @Autowired
    public ConnectionService(ConnectionRepository repository, CryptoService crypto, AuditRepository audit, BackupTaskRepository backupTasks, MetadataCacheService metadataCache, RemoteDataSourceRegistry dataSources, DialectRegistry dialectRegistry, RestoreJobRepository restoreJobs) {
        this.repository = repository;
        this.crypto = crypto;
        this.audit = audit;
        this.backupTasks = backupTasks;
        this.metadataCache = metadataCache;
        this.dataSources = dataSources;
        this.dialectRegistry = dialectRegistry;
        this.restoreJobs = restoreJobs;
    }

    public List<ConnectionResponse> list() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    public ConnectionResponse create(ConnectionRequest request, String actor) {
        DbConnection c = toModel(0, request, crypto.encrypt(request.password()));
        long id = repository.insert(c);
        audit.log(actor, "CONNECTION_CREATE", request.name(), request.jdbcUrl());
        return toResponse(repository.findById(id).orElseThrow());
    }

    public ConnectionResponse update(long id, ConnectionRequest request, String actor) {
        DbConnection old = require(id);
        if (backupTasks.countRunningByConnectionId(id) > 0) {
            throw new IllegalStateException("该连接有正在执行的备份任务，请等待备份完成后再修改连接。");
        }
        if (restoreJobs.countActiveByConnectionId(id) > 0) {
            throw new IllegalStateException("该连接有正在执行的恢复任务，请等待恢复完成后再修改连接。");
        }
        String secret = reusesStoredPassword(request.password())
                ? old.encryptedPassword()
                : crypto.encrypt(request.password());
        repository.update(id, toModel(id, request, secret));
        evictConnection(id);
        metadataCache.evictConnection(id);
        audit.log(actor, "CONNECTION_UPDATE", request.name(), request.jdbcUrl());
        return toResponse(repository.findById(id).orElseThrow());
    }

    public void delete(long id, String actor) {
        DbConnection c = require(id);
        int refs = backupTasks.countByConnectionId(id);
        if (refs > 0) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "CONNECTION_HAS_BACKUP_TASKS",
                    "该连接存在 " + refs + " 个关联备份任务，请先删除相关备份任务后再删除连接。");
        }
        if (restoreJobs.countActiveByConnectionId(id) > 0) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "CONNECTION_RESTORE_RUNNING",
                    "该连接有正在执行的恢复任务，请等待恢复完成后再删除连接。");
        }
        repository.delete(id);
        evictConnection(id);
        metadataCache.evictConnection(id);
        audit.log(actor, "CONNECTION_DELETE", c.name(), c.jdbcUrl());
    }

    public void test(TestConnectionRequest request) throws Exception {
        dataSources.test(request.jdbcUrl().trim(), request.username(), request.password());
    }

    public void testExisting(long id) throws Exception {
        try (Connection ignored = open(id)) {
        }
    }

    public void testExisting(long id, ConnectionRequest request) throws Exception {
        if (request == null) {
            testExisting(id);
            return;
        }
        DbConnection old = require(id);
        String password = reusesStoredPassword(request.password())
                ? crypto.decrypt(old.encryptedPassword())
                : request.password();
        dataSources.test(request.jdbcUrl().trim(), request.username(), password);
    }

    public Connection open(long id) throws Exception {
        return dataSources.open(require(id), password(id));
    }

    public Connection open(long id, String schemaName) throws Exception {
        DbConnection configured = require(id);
        Connection connection = dataSources.open(configured, password(id));
        var dialect = dialectRegistry.dialectFor(configured);
        try {
            dialect.activateNamespace(connection, schemaName);
            return connection;
        } catch (Exception error) {
            try {
                connection.close();
            } catch (Exception closeError) {
                error.addSuppressed(closeError);
            }
            String targetKind = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG
                    ? "数据库"
                    : "Schema";
            throw new IllegalArgumentException("无法切换到" + targetKind + "：" + schemaName, error);
        }
    }

    public DbConnection require(long id) {
        DbConnection cached = cachedConnections.get(id);
        if (cached != null) return cached;
        DbConnection loaded = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
        cachedConnections.put(id, loaded);
        return loaded;
    }

    public String password(long id) {
        DbConnection c = require(id);
        String cached = cachedPasswords.get(id);
        if (cached != null) return cached;
        String decrypted = crypto.decrypt(c.encryptedPassword());
        cachedPasswords.put(id, decrypted);
        return decrypted;
    }

    void resetRemoteSession(long id) {
        dataSources.evict(id);
    }

    /**
     * Drops every cached view of one connection. Always paired with the pool
     * eviction, because a changed row means the existing pool is stale too.
     */
    private void evictConnection(long id) {
        cachedConnections.remove(id);
        cachedPasswords.remove(id);
        dataSources.evict(id);
    }

    private boolean reusesStoredPassword(String password) {
        // The explicit UI mask means "keep existing". An empty string means
        // the user intentionally cleared the password (common for local DBs).
        return password == null || PASSWORD_MASK.equals(password);
    }

    private DbConnection toModel(long id, ConnectionRequest r, String encryptedPassword) {
        return new DbConnection(
                id,
                r.name().trim(),
                r.dbType().trim().toLowerCase(Locale.ROOT),
                r.jdbcUrl().trim(),
                r.username(),
                encryptedPassword,
                normalizeEnvironment(r.environment()),
                r.readonly(),
                Instant.now(),
                Instant.now()
        );
    }

    private ConnectionResponse toResponse(DbConnection c) {
        return new ConnectionResponse(
                c.id(), c.name(), c.dbType(), c.jdbcUrl(), c.username(), normalizeEnvironment(c.environment()), c.readonly(),
                dialectRegistry.dialectFor(c).capabilities()
        );
    }

    private String normalizeEnvironment(String environment) {
        String normalized = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        if ("production".equals(normalized)) normalized = "prod";
        if ("testing".equals(normalized)) normalized = "test";
        if ("test".equals(normalized) || "prod".equals(normalized)) {
            return normalized;
        }
        return "dev";
    }
}
