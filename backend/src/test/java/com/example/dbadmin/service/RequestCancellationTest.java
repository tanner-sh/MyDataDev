package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    @Test void queuedCancellationDoesNotRunAfterStatementRegistrationCloses() throws Exception {
        var queued = new ArrayDeque<Runnable>();
        Statement statement = mock(Statement.class);
        try (var scope = new RequestCancellation(queued::add)) {
            var registration = RequestCancellation.watch(statement);
            scope.cancel();
            assertThat(queued).hasSize(1);
            registration.close();
            queued.remove().run();
            verify(statement, never()).cancel();
        }
    }

    @Test void queuedCancellationDoesNotRunAfterTheWholeRequestCloses() throws Exception {
        var queued = new ArrayDeque<Runnable>();
        AtomicInteger cancelled = new AtomicInteger();
        try (var scope = new RequestCancellation(queued::add)) {
            RequestCancellation.onCancel(cancelled::incrementAndGet);
            scope.cancel();
        }
        queued.remove().run();
        assertThat(cancelled).hasValue(0);
        RequestCancellation.check();
    }

    @Test void statementCannotBeReleasedWhileItsCancellationIsStillRunning() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var cancelling = new CountDownLatch(1);
        var finishCancellation = new CountDownLatch(1);
        var closing = new CountDownLatch(1);
        var scope = new RequestCancellation(workers);
        try {
            var registration = RequestCancellation.onCancel(() -> {
                cancelling.countDown();
                if (!finishCancellation.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试取消回调未结束");
            });
            scope.cancel();
            assertThat(cancelling.await(5, TimeUnit.SECONDS)).isTrue();
            var released = workers.submit(() -> {
                closing.countDown();
                registration.close();
            });
            assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
            // close 是 JDBC try-with-resources 中归还连接之前的屏障。
            assertThatThrownBy(() -> released.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            finishCancellation.countDown();
            released.get(5, TimeUnit.SECONDS);
        } finally {
            finishCancellation.countDown();
            scope.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
