package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 将高风险审计事件异步推送到通用 Webhook；任何告警故障都不会回滚业务操作。 */
@Service
public class AuditAlertService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AuditAlertService.class);
    private final AppProperties.AuditAlert properties;
    private final HttpClient client;
    private final ThreadPoolExecutor sender;
    private final Object rateLock = new Object();
    /** access-order + 容量上限，避免攻击者用无限用户名撑大堆内存。 */
    private final LinkedHashMap<String, Long> lastSent = new LinkedHashMap<>(64, 0.75f, true);
    private long globalWindowStartedAt;
    private int globalWindowCount;

    public AuditAlertService(AppProperties properties) {
        this.properties = properties.getAuditAlert();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, this.properties.getTimeoutSeconds()))).build();
        int workers = Math.max(1, Math.min(this.properties.getWorkerThreads(), 8));
        this.sender = new ThreadPoolExecutor(
                workers, workers, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, this.properties.getQueueCapacity())),
                runnable -> {
                    Thread thread = new Thread(runnable, "dbadmin-audit-alert");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void publish(Event event) {
        if (!enabled() || !actions().contains(event.action())) return;
        long now = System.currentTimeMillis();
        if (!admit(event, now)) return;
        try {
            String json = json(event);
            HttpRequest.Builder request = HttpRequest.newBuilder(webhookUri())
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "MyDataDev-Audit/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (properties.getSigningSecret() != null && !properties.getSigningSecret().isBlank()) {
                request.header("X-MyDataDev-Signature-256", "sha256=" + hmac(json, properties.getSigningSecret()));
            }
            HttpRequest prepared = request.build();
            sender.execute(() -> deliver(event, prepared));
        } catch (RejectedExecutionException full) {
            log.warn("Audit webhook queue is full; dropping action={}", event.action());
        } catch (RuntimeException error) {
            log.warn("Unable to prepare audit webhook action={}", event.action(), error);
        }
    }

    private boolean admit(Event event, long now) {
        String discriminator = "AUTH_LOGIN_FAILED".equals(event.action())
                ? event.remoteAddress()
                : event.target();
        String cooldownKey = event.action() + "\n" + (discriminator == null ? "" : discriminator);
        long cooldownMillis = Math.max(0, properties.getCooldownSeconds()) * 1_000L;
        synchronized (rateLock) {
            evictExpired(now, Math.max(cooldownMillis, 60_000L));
            Long previous = lastSent.get(cooldownKey);
            if (previous != null && now - previous < cooldownMillis) return false;
            if (globalWindowStartedAt == 0 || now - globalWindowStartedAt >= 60_000L) {
                globalWindowStartedAt = now;
                globalWindowCount = 0;
            }
            if (globalWindowCount >= Math.max(1, properties.getMaxEventsPerMinute())) return false;
            globalWindowCount++;
            lastSent.put(cooldownKey, now);
            int maximum = Math.max(1, properties.getMaxCooldownEntries());
            while (lastSent.size() > maximum) {
                Iterator<String> keys = lastSent.keySet().iterator();
                keys.next();
                keys.remove();
            }
            return true;
        }
    }

    private void evictExpired(long now, long ttlMillis) {
        lastSent.entrySet().removeIf(entry -> now - entry.getValue() >= ttlMillis);
    }

    private void deliver(Event event, HttpRequest request) {
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Audit webhook returned status={} action={}", response.statusCode(), event.action());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            log.warn("Audit webhook delivery failed action={}", event.action(), error);
        }
    }

    int trackedCooldownKeys() {
        synchronized (rateLock) {
            return lastSent.size();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        sender.shutdownNow();
    }

    public Status status() {
        return new Status(enabled(), properties.getWebhookUrl() != null && !properties.getWebhookUrl().isBlank(),
                properties.getSigningSecret() != null && !properties.getSigningSecret().isBlank(),
                properties.getCooldownSeconds(), actions());
    }

    public boolean enabled() {
        return properties.isEnabled() && properties.getWebhookUrl() != null && !properties.getWebhookUrl().isBlank();
    }

    private Set<String> actions() {
        Set<String> values = new LinkedHashSet<>();
        for (String action : properties.getActions()) if (action != null && !action.isBlank()) values.add(action.trim().toUpperCase(Locale.ROOT));
        return Set.copyOf(values);
    }

    private URI webhookUri() {
        URI uri = URI.create(properties.getWebhookUrl().trim());
        if (!Set.of("http", "https").contains(uri.getScheme())) throw new IllegalArgumentException("审计 Webhook 只支持 HTTP/HTTPS");
        return uri;
    }

    private static String json(Event event) {
        return "{" + field("type", "mydatadev.audit") + ',' + field("occurredAt", event.createdAt().toString()) + ','
                + field("actor", event.actor()) + ',' + field("action", event.action()) + ','
                + field("target", event.target()) + ',' + field("detail", event.detail()) + ','
                + field("requestId", event.requestId()) + ',' + field("remoteAddress", event.remoteAddress()) + ','
                + field("eventHash", event.eventHash()) + "}";
    }

    private static String field(String name, String value) {
        return quote(name) + ':' + (value == null ? "null" : quote(value));
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('\"').toString();
    }

    private static String hmac(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("无法生成 Webhook 签名", error); }
    }

    public record Event(String actor, String action, String target, String detail, String requestId,
                        String remoteAddress, String eventHash, Instant createdAt) {}
    public record Status(boolean enabled, boolean webhookConfigured, boolean signed, int cooldownSeconds, Set<String> actions) {}
}
