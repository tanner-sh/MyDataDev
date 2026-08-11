package com.example.dbadmin.desktop;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;

@Service
@Profile("desktop")
public class DesktopLifecycleService {
    private final DesktopRuntimeProperties properties;
    private final ConfigurableApplicationContext context;
    private final LongPredicate processAlive;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    @Autowired
    public DesktopLifecycleService(
            DesktopRuntimeProperties properties,
            ConfigurableApplicationContext context
    ) {
        this(properties, context, pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    DesktopLifecycleService(
            DesktopRuntimeProperties properties,
            ConfigurableApplicationContext context,
            LongPredicate processAlive
    ) {
        this.properties = properties;
        this.context = context;
        this.processAlive = processAlive;
    }

    public boolean authorized(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        return MessageDigest.isEqual(
                properties.controlToken().getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    public void requestShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        Thread shutdown = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            context.close();
        }, "mydatadev-desktop-shutdown");
        shutdown.setDaemon(false);
        shutdown.start();
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 5_000)
    void stopWhenDesktopParentExits() {
        if (!processAlive.test(properties.parentPid())) requestShutdown();
    }
}
