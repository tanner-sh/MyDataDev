package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将高风险审计事件异步推送到通用 Webhook；任何告警故障都不会回滚业务操作。 */
@Service
public class AuditAlertService {
    private static final Logger log = LoggerFactory.getLogger(AuditAlertService.class);
    private final AppProperties.AuditAlert properties;
    private final HttpClient client;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    public AuditAlertService(AppProperties properties) {
        this.properties = properties.getAuditAlert();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, this.properties.getTimeoutSeconds()))).build();
    }

    public void publish(Event event) {
        if (!enabled() || !actions().contains(event.action())) return;
        long now = System.currentTimeMillis();
        String cooldownKey = event.action() + "\n" + (event.target() == null ? "" : event.target());
        long cooldownMillis = Math.max(0, properties.getCooldownSeconds()) * 1_000L;
        AtomicBoolean deliver = new AtomicBoolean();
        lastSent.compute(cooldownKey, (key, previous) -> {
            if (previous == null || now - previous >= cooldownMillis) {
                deliver.set(true);
                return now;
            }
            return previous;
        });
        if (!deliver.get()) return;
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
            client.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) log.warn("Audit webhook delivery failed action={}", event.action(), error);
                        else if (response.statusCode() < 200 || response.statusCode() >= 300)
                            log.warn("Audit webhook returned status={} action={}", response.statusCode(), event.action());
                    });
        } catch (RuntimeException error) {
            log.warn("Unable to prepare audit webhook action={}", event.action(), error);
        }
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
