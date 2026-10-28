package com.example.dbadmin.service;

import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.RemotePoolSettingsRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

@Service
@DependsOnDatabaseInitialization
public class RemotePoolSettingsService {
    private final RemotePoolSettingsRepository repository;
    private final RemoteDataSourceRegistry pools;
    private final AuditRepository audit;

    public RemotePoolSettingsService(RemotePoolSettingsRepository repository, RemoteDataSourceRegistry pools,
                                     AuditRepository audit) {
        this.repository = repository;
        this.pools = pools;
        this.audit = audit;
    }

    @PostConstruct
    void initialize() {
        repository.findMaximumPoolSize().ifPresent(pools::resizePools);
    }

    public synchronized int maximumPoolSize() {
        return pools.maximumPoolSize();
    }

    public synchronized int updateMaximumPoolSize(int size, String actor) {
        if (size < 2 || size > 50) throw new IllegalArgumentException("每个连接池的连接上限必须在 2–50 之间");
        int previous = pools.maximumPoolSize();
        // 单条 MERGE 提交成功后才更新运行值。串行保存，避免并发请求令持久值和运行值交错。
        repository.saveMaximumPoolSize(size);
        pools.resizePools(size);
        audit.global(actor, "CONNECTION_POOL_SETTINGS_UPDATE", "connection-pools",
                "maximumPoolSize=" + previous + " -> " + size);
        return size;
    }
}
