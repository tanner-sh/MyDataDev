package com.example.dbadmin.repo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void keepsEveryWriteAndItsOrderWhenSaturated() {
        MetadataWriteQueue queue = new MetadataWriteQueue();
        List<Integer> written = new CopyOnWriteArrayList<>();

        // 远超队列容量：满队列既不能丢记录，也不能打乱顺序。
        IntStream.range(0, 5_000).forEach(index -> queue.submit(() -> written.add(index)));
        queue.close();

        assertThat(written).hasSize(5_000).isSorted();
    }

    /**
     * 队列满时的关键场景：写入线程被占住、队列排满之后再来一条。
     *
     * <p>此前的拒绝策略是让提交线程就地执行，于是这条最新的记录会抢在队列里已排着的 512 条
     * 之前落库（实测顺序是 512, 0, 1, 2 …）—— audit_log 的自增 id 与事件先后就对不上了。
     * 现在提交线程阻塞等位，排到队尾。</p>
     */
    @Test
    void aWriteSubmittedWhileTheQueueIsFullGoesToTheBackOfTheLine() throws Exception {
        MetadataWriteQueue queue = new MetadataWriteQueue();
        List<Integer> written = new CopyOnWriteArrayList<>();
        CountDownLatch blocked = new CountDownLatch(1);

        // 唯一的工作线程被占住，之后提交的都只能待在队列里。
        queue.submit(() -> {
            try {
                blocked.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        int capacity = 512;
        IntStream.range(0, capacity).forEach(index -> queue.submit(() -> written.add(index)));

        // 队列已满，这一条会阻塞在入队上，所以得放到另一个线程里提交。
        Thread late = new Thread(() -> queue.submit(() -> written.add(capacity)), "late-write");
        late.start();
        awaitBlocked(late);

        blocked.countDown();
        late.join(10_000);
        queue.close();

        assertThat(written).hasSize(capacity + 1).isSorted();
        assertThat(written.get(written.size() - 1)).isEqualTo(capacity);
    }

    /** 等到提交线程确实卡在入队上，否则这个用例根本没走到满队列那条路径。 */
    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.TIMED_WAITING || state == Thread.State.WAITING) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("提交线程没有阻塞在入队上：队列可能没满");
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
