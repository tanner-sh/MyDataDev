package com.example.dbadmin.repo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataWriteQueueTest {
    @Test
    void drainsEverySubmittedWriteOnShutdown() {
        MetadataWriteQueue queue = new MetadataWriteQueue();
        List<Integer> written = new CopyOnWriteArrayList<>();

        IntStream.range(0, 200).forEach(index -> queue.submit(() -> written.add(index)));
        queue.close();

        assertThat(written).hasSize(200);
    }

    @Test
    void keepsWritesInSubmissionOrder() {
        MetadataWriteQueue queue = new MetadataWriteQueue();
        List<Integer> written = new CopyOnWriteArrayList<>();

        // 单线程执行是 sql_history/audit_log 的自增 id 与执行先后一致的前提。
        IntStream.range(0, 100).forEach(index -> queue.submit(() -> written.add(index)));
        queue.close();

        assertThat(written).isSorted();
    }

    @Test
    void fallsBackToTheCallerRatherThanDroppingWritesWhenSaturated() {
        MetadataWriteQueue queue = new MetadataWriteQueue();
        List<Integer> written = new CopyOnWriteArrayList<>();

        // 远超队列容量：满队列必须退化成调用线程同步执行，而不是丢记录。
        IntStream.range(0, 5_000).forEach(index -> queue.submit(() -> written.add(index)));
        queue.close();

        assertThat(written).hasSize(5_000);
    }

    @Test
    void inlineQueueRunsWritesImmediately() {
        MetadataWriteQueue queue = MetadataWriteQueue.inline();
        List<Integer> written = new CopyOnWriteArrayList<>();

        queue.submit(() -> written.add(1));

        assertThat(written).containsExactly(1);
    }

    @Test
    void runsWritesInlineAfterShutdown() {
        MetadataWriteQueue queue = new MetadataWriteQueue();
        queue.close();
        List<Integer> written = new CopyOnWriteArrayList<>();

        queue.submit(() -> written.add(1));

        assertThat(written).containsExactly(1);
    }
}
