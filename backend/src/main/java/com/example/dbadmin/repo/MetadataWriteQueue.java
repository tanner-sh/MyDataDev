package com.example.dbadmin.repo;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 把只写不读的元数据库记录挪出请求线程。
 *
 * <p>审计日志的 detail 最多 100 000 字符，一次 500 条语句的脚本执行就要往 H2 写掉上百 KB，
 * 而这条记录既不会被任何接口读回，也早就被设计成「写失败不影响业务结果」。放在请求路径上
 * 同步写只是白白拉长响应时间。</p>
 *
 * <p>三条约束：单线程保证写入顺序与提交顺序一致；队列有界，慢速 H2 不会把待写记录堆到
 * 撑爆堆；队列满时由提交线程自己执行（{@link ThreadPoolExecutor.CallerRunsPolicy}），
 * 也就是退化成今天的同步行为而不是丢记录。</p>
 *
 * <p>需要立刻读回的记录（例如 SQL 执行历史，执行完前端马上会刷新历史抽屉）不要走这里，
 * 否则会读到还没落库的空结果。</p>
 */
@Component
public class MetadataWriteQueue {
    private static final Logger log = LoggerFactory.getLogger(MetadataWriteQueue.class);
    private static final int QUEUE_CAPACITY = 512;
    private static final long SHUTDOWN_DRAIN_SECONDS = 5;

    private final Executor executor;
    private final ThreadPoolExecutor owned;

    public MetadataWriteQueue() {
        this.owned = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "dbadmin-metadata-write");
                    thread.setDaemon(true);
                    return thread;
                },
                // 不能用 CallerRunsPolicy：它在线程池已关停时是静默丢弃任务，而这里
                // 队列满和已关停都必须退化成调用线程同步执行，绝不丢记录。
                (write, ignored) -> write.run()
        );
        this.executor = owned;
    }

    private MetadataWriteQueue(Executor executor) {
        this.executor = executor;
        this.owned = null;
    }

    /** 测试用：在调用线程上立即执行，让断言不必等待后台线程。 */
    public static MetadataWriteQueue inline() {
        return new MetadataWriteQueue(Runnable::run);
    }

    public void submit(Runnable write) {
        executor.execute(write);
    }

    @PreDestroy
    public void close() {
        if (owned == null) return;
        owned.shutdown();
        try {
            if (!owned.awaitTermination(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("元数据写入队列在 {} 秒内未排空，剩余 {} 条记录被丢弃",
                        SHUTDOWN_DRAIN_SECONDS, owned.getQueue().size());
                owned.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            owned.shutdownNow();
        }
    }
}
