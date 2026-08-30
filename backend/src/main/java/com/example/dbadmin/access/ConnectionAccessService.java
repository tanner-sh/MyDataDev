package com.example.dbadmin.access;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.auth.WebIdentityContext;
import com.example.dbadmin.dto.AccessControlDtos.ConnectionAccessResponse;
import com.example.dbadmin.dto.AccessControlDtos.ConnectionAccessUpdateRequest;
import com.example.dbadmin.dto.AccessControlDtos.ConnectionGrantResponse;
import com.example.dbadmin.dto.AccessControlDtos.UserGroupRequest;
import com.example.dbadmin.dto.AccessControlDtos.UserGroupResponse;
import com.example.dbadmin.dto.AccessControlDtos.PermissionTemplateResponse;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.RestoreSourceRef;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.ConnectionAccessRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import com.example.dbadmin.service.SqlScriptSplitter;
import com.example.dbadmin.service.SqlStatementClassifier;
import com.example.dbadmin.service.SqlExecutionRegistry;
import com.example.dbadmin.service.SqlTransactionRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ConnectionAccessService {
    public static final String MODE_SHARED = "SHARED";
    public static final String MODE_RESTRICTED = "RESTRICTED";
    private static final Set<String> MODES = Set.of(MODE_SHARED, MODE_RESTRICTED);
    private static final Set<String> GRANTEE_TYPES = Set.of("USER", "GROUP");

    private final ConnectionAccessRepository repository;
    private final AuditRepository audit;
    private final SqlStatementClassifier classifier;
    private final SqlScriptSplitter splitter;
    private final BackupTaskRepository backupTasks;
    private final BackupHistoryRepository backupHistories;
    private final RestoreJobRepository restoreJobs;
    private final SqlFileExecutionRepository sqlFiles;
    private final SqlTransactionRegistry transactions;
    private final SqlExecutionRegistry executions;

    public ConnectionAccessService(
            ConnectionAccessRepository repository,
            AuditRepository audit,
            SqlStatementClassifier classifier,
            SqlScriptSplitter splitter,
            BackupTaskRepository backupTasks,
            BackupHistoryRepository backupHistories,
            RestoreJobRepository restoreJobs,
            SqlFileExecutionRepository sqlFiles,
            SqlTransactionRegistry transactions,
            SqlExecutionRegistry executions
    ) {
        this.repository = repository;
        this.audit = audit;
        this.classifier = classifier;
        this.splitter = splitter;
        this.backupTasks = backupTasks;
        this.backupHistories = backupHistories;
        this.restoreJobs = restoreJobs;
        this.sqlFiles = sqlFiles;
        this.transactions = transactions;
        this.executions = executions;
    }

    public void require(long connectionId, ConnectionPermission permission) {
        WebIdentity identity = currentIdentity().orElse(null);
        // 桌面/开发模式没有 WebIdentity；MCP 继续由自己的 Agent 白名单做显式连接授权。
        if (identity == null || "ADMIN".equals(identity.role())) return;
        if (repository.hasAccess(connectionId, identity.userId(), permission)) return;
        audit.onConnection(identity.username(), "CONNECTION_ACCESS_DENIED", connectionId,
                "requiredPermission=" + permission.name());
        throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "CONNECTION_ACCESS_DENIED",
                "当前账号没有该连接的" + permissionLabel(permission) + "权限。"
        );
    }

    public void requireSql(long connectionId, String sql) {
        List<SqlScriptSplitter.StatementSegment> statements = splitter.split(sql);
        if (statements.isEmpty()) {
            require(connectionId, ConnectionPermission.QUERY);
            return;
        }
        for (SqlScriptSplitter.StatementSegment statement : statements) {
            require(connectionId, permissionFor(classifier.classify(statement.sql())));
        }
    }

    public BackupTask requireBackupTask(long taskId) {
        BackupTask task = backupTasks.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("备份任务不存在"));
        require(task.connectionId(), ConnectionPermission.BACKUP_RESTORE);
        return task;
    }

    public void requireRestoreJob(long jobId) {
        long connectionId = restoreJobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("恢复任务不存在"))
                .targetConnectionId();
        require(connectionId, ConnectionPermission.BACKUP_RESTORE);
    }

    /** 恢复历史属于原备份连接；校验来源可防止拿别人的备份恢复到自己的连接。 */
    public void requireRestoreSource(RestoreSourceRef source) {
        if (source == null || source.id() == null) throw new IllegalArgumentException("恢复来源不能为空。");
        if ("HISTORY".equalsIgnoreCase(source.kind())) {
            long connectionId = backupHistories.findById(source.id())
                    .orElseThrow(() -> new IllegalArgumentException("备份历史不存在：" + source.id()))
                    .connectionId();
            require(connectionId, ConnectionPermission.BACKUP_RESTORE);
            return;
        }
        if (!"UPLOAD".equalsIgnoreCase(source.kind())) {
            throw new IllegalArgumentException("不支持的恢复来源：" + source.kind());
        }
    }

    public void requireSqlFile(long jobId, ConnectionPermission permission) {
        long connectionId = sqlFiles.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("SQL 文件任务不存在"))
                .connectionId();
        require(connectionId, permission);
    }

    public void requireSqlFileExecution(long jobId) {
        var job = sqlFiles.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("SQL 文件任务不存在"));
        // 下面三条按语句种类要权限，但 ANALYZING 期间计数全是 0，解析出零条语句的脚本也一样 ——
        // 只靠它们的话，那个窗口里任何登录用户都能查看别人任务的文件名与连接、甚至取消它。
        requireAnyAccess(job.connectionId());
        if (job.queryCount() > 0) require(job.connectionId(), ConnectionPermission.QUERY);
        if (job.mutationCount() > 0) require(job.connectionId(), ConnectionPermission.DATA_WRITE);
        if (job.ddlCount() > 0 || job.unknownCount() > 0) require(job.connectionId(), ConnectionPermission.DDL);
    }

    /**
     * 手动事务的执行、提交与回滚。
     *
     * <p>光校验连接权限是不够的：事务 id 会随「当前活动事务」接口发给同一连接上任何有查询权限
     * 的人，拿到就能替开启者提交或回滚未完成的写入。所以这里先比归属，再按语句种类要权限。
     * 管理员也不例外 —— 界面上没有接触别人事务 id 的路径，放行只会多一个踩坑的口子。</p>
     */
    public void requireTransaction(String transactionId, String sql) {
        SqlTransactionRegistry.OpenTransaction transaction = transactions.require(transactionId);
        requireTransactionOwner(transaction);
        long connectionId = transaction.connectionId();
        if (sql == null) require(connectionId, ConnectionPermission.DATA_WRITE);
        else requireSql(connectionId, sql);
    }

    private void requireTransactionOwner(SqlTransactionRegistry.OpenTransaction transaction) {
        WebIdentity identity = currentIdentity().orElse(null);
        // 没有会话身份就是桌面/开发模式，事务开启时也没有 owner，维持原语义。
        if (identity == null || transaction.ownedBy(identity.userId())) return;
        audit.onConnection(identity.username(), "CONNECTION_ACCESS_DENIED", transaction.connectionId(),
                "transaction:" + transaction.id(), "reason=not_transaction_owner");
        throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "TRANSACTION_NOT_OWNED",
                "该手动事务由其他用户开启，只有开启者能继续执行、提交或回滚。"
        );
    }

    public void requireSqlExecution(String executionId) {
        executions.connectionId(executionId).ifPresent(this::requireAnyAccess);
    }

    public void requireAnyAccess(long connectionId) {
        WebIdentity identity = currentIdentity().orElse(null);
        if (identity == null || "ADMIN".equals(identity.role())) return;
        if (!repository.hasAnyAccess(connectionId, identity.userId())) {
            audit.onConnection(identity.username(), "CONNECTION_ACCESS_DENIED", connectionId,
                    "requiredPermission=ANY");
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "CONNECTION_ACCESS_DENIED", "当前账号没有该连接的访问权限。");
        }
    }

    public void requireScopedConnection(Long connectionId, ConnectionPermission permission) {
        if (connectionId != null) {
            require(connectionId, permission);
            return;
        }
        WebIdentity identity = currentIdentity().orElse(null);
        if (identity != null && !"ADMIN".equals(identity.role())) {
            audit.global(identity.username(), "CONNECTION_ACCESS_DENIED", "connection-scope",
                    "requiredPermission=" + permission.name());
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "CONNECTION_SCOPE_REQUIRED", "当前账号只能查看已授权连接的记录。");
        }
    }

    public void requireAnyConnection(ConnectionPermission permission) {
        WebIdentity identity = currentIdentity().orElse(null);
        if (identity == null || "ADMIN".equals(identity.role())) return;
        if (!repository.allowedConnectionIds(identity.userId(), permission).isEmpty()) return;
        audit.global(identity.username(), "CONNECTION_ACCESS_DENIED", "connections",
                "requiredPermission=" + permission.name());
        throw new ApiProblemException(HttpStatus.FORBIDDEN, "CONNECTION_ACCESS_DENIED",
                "当前账号没有任何连接的" + permissionLabel(permission) + "权限。");
    }

    public List<BackupTask> visibleBackupTasks(List<BackupTask> tasks) {
        WebIdentity identity = currentIdentity().orElse(null);
        if (identity == null || "ADMIN".equals(identity.role())) return tasks;
        Set<Long> allowed = repository.allowedConnectionIds(identity.userId(), ConnectionPermission.BACKUP_RESTORE);
        return tasks.stream().filter(task -> allowed.contains(task.connectionId())).toList();
    }

    public ConnectionPermission permissionFor(SqlStatementClassifier.Kind kind) {
        return switch (kind) {
            case QUERY -> ConnectionPermission.QUERY;
            case MUTATION -> ConnectionPermission.DATA_WRITE;
            case DDL, UNKNOWN -> ConnectionPermission.DDL;
        };
    }

    public List<ConnectionResponse> visibleConnections(List<ConnectionResponse> connections) {
        WebIdentity identity = currentIdentity().orElse(null);
        if (identity == null || "ADMIN".equals(identity.role())) return connections;
        Set<Long> visible = repository.allowedConnectionIds(identity.userId());
        return connections.stream().filter(connection -> visible.contains(connection.id())).toList();
    }

    /**
     * 当前账号在给定连接上的权限。前端每次刷新连接列表都会调，所以只发一条查询 ——
     * 原来是每条连接 8 次（存在性检查 + 7 个权限各一个相关子查询）。
     */
    public Map<Long, List<ConnectionPermission>> currentPermissions(List<Long> connectionIds) {
        if (connectionIds.size() > 500) throw new IllegalArgumentException("单次最多查询 500 条连接权限");
        Set<Long> requested = new LinkedHashSet<>(connectionIds);
        requested.remove(null);
        if (requested.isEmpty()) return Map.of();
        WebIdentity identity = currentIdentity().orElse(null);
        List<ConnectionPermission> all = List.of(ConnectionPermission.values());
        Map<Long, List<ConnectionPermission>> result = new LinkedHashMap<>();
        // 桌面/开发模式没有身份，管理员隐含全部权限：两种情况都只需确认连接确实存在。
        if (identity == null || "ADMIN".equals(identity.role())) {
            Set<Long> existing = repository.existingConnectionIds(requested);
            for (Long connectionId : requested) {
                if (existing.contains(connectionId)) result.put(connectionId, all);
            }
            return result;
        }
        Map<Long, Set<ConnectionPermission>> granted = repository.permissionsByConnection(identity.userId());
        for (Long connectionId : requested) {
            Set<ConnectionPermission> permissions = granted.get(connectionId);
            if (permissions == null || permissions.isEmpty()) continue;
            result.put(connectionId, permissions.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList());
        }
        return result;
    }

    public boolean can(long connectionId, ConnectionPermission permission) {
        WebIdentity identity = currentIdentity().orElse(null);
        return identity == null || "ADMIN".equals(identity.role())
                || repository.hasAccess(connectionId, identity.userId(), permission);
    }

    @Transactional
    public void initializeNewConnection(long connectionId) {
        Long owner = currentIdentity().map(WebIdentity::userId).orElse(null);
        repository.initializeNewConnection(connectionId, owner);
    }

    public ConnectionAccessResponse policy(long connectionId) {
        ConnectionAccessPolicy policy = repository.findPolicy(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("连接不存在"));
        return response(policy);
    }

    @Transactional
    public ConnectionAccessResponse updatePolicy(long connectionId, ConnectionAccessUpdateRequest request, WebIdentity actor) {
        if (!repository.connectionExists(connectionId)) throw new IllegalArgumentException("连接不存在");
        String mode = normalizedMode(request.accessMode());
        if (request.ownerUserId() != null && !repository.userExists(request.ownerUserId())) {
            throw new IllegalArgumentException("连接所有者不存在");
        }
        List<ConnectionAccessPolicy.Grant> grants = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        if (MODE_RESTRICTED.equals(mode)) {
            for (var grantRequest : request.grants()) {
                String type = normalizedGranteeType(grantRequest.granteeType());
                validateGrantee(type, grantRequest.granteeId());
                for (ConnectionPermission permission : grantRequest.permissions()) {
                    String key = type + ":" + grantRequest.granteeId() + ":" + permission;
                    if (unique.add(key)) {
                        grants.add(new ConnectionAccessPolicy.Grant(type, grantRequest.granteeId(), permission));
                    }
                }
            }
        }
        repository.replacePolicy(connectionId, mode, request.ownerUserId(), grants);
        audit.onConnection(actor.username(), "CONNECTION_ACCESS_UPDATE", connectionId,
                "mode=" + mode + ", owner=" + request.ownerUserId() + ", grants=" + grants.size());
        return policy(connectionId);
    }

    public List<UserGroupResponse> groups() {
        return repository.findGroups().stream().map(this::response).toList();
    }

    public List<PermissionTemplateResponse> permissionTemplates() {
        return List.of(
                new PermissionTemplateResponse("READ_ONLY", "只读分析", "查看结构、查询并导出结果",
                        List.of(ConnectionPermission.VIEW_METADATA, ConnectionPermission.QUERY, ConnectionPermission.EXPORT)),
                new PermissionTemplateResponse("DEVELOPER", "开发人员", "查询、修改数据和维护数据库对象",
                        List.of(ConnectionPermission.VIEW_METADATA, ConnectionPermission.QUERY, ConnectionPermission.DATA_WRITE,
                                ConnectionPermission.DDL, ConnectionPermission.EXPORT)),
                new PermissionTemplateResponse("OPERATIONS", "运维人员", "开发权限加备份与恢复",
                        List.of(ConnectionPermission.VIEW_METADATA, ConnectionPermission.QUERY, ConnectionPermission.DATA_WRITE,
                                ConnectionPermission.DDL, ConnectionPermission.EXPORT, ConnectionPermission.BACKUP_RESTORE)),
                new PermissionTemplateResponse("CONNECTION_OWNER", "连接管理员", "管理连接及其全部功能",
                        List.of(ConnectionPermission.CONNECTION_ADMIN))
        );
    }

    @Transactional
    public UserGroupResponse createGroup(UserGroupRequest request, WebIdentity actor) {
        String name = normalizedGroupName(request.name());
        validateMembers(request.memberUserIds());
        try {
            long id = repository.insertGroup(name, normalizedDescription(request.description()), request.memberUserIds());
            audit.global(actor.username(), "USER_GROUP_CREATE", "group:" + name, "members=" + request.memberUserIds().size());
            return response(repository.findGroup(id).orElseThrow());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("用户组名称已存在：" + name);
        }
    }

    @Transactional
    public UserGroupResponse updateGroup(long id, UserGroupRequest request, WebIdentity actor) {
        UserGroup existing = repository.findGroup(id).orElseThrow(() -> new IllegalArgumentException("用户组不存在"));
        String name = normalizedGroupName(request.name());
        validateMembers(request.memberUserIds());
        try {
            repository.updateGroup(id, name, normalizedDescription(request.description()), request.memberUserIds());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("用户组名称已存在：" + name);
        }
        audit.global(actor.username(), "USER_GROUP_UPDATE", "group:" + name,
                "previous=" + existing.name() + ", members=" + request.memberUserIds().size());
        return response(repository.findGroup(id).orElseThrow());
    }

    @Transactional
    public void deleteGroup(long id, WebIdentity actor) {
        UserGroup existing = repository.findGroup(id).orElseThrow(() -> new IllegalArgumentException("用户组不存在"));
        repository.deleteGroup(id);
        audit.global(actor.username(), "USER_GROUP_DELETE", "group:" + existing.name(), "id=" + id);
    }

    public Optional<WebIdentity> currentIdentity() {
        return WebIdentityContext.current();
    }

    private ConnectionAccessResponse response(ConnectionAccessPolicy policy) {
        Map<String, List<ConnectionPermission>> grouped = new LinkedHashMap<>();
        for (ConnectionAccessPolicy.Grant grant : policy.grants()) {
            grouped.computeIfAbsent(grant.granteeType() + ":" + grant.granteeId(), ignored -> new ArrayList<>())
                    .add(grant.permission());
        }
        List<ConnectionGrantResponse> grants = grouped.entrySet().stream().map(entry -> {
            String[] key = entry.getKey().split(":", 2);
            List<ConnectionPermission> permissions = entry.getValue().stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
            return new ConnectionGrantResponse(key[0], Long.parseLong(key[1]), permissions);
        }).toList();
        return new ConnectionAccessResponse(
                policy.connectionId(), policy.accessMode(), policy.ownerUserId(), grants,
                List.of(ConnectionPermission.values())
        );
    }

    private UserGroupResponse response(UserGroup group) {
        return new UserGroupResponse(
                group.id(), group.name(), group.description(), group.memberUserIds(), group.externalMemberUserIds(),
                group.createdAt(), group.updatedAt()
        );
    }

    private void validateMembers(List<Long> memberUserIds) {
        for (Long userId : new LinkedHashSet<>(memberUserIds)) {
            if (userId == null || !repository.userExists(userId)) throw new IllegalArgumentException("用户不存在：" + userId);
        }
    }

    private void validateGrantee(String type, long id) {
        boolean exists = "USER".equals(type) ? repository.userExists(id) : repository.groupExists(id);
        if (!exists) throw new IllegalArgumentException(("USER".equals(type) ? "用户" : "用户组") + "不存在：" + id);
    }

    private String normalizedMode(String value) {
        String mode = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw new IllegalArgumentException("连接访问模式只支持 SHARED 或 RESTRICTED");
        return mode;
    }

    private String normalizedGranteeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!GRANTEE_TYPES.contains(type)) throw new IllegalArgumentException("授权对象只支持 USER 或 GROUP");
        return type;
    }

    private String normalizedGroupName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank()) throw new IllegalArgumentException("用户组名称不能为空");
        return name;
    }

    private String normalizedDescription(String value) {
        String description = value == null ? "" : value.trim();
        return description.isBlank() ? null : description;
    }

    private String permissionLabel(ConnectionPermission permission) {
        return switch (permission) {
            case VIEW_METADATA -> "元数据查看";
            case QUERY -> "查询";
            case DATA_WRITE -> "数据写入";
            case DDL -> "DDL";
            case EXPORT -> "导出";
            case BACKUP_RESTORE -> "备份恢复";
            case CONNECTION_ADMIN -> "连接管理";
        };
    }
}
