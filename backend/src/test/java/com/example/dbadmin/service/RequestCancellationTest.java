package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestCancellationTest {
    @Test void cancelsJdbcOnceAndDoesNotLeakToTheNextRequest() throws Exception {
        Statement statement = mock(Statement.class);
        try (var scope = new RequestCancellation(Runnable::run);
             var registration = RequestCancellation.watch(statement)) {
            scope.cancel(); scope.cancel();
            verify(statement, times(1)).cancel();
            assertThatThrownBy(RequestCancellation::check).isInstanceOf(SQLException.class);
        }
        RequestCancellation.check();
        try (var next = new RequestCancellation(Runnable::run);
             var registration = RequestCancellation.watch(mock(Statement.class))) {
            RequestCancellation.check();
        }
    }
    @Test void cancellationDuringSubmissionIsDeliveredWhenTaskIdBecomesAvailable() throws Exception {
        AtomicInteger cancelled = new AtomicInteger();
        try (var scope = new RequestCancellation(Runnable::run)) {
            scope.cancel();
            RequestCancellation.onCancel(cancelled::incrementAndGet);
            assertThat(cancelled).hasValue(1);
        }
    }
    @Test void finishedStatementsAreNotCancelledByLaterNotifications() throws Exception {
        Statement statement = mock(Statement.class);
        try (var scope = new RequestCancellation(Runnable::run)) {
            RequestCancellation.watch(statement).close();
            scope.cancel();
            verify(statement, never()).cancel();
        }
    }
}
