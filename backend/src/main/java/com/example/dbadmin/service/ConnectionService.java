package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.TestConnectionRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.ConnectionRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

@Service
public class ConnectionService {
    public static final String PASSWORD_MASK = "******";

    private final ConnectionRepository repository;
    private final CryptoService crypto;
    private final AuditRepository audit;
    private final BackupTaskRepository backupTasks;

    public ConnectionService(ConnectionRepository repository, CryptoService crypto, AuditRepository audit, BackupTaskRepository backupTasks) {
        this.repository = repository;
        this.crypto = crypto;
        this.audit = audit;
        this.backupTasks = backupTasks;
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
        String secret = reusesStoredPassword(request.password())
                ? old.encryptedPassword()
                : crypto.encrypt(request.password());
        repository.update(id, toModel(id, request, secret));
        audit.log(actor, "CONNECTION_UPDATE", request.name(), request.jdbcUrl());
        return toResponse(repository.findById(id).orElseThrow());
    }

    public void delete(long id, String actor) {
        DbConnection c = require(id);
        int refs = backupTasks.countByConnectionId(id);
        if (refs > 0) {
            throw new IllegalArgumentException("Connection is referenced by " + refs + " backup task(s). Delete related backup tasks first.");
        }
        repository.delete(id);
        audit.log(actor, "CONNECTION_DELETE", c.name(), c.jdbcUrl());
    }

    public void test(TestConnectionRequest request) throws Exception {
        try (Connection ignored = DriverManager.getConnection(request.jdbcUrl(), props(request.username(), request.password()))) {
        }
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
        try (Connection ignored = DriverManager.getConnection(request.jdbcUrl(), props(request.username(), password))) {
        }
    }

    public Connection open(long id) throws Exception {
        DbConnection c = require(id);
        return DriverManager.getConnection(c.jdbcUrl(), props(c.username(), crypto.decrypt(c.encryptedPassword())));
    }

    public DbConnection require(long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
    }

    public String password(long id) {
        DbConnection c = require(id);
        return crypto.decrypt(c.encryptedPassword());
    }

    private Properties props(String username, String password) {
        Properties props = new Properties();
        if (username != null) {
            props.put("user", username);
        }
        if (password != null) {
            props.put("password", password);
        }
        return props;
    }

    private boolean reusesStoredPassword(String password) {
        return password == null || password.isBlank() || PASSWORD_MASK.equals(password);
    }

    private DbConnection toModel(long id, ConnectionRequest r, String encryptedPassword) {
        return new DbConnection(
                id,
                r.name(),
                r.dbType(),
                r.jdbcUrl(),
                r.username(),
                encryptedPassword,
                normalizeEnvironment(r.environment()),
                r.readonly(),
                Instant.now(),
                Instant.now()
        );
    }

    private ConnectionResponse toResponse(DbConnection c) {
        return new ConnectionResponse(c.id(), c.name(), c.dbType(), c.jdbcUrl(), c.username(), normalizeEnvironment(c.environment()), c.readonly());
    }

    private String normalizeEnvironment(String environment) {
        if ("test".equals(environment) || "prod".equals(environment)) {
            return environment;
        }
        return "dev";
    }
}
