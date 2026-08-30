package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAlertServiceTest {
    @Test
    void sendsSignedSelectedEventAndSuppressesDuplicatesDuringCooldown() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audit", exchange -> {
            requests.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signature.set(exchange.getRequestHeaders().getFirst("X-MyDataDev-Signature-256"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            AppProperties properties = new AppProperties();
            properties.getAuditAlert().setEnabled(true);
            properties.getAuditAlert().setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/audit");
            properties.getAuditAlert().setSigningSecret("test-secret");
            properties.getAuditAlert().setCooldownSeconds(60);
            properties.getAuditAlert().setActions(List.of("AUDIT_ALERT_TEST"));
            AuditAlertService service = new AuditAlertService(properties);
            AuditAlertService.Event event = new AuditAlertService.Event(
                    "admin", "AUDIT_ALERT_TEST", "audit:webhook", "测试", "request-1",
                    "127.0.0.1", "abc123", Instant.parse("2026-08-30T02:00:00Z"));

            service.publish(event);
            service.publish(event);

            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);
            assertThat(requests).hasValue(1);
            assertThat(body.get()).contains("\"action\":\"AUDIT_ALERT_TEST\"").contains("\"detail\":\"测试\"");
            assertThat(signature.get()).isEqualTo("sha256=" + hmac(body.get(), "test-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remainsDisabledUntilBothFlagAndUrlAreConfigured() {
        AppProperties properties = new AppProperties();
        AuditAlertService service = new AuditAlertService(properties);

        assertThat(service.status().enabled()).isFalse();
        assertThat(service.status().webhookConfigured()).isFalse();
    }

    private static String hmac(String value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
