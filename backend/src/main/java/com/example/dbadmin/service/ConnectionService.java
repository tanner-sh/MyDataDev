package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.TestConnectionRequest;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.SshTunnelSettings;
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
    /** 解密后的隧道参数；只缓存真的启用了隧道的连接。 */
    private final Map<Long, SshTunnelSpec> cachedSshSpecs = new ConcurrentHashMap<>();

    private final ConnectionRepository repository;
    private final CryptoService crypto;
    private final AuditRepository audit;
    private final BackupTaskRepository backupTasks;
    private final MetadataCacheService metadataCache;
    private final RemoteDataSourceRegistry dataSources;
    private final DialectRegistry dialectRegistry;
    private final RestoreJobRepository restoreJobs;
    private final SqlTransactionRegistry transactions;
    private final SqlScriptSplitter scriptSplitter;
    private final SqlStatementClassifier statementClassifier;

    @Autowired
    public ConnectionService(ConnectionRepository repository, CryptoService crypto, AuditRepository audit, BackupTaskRepository backupTasks, MetadataCacheService metadataCache, RemoteDataSourceRegistry dataSources, DialectRegistry dialectRegistry, RestoreJobRepository restoreJobs, SqlTransactionRegistry transactions, SqlScriptSplitter scriptSplitter, SqlStatementClassifier statementClassifier) {
        this.repository = repository;
        this.crypto = crypto;
        this.audit = audit;
        this.backupTasks = backupTasks;
        this.metadataCache = metadataCache;
        this.dataSources = dataSources;
        this.dialectRegistry = dialectRegistry;
        this.restoreJobs = restoreJobs;
        this.transactions = transactions;
        this.scriptSplitter = scriptSplitter;
        this.statementClassifier = statementClassifier;
    }

    public List<ConnectionResponse> list() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    public ConnectionResponse create(ConnectionRequest request, String actor) {
        DbConnection c = toModel(0, request, crypto.encrypt(request.password()), null);
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
        requireNoOpenTransaction(id, "修改连接");
        String secret = reusesStoredPassword(request.password())
                ? old.encryptedPassword()
                : crypto.encrypt(request.password());
        repository.update(id, toModel(id, request, secret, old.sshTunnel()));
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
        requireNoOpenTransaction(id, "删除连接");
        repository.delete(id);
        evictConnection(id);
        metadataCache.evictConnection(id);
        audit.log(actor, "CONNECTION_DELETE", c.name(), c.jdbcUrl());
    }

    /**
     * 手动事务开着时不许改动连接。
     *
     * <p>改连接会淘汰远程连接池，而事务正握着池里的一条连接；删连接更糟：事务会留在注册表里
     * 指向一个已经不存在的连接，之后既执行不了（要查连接配置）也没人记得回滚它。与已有的
     * 备份/恢复任务一样，先让用户自己了结。</p>
     */
    private void requireNoOpenTransaction(long id, String action) {
        if (transactions.activeFor(id) == null) return;
        throw new ApiProblemException(
                HttpStatus.CONFLICT,
                "CONNECTION_TRANSACTION_OPEN",
                "该连接上有未结束的手动事务，请先提交或回滚后再" + action + "。"
        );
    }

    public void test(TestConnectionRequest request) throws Exception {
        // 新连接还没有存过密钥，掩码在这里没有可沿用的旧值，等价于「没填」。
        dataSources.test(request.jdbcUrl().trim(), request.username(), request.password(), specOf(request.ssh(), null));
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
        dataSources.test(request.jdbcUrl().trim(), request.username(), password, specOf(request.ssh(), old.sshTunnel()));
    }

    /**
     * 按连接配置打开一条连接，未指定命名空间即落到连接上配置的默认命名空间。
     *
     * <p>这里必须和 {@link #open(long, String)} 走同一条回落逻辑：之前这个重载直接返回原始
     * 连接，于是 SQL 文件执行、导出、备份这些不带 schema 参数的调用方全都绕过了默认命名空间，
     * 脚本里的无限定表名会落到登录账号的默认库，而不是用户在连接上配置的那个。</p>
     */
    public Connection open(long id) throws Exception {
        return open(id, null);
    }

    public Connection open(long id, String schemaName) throws Exception {
        DbConnection configured = require(id);
        // 未指定命名空间时用连接上配置的默认值，省去每次打开连接后再手动切库。
        if (schemaName == null || schemaName.isBlank()) schemaName = configured.defaultSchema();
        if (schemaName == null || schemaName.isBlank()) return dataSources.open(configured, password(id), sshSpec(id));
        Connection connection = dataSources.open(configured, password(id), sshSpec(id));
        var dialect = dialectRegistry.dialectFor(configured);
        try {
            // 池化连接上的 setCatalog/setSchema 不会被 Hikari 归还时重置，必须自己还原，
            // 否则下一个借用者（表浏览、元数据、备份、MCP 查询）会静默继承这个命名空间。
            String original = NamespaceScopedConnection.readNamespace(connection, dialect.namespaceKind());
            dialect.activateNamespace(connection, schemaName);
            return NamespaceScopedConnection.wrap(
                    connection, dialect.namespaceKind(), original, () -> dataSources.evict(id)
            );
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

    /**
     * 这条连接的隧道参数，未启用隧道时返回 {@code null}。
     *
     * <p>和密码一样缓存：每次打开连接都解一遍 AES 私钥没有意义，而连接改动一定会走
     * {@link #evictConnection(long)}。</p>
     */
    private SshTunnelSpec sshSpec(long id) {
        DbConnection connection = require(id);
        if (!connection.usesSshTunnel()) return null;
        return cachedSshSpecs.computeIfAbsent(id, key -> SshTunnelProfile.toSpec(connection.sshTunnel(), crypto::decrypt));
    }

    /**
     * 把编辑器提交的隧道配置变成可用的隧道参数。
     *
     * <p>先按保存时的规则解释掩码、补默认值、做校验，再解密回来 —— 这样「测试连接」和真正
     * 保存后建连走的是同一条路径，不会出现测试通过但保存后连不上。</p>
     */
    private SshTunnelSpec specOf(com.example.dbadmin.dto.ApiDtos.SshTunnelRequest request, SshTunnelSettings existing) {
        return SshTunnelProfile.toSpec(SshTunnelProfile.toSettings(request, existing, crypto::encrypt), crypto::decrypt);
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
        cachedSshSpecs.remove(id);
        dataSources.evict(id);
    }

    private boolean reusesStoredPassword(String password) {
        // The explicit UI mask means "keep existing". An empty string means
        // the user intentionally cleared the password (common for local DBs).
        return password == null || PASSWORD_MASK.equals(password);
    }

    private DbConnection toModel(long id, ConnectionRequest r, String encryptedPassword, SshTunnelSettings existingSsh) {
        String initSql = ConnectionProfile.normalizeInitSql(r.initSql());
        // 保存时就把初始化 SQL 校验掉：它之后会在每条物理连接上隐式执行，等到建连时才报错
        // 就变成了一条连不上的连接，用户很难定位。
        ConnectionProfile.initStatements(initSql, scriptSplitter, statementClassifier);
        return new DbConnection(
                id,
                r.name().trim(),
                r.dbType().trim().toLowerCase(Locale.ROOT),
                r.jdbcUrl().trim(),
                r.username(),
                encryptedPassword,
                normalizeEnvironment(r.environment()),
                r.readonly(),
                ConnectionProfile.normalizeGroup(r.groupName()),
                ConnectionProfile.normalizeTags(r.tags()),
                ConnectionProfile.normalizeDefaultSchema(r.defaultSchema()),
                initSql,
                ConnectionProfile.normalizeDescription(r.description()),
                SshTunnelProfile.toSettings(r.ssh(), existingSsh, crypto::encrypt),
                Instant.now(),
                Instant.now()
        );
    }

    private ConnectionResponse toResponse(DbConnection c) {
        return new ConnectionResponse(
                c.id(), c.name(), c.dbType(), c.jdbcUrl(), c.username(), normalizeEnvironment(c.environment()), c.readonly(),
                c.groupName(), ConnectionProfile.parseTags(c.tags()), c.defaultSchema(), c.initSql(), c.description(),
                SshTunnelProfile.summarize(c.sshTunnel()),
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
