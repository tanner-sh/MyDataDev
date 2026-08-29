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
 * 撑爆堆；队列满时退化成同步行为而不是丢记录。</p>
 *
 * <p>队列满时不能用 {@link ThreadPoolExecutor.CallerRunsPolicy} 那样就地执行：它会让最新的
 * 那条记录抢在队列里已经排着的几百条前面落库，audit_log 的自增 id 与事件先后就对不上了
 * （审计正是靠这个顺序还原「先做了什么再做了什么」）。所以这里改成阻塞入队 —— 提交线程
 * 等到队列腾出位置再排到队尾，慢是慢了，顺序是对的。</p>
 *
 * <p>只有两种情况会退回就地执行：线程池已经关停（队列不再被消费，不自己写就是丢记录），
 * 以及等待超过 {@link #ENQUEUE_TIMEOUT_SECONDS} 秒 —— 写入线程卡死时不能让请求线程跟着
 * 一起无限期挂住，这时顺序让位于可用性，并打一条 warn。</p>
 *
 * <p>需要立刻读回的记录（例如 SQL 执行历史，执行完前端马上会刷新历史抽屉）不要走这里，
 * 否则会读到还没落库的空结果。</p>
 */
@Component
public class MetadataWriteQueue {
    private static final Logger log = LoggerFactory.getLogger(MetadataWriteQueue.class);
    private static final int QUEUE_CAPACITY = 512;
    private static final long SHUTDOWN_DRAIN_SECONDS = 5;
    /** 队列满时最多等多久；超时说明写入线程已经卡死，只能就地写。 */
    private static final long ENQUEUE_TIMEOUT_SECONDS = 5;

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
                MetadataWriteQueue::enqueueOrRunInline
        );
        this.executor = owned;
    }

    /**
     * 队列满时的兜底。绝不丢记录，并且尽最大努力保住顺序。
     *
     * <p>{@link ThreadPoolExecutor.CallerRunsPolicy} 两点都不满足：关停后它静默丢弃，
     * 队列满时它就地执行、把最新一条插到已排队的记录前面。</p>
     */
    private static void enqueueOrRunInline(Runnable write, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            // 队列不会再被消费了，只能自己写。
            write.run();
            return;
        }
        try {
            if (executor.getQueue().offer(write, ENQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return;
            log.warn("元数据写入队列已满且 {} 秒内没有腾出位置，本条记录改由调用线程写入，落库顺序会与调用顺序不一致",
                    ENQUEUE_TIMEOUT_SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        write.run();
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
