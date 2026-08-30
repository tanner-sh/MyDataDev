package com.example.dbadmin.access;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.model.SqlFileExecution;
import com.example.dbadmin.model.RestoreUpload;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.ConnectionAccessRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import com.example.dbadmin.repo.RestoreUploadRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import com.example.dbadmin.service.SqlExecutionRegistry;
import com.example.dbadmin.service.SqlScriptSplitter;
import com.example.dbadmin.service.SqlStatementClassifier;
import com.example.dbadmin.service.SqlTransactionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 连接权限之外的两道归属校验：手动事务的开启者，以及尚未分类完的 SQL 文件任务。 */
class ConnectionAccessGuardTest {
    private ConnectionAccessRepository repository;
    private SqlFileExecutionRepository sqlFiles;
    private SqlTransactionRegistry transactions;
    private RestoreUploadRepository restoreUploads;
    private ConnectionAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConnectionAccessRepository.class);
        sqlFiles = mock(SqlFileExecutionRepository.class);
        transactions = new SqlTransactionRegistry();
        restoreUploads = mock(RestoreUploadRepository.class);
        service = new ConnectionAccessService(
                repository, mock(AuditRepository.class), new SqlStatementClassifier(), new SqlScriptSplitter(),
                mock(BackupTaskRepository.class), mock(BackupHistoryRepository.class), mock(RestoreJobRepository.class),
                restoreUploads, sqlFiles, transactions, new SqlExecutionRegistry()
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void transactionCannotBeCommittedByAnotherUser() {
        String id = transactions.open(1L, mock(Connection.class), "public", "alice", 7L).id();
        // 拿到事务 id 的第二个用户，即便对这条连接有全部权限也不能碰别人的事务。
        when(repository.hasAccess(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        authenticate(8L, "bob", "OPERATOR");

        assertThatThrownBy(() -> service.requireTransaction(id, null))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("TRANSACTION_NOT_OWNED");
    }

    @Test
    void transactionOwnerMayStillCommit() {
        String id = transactions.open(1L, mock(Connection.class), "public", "alice", 7L).id();
        when(repository.hasAccess(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        authenticate(7L, "alice", "OPERATOR");

        assertThatCode(() -> service.requireTransaction(id, null)).doesNotThrowAnyException();
    }

    @Test
    void transactionsStayOpenWithoutWebAuthentication() {
        String id = transactions.open(1L, mock(Connection.class), "public", "alice", null).id();

        assertThatCode(() -> service.requireTransaction(id, null)).doesNotThrowAnyException();
    }

    @Test
    void sqlFileJobStillGuardedWhileStatementCountsAreZero() {
        // ANALYZING 期间四个计数都是 0，按种类分派的检查一条都不会触发。
        when(sqlFiles.findById(5L)).thenReturn(Optional.of(analyzingJob()));
        when(repository.hasAnyAccess(3L, 8L)).thenReturn(false);
        authenticate(8L, "bob", "OPERATOR");

        assertThatThrownBy(() -> service.requireSqlFileExecution(5L))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("CONNECTION_ACCESS_DENIED");

        when(repository.hasAnyAccess(3L, 8L)).thenReturn(true);
        assertThatCode(() -> service.requireSqlFileExecution(5L)).doesNotThrowAnyException();
    }

    @Test
    void activeTransactionLookupIgnoresOtherUsers() {
        transactions.open(1L, mock(Connection.class), "public", "alice", 7L);

        assertThat(transactions.activeFor(1L).ownedBy(8L)).isFalse();
        assertThat(transactions.activeFor(1L).ownedBy(7L)).isTrue();
    }

    @Test
    void restoreUploadCanOnlyBeUsedByItsOwner() {
        when(restoreUploads.findById(9L)).thenReturn(Optional.of(restoreUpload(7L)));
        authenticate(8L, "bob", "OPERATOR");

        assertThatThrownBy(() -> service.requireRestoreSource(
                new com.example.dbadmin.dto.ApiDtos.RestoreSourceRef("UPLOAD", 9L)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("RESTORE_UPLOAD_NOT_OWNED");

        authenticate(7L, "alice", "OPERATOR");
        assertThatCode(() -> service.requireRestoreSource(
                new com.example.dbadmin.dto.ApiDtos.RestoreSourceRef("UPLOAD", 9L)))
                .doesNotThrowAnyException();
    }

    @Test
    void administratorMayHandleLegacyRestoreUploadWithoutOwner() {
        when(restoreUploads.findById(9L)).thenReturn(Optional.of(restoreUpload(null)));
        authenticate(1L, "admin", "ADMIN");

        assertThatCode(() -> service.requireRestoreSource(
                new com.example.dbadmin.dto.ApiDtos.RestoreSourceRef("UPLOAD", 9L)))
                .doesNotThrowAnyException();
    }

    private void authenticate(long userId, String username, String role) {
        WebIdentity identity = new WebIdentity(userId, "LOCAL", username, username, username, role, 0L);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of()));
    }

    private SqlFileExecution analyzingJob() {
        return new SqlFileExecution(
                5L, 3L, "prod", "MYSQL", "migration.sql", "/tmp/migration.sql", 1024L, "sha", "UTF-8",
                "ANALYZING", "ANALYZE", 0L, null, 0L,
                0L, 0L, 0L, 0L, 0L, 0L,
                null, null, null, false, false, false, "alice",
                null, null, null, Instant.now()
        );
    }

    private RestoreUpload restoreUpload(Long ownerUserId) {
        return new RestoreUpload(9L, "backup.sql", "/tmp/backup.sql", 10L, "sha", "SQL", "mysql",
                ownerUserId, Instant.now(), Instant.now().plusSeconds(3600));
    }
}
