package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Component
public class LargeFileUploadGuard {
    private final Semaphore permits;

    public LargeFileUploadGuard(AppProperties properties) {
        permits = new Semaphore(Math.max(1, properties.getBackgroundTasks().getMaxConcurrentUploads()), true);
    }

    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }
}
