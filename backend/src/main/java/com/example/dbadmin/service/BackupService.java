package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.BackupTaskRequest;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Service
public class BackupService {
    private final BackupTaskRepository repository;
    private final ConnectionService connections;
    private final AuditRepository audit;
    private final AppProperties properties;

    public BackupService(BackupTaskRepository repository, ConnectionService connections, AuditRepository audit, AppProperties properties) {
        this.repository = repository;
        this.connections = connections;
        this.audit = audit;
        this.properties = properties;
    }

    public List<BackupTask> list() {
        return repository.findAll();
    }

    public BackupTask create(BackupTaskRequest request, String actor) {
        connections.require(request.connectionId());
        long id = repository.insert(new BackupTask(0, request.name(), request.connectionId(), request.scope(), request.schemaName(), request.tableName(), request.cron(), request.enabled(), null, null, null));
        audit.log(actor, "BACKUP_TASK_CREATE", request.name(), request.scope());
        return repository.findById(id).orElseThrow();
    }

    public BackupTask run(long id, String actor) throws Exception {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        DbConnection connection = connections.require(task.connectionId());
        Path dir = Path.of(properties.getBackup().getDirectory());
        Files.createDirectories(dir);
        Path manifest = dir.resolve("backup-task-" + id + "-" + Instant.now().toEpochMilli() + ".txt");
        String message;
        if (connection.dbType().equalsIgnoreCase("mysql") || connection.jdbcUrl().startsWith("jdbc:mysql:")) {
            message = "MySQL backup task prepared. Configure mysqldump on the server to replace this manifest with a physical dump.";
        } else {
            message = "Backup framework executed. Physical backup adapter is not implemented for " + connection.dbType();
        }
        Files.writeString(manifest, "task=" + task.name() + "\nconnection=" + connection.name() + "\nscope=" + task.scope() + "\nmessage=" + message + "\n");
        repository.updateStatus(id, "SUCCESS", message + " Manifest: " + manifest);
        audit.log(actor, "BACKUP_TASK_RUN", task.name(), message);
        return repository.findById(id).orElseThrow();
    }
}
