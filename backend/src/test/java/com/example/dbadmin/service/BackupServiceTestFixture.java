package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.RestoreJobRepository;

import static org.mockito.Mockito.mock;

final class BackupServiceTestFixture {
    private BackupServiceTestFixture() {
    }

    static BackupService create(
            BackupTaskRepository repository,
            BackupHistoryRepository historyRepository,
            ConnectionService connections,
            AuditRepository audit,
            AppProperties properties
    ) {
        return create(
                repository,
                historyRepository,
                connections,
                audit,
                properties,
                new DialectRegistry(),
                mock(BackupExecutionCoordinator.class)
        );
    }

    static BackupService create(
            BackupTaskRepository repository,
            BackupHistoryRepository historyRepository,
            ConnectionService connections,
            AuditRepository audit,
            AppProperties properties,
            DialectRegistry dialectRegistry,
            BackupExecutionCoordinator coordinator
    ) {
        return new BackupService(
                repository,
                historyRepository,
                connections,
                audit,
                properties,
                dialectRegistry,
                coordinator,
                mock(RestoreJobRepository.class),
                new NativeToolLocator(properties),
                new BackgroundTaskControl(properties)
        );
    }
}
