package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.ConnectionRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 连接上有未结束的手动事务时，不允许改动或删除这条连接。
 *
 * <p>改连接会淘汰远程连接池，而事务正握着池里的一条连接；删连接更糟：事务会留在注册表里指向
 * 一个已经不存在的连接，之后既执行不了（执行要先查连接配置）也没人记得回滚它 —— 那条池化
 * 连接要等十分钟的空闲清理才回收。</p>
 */
class ConnectionServiceTransactionGuardTest {
    private ConnectionRepository repository;
    private SqlTransactionRegistry transactions;
    private ConnectionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConnectionRepository.class);
        transactions = new SqlTransactionRegistry();
        BackupTaskRepository backupTasks = mock(BackupTaskRepository.class);
        RestoreJobRepository restoreJobs = mock(RestoreJobRepository.class);
        when(backupTasks.countByConnectionId(anyLong())).thenReturn(0);
        when(backupTasks.countRunningByConnectionId(anyLong())).thenReturn(0);
        when(restoreJobs.countActiveByConnectionId(anyLong())).thenReturn(0);
        DbConnection row = new DbConnection(7, "订单库", "h2", "jdbc:h2:mem:t", "sa", "cipher", "dev", false,
                Instant.now(), Instant.now());
        when(repository.findById(7)).thenReturn(Optional.of(row));
        CryptoService crypto = mock(CryptoService.class);
        when(crypto.encrypt("x")).thenReturn("cipher");
        service = new ConnectionService(
                repository, crypto, mock(AuditRepository.class), backupTasks,
                mock(MetadataCacheService.class), mock(RemoteDataSourceRegistry.class), new DialectRegistry(),
                restoreJobs, transactions, new SqlScriptSplitter(), new SqlStatementClassifier()
        );
    }

    private void openTransaction() {
        transactions.open(7, mock(Connection.class), "public", "admin", null);
    }

    @Test
    void refusesToUpdateAConnectionThatHasAnOpenTransaction() {
        openTransaction();
        assertThatThrownBy(() -> service.update(7, request(), "admin"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("手动事务");
        verify(repository, never()).update(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesToDeleteAConnectionThatHasAnOpenTransaction() {
        openTransaction();
        assertThatThrownBy(() -> service.delete(7, "admin"))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> {
                    assertThat(((ApiProblemException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiProblemException) error).code()).isEqualTo("CONNECTION_TRANSACTION_OPEN");
                });
        verify(repository, never()).delete(anyLong());
    }

    @Test
    void allowsChangesOnceTheTransactionIsClosed() {
        openTransaction();
        transactions.close(transactions.activeFor(7).id());
        service.delete(7, "admin");
        verify(repository).delete(7);
    }

    @Test
    void anotherConnectionsTransactionDoesNotBlockThisOne() {
        transactions.open(99, mock(Connection.class), "public", "admin", null);
        service.delete(7, "admin");
        verify(repository).delete(7);
    }

    private ConnectionRequest request() {
        return new ConnectionRequest("订单库", "h2", "jdbc:h2:mem:t", "sa", "x", "dev", false);
    }
}
