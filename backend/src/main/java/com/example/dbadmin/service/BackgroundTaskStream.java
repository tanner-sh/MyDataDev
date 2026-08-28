package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ActiveOperations;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 后台任务进度的服务端推送。
 *
 * <p>之前每个浏览器标签页都在轮询 {@code /restores/operations/active}：有任务时 2 秒一次、
 * 空闲时 20 秒一次。代价有两头 —— 空闲时绝大多数请求返回的是同一份「什么都没有」，而任务
 * 跑起来时 2 秒的粒度又赶不上后端 500 毫秒一次的进度上报。</p>
 *
 * <p>这里把轮询收到服务端：只要还有订阅者，就按 {@code app.background-tasks.stream-interval-ms}
 * 扫一遍，内容变了才推。没有订阅者时一条查询都不发。浏览器侧的 {@code EventSource} 断线会
 * 自动重连，所以这里不做重放，新订阅者进来先收一份当前快照。</p>
 */
@Component
public class BackgroundTaskStream {
    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskStream.class);
    /** 心跳间隔：中间的反向代理常在 30~60 秒无数据后掐掉连接。 */
    private static final Duration HEARTBEAT = Duration.ofSeconds(20);
    public static final String EVENT_NAME = "operations";

    private final RestoreService restores;
    private final BackupHistoryRepository histories;
    private final SqlFileExecutionRepository sqlFiles;
    private final ObjectMapper mapper;
    private final long streamTimeoutMs;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public BackgroundTaskStream(RestoreService restores, BackupHistoryRepository histories,
                                SqlFileExecutionRepository sqlFiles, ObjectMapper mapper,
                                com.example.dbadmin.config.AppProperties properties) {
        this.restores = restores;
        this.histories = histories;
        this.sqlFiles = sqlFiles;
        this.mapper = mapper;
        this.streamTimeoutMs = Math.max(60_000L, properties.getBackgroundTasks().getStreamTimeoutMinutes() * 60_000L);
    }

    public SseEmitter subscribe(Long connectionId) {
        return register(connectionId, new SseEmitter(streamTimeoutMs));
    }

    /** 注册一个已经建好的通道。测试从这里塞入自己的 emitter，免得起一个真的 HTTP 长连接。 */
    SseEmitter register(Long connectionId, SseEmitter emitter) {
        Subscriber subscriber = new Subscriber(connectionId, emitter);
        subscribers.add(subscriber);
        // 三个回调都只是摘链接：超时是正常寿命结束（浏览器会重连），错误多半是对端已经走了。
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> {
            subscribers.remove(subscriber);
            emitter.complete();
        });
        emitter.onError(error -> subscribers.remove(subscriber));
        // 先推一份当前快照，界面不必等到下一次变化才有内容。
        publishTo(subscriber, snapshotJson(connectionId));
        return emitter;
    }

    int subscriberCount() {
        return subscribers.size();
    }

    @Scheduled(fixedDelayString = "${app.background-tasks.stream-interval-ms:1000}")
    public void publish() {
        if (subscribers.isEmpty()) return;
        // 同一条连接的订阅者共用一次查询：一个用户开三个标签页不该让后端查三遍。
        Map<Long, String> snapshots = new HashMap<>();
        List<Subscriber> broken = new ArrayList<>();
        for (Subscriber subscriber : subscribers) {
            long key = subscriber.key();
            // 用 containsKey 而不是 computeIfAbsent：快照读失败时返回 null，
            // computeIfAbsent 不会记住这个 null，同一轮里会为每个订阅者重查一次。
            if (!snapshots.containsKey(key)) snapshots.put(key, snapshotJson(subscriber.connectionId));
            String payload = snapshots.get(key);
            if (!publishTo(subscriber, payload)) broken.add(subscriber);
        }
        subscribers.removeAll(broken);
    }

    /**
     * 推送一份快照。内容与上次相同就只在需要时发心跳。
     *
     * @return 连接是否仍然可用
     */
    private boolean publishTo(Subscriber subscriber, String payload) {
        try {
            if (payload != null && !payload.equals(subscriber.lastPayload)) {
                subscriber.emitter.send(SseEmitter.event().name(EVENT_NAME).data(payload, MediaType.APPLICATION_JSON));
                subscriber.lastPayload = payload;
                subscriber.lastSentNanos = System.nanoTime();
                return true;
            }
            if (System.nanoTime() - subscriber.lastSentNanos >= HEARTBEAT.toNanos()) {
                subscriber.emitter.send(SseEmitter.event().comment("keep-alive"));
                subscriber.lastSentNanos = System.nanoTime();
            }
            return true;
        } catch (IOException | IllegalStateException error) {
            // 对端关掉页面就是这个下场，属于正常流程，不该记成错误。
            log.debug("后台任务推送通道已关闭", error);
            return false;
        }
    }

    private String snapshotJson(Long connectionId) {
        try {
            ActiveOperations operations = restores.active(
                    connectionId, histories.findActive(connectionId), sqlFiles.findActive(connectionId));
            return mapper.writeValueAsString(operations);
        } catch (Exception error) {
            log.debug("序列化后台任务快照失败", error);
            return null;
        }
    }

    private static final class Subscriber {
        private final Long connectionId;
        private final SseEmitter emitter;
        private volatile String lastPayload;
        private volatile long lastSentNanos = System.nanoTime();

        private Subscriber(Long connectionId, SseEmitter emitter) {
            this.connectionId = connectionId;
            this.emitter = emitter;
        }

        /** 分组键：连接 id 可以为空（全局视图），Map 的键不能。 */
        private long key() {
            return connectionId == null ? -1L : connectionId;
        }
    }
}
