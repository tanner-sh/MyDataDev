package com.example.dbadmin.desktop;

import com.example.dbadmin.DbAdminApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopShutdownIntegrationTest {
    @TempDir
    Path home;

    @Test
    void closesActiveAndDisconnectedStatusStreamsBeforeWaitingForWebRequests() throws Exception {
        SpringApplication app = new SpringApplication(DbAdminApplication.class);
        app.setRegisterShutdownHook(false);
        app.setAdditionalProfiles("desktop");
        ConfigurableApplicationContext context = app.run(
                "--MYDATADEV_DESKTOP_HOME=" + home,
                "--MYDATADEV_DESKTOP_CONTROL_TOKEN=shutdown-test-token",
                "--MYDATADEV_DESKTOP_PARENT_PID=" + ProcessHandle.current().pid(),
                "--server.port=0",
                "--app.crypto-key-source=FILE",
                "--app.crypto-key-file=" + home.resolve("master.key"),
                "--spring.datasource.url=jdbc:h2:mem:shutdown-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--spring.lifecycle.timeout-per-shutdown-phase=10s",
                "--app.mcp.enabled=false",
                "--logging.file.name=" + home.resolve("mydatadev.log")
        );
        try {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            try (Socket disconnected = openStatusStream(port); Socket active = openStatusStream(port)) {
                // 客户端断开后，服务器可能直到下一次心跳才发现；另一个连接则一直保持打开。
                disconnected.close();
                long start = System.nanoTime();
                context.close();
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                assertThat(elapsed).as("状态推送不应拖到 10 秒的 Web 优雅关闭上限")
                        .isLessThan(Duration.ofSeconds(7));
            }
        } finally {
            if (context.isActive()) context.close();
        }
    }

    private Socket openStatusStream(int port) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /api/restores/operations/stream HTTP/1.1\r\n"
                    + "Host: localhost\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            BufferedReader response = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertThat(response.readLine()).contains("200");
            String line;
            do {
                line = response.readLine();
                assertThat(line).as("等待第一条后台任务快照").isNotNull();
            } while (!line.startsWith("data:"));
            return socket;
        } catch (Exception | AssertionError error) {
            socket.close();
            throw error;
        }
    }
}
