package com.example.dbadmin.model;

import java.time.Instant;

public record DbConnection(
        long id,
        String name,
        String dbType,
        String jdbcUrl,
        String username,
        String encryptedPassword,
        String environment,
        boolean readonly,
        /** 连接分组，仅用于组织列表。 */
        String groupName,
        /** 逗号分隔的标签，仅用于筛选。 */
        String tags,
        /** 连接级默认 schema/catalog：打开连接时若未指定命名空间就用它。 */
        String defaultSchema,
        /** 每建立一条物理数据库会话时执行的语句，按分号分隔。 */
        String initSql,
        String description,
        /** SSH 隧道配置；{@code null} 与 enabled=false 等价，都表示直连。 */
        SshTunnelSettings sshTunnel,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * 兼容旧的构造顺序。
     *
     * <p>连接档案字段是后加的，而 {@code DbConnection} 在测试与服务里有几十个构造点；保留这个
     * 构造器让「不关心档案字段」的调用方不必逐个填 null。</p>
     */
    public DbConnection(
            long id,
            String name,
            String dbType,
            String jdbcUrl,
            String username,
            String encryptedPassword,
            String environment,
            boolean readonly,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, name, dbType, jdbcUrl, username, encryptedPassword, environment, readonly,
                null, null, null, null, null, createdAt, updatedAt);
    }

    /**
     * 兼容没有 SSH 隧道字段的构造顺序。
     *
     * <p>理由同上：隧道是后加的，绝大多数调用方（尤其是测试）根本不关心它。</p>
     */
    public DbConnection(
            long id,
            String name,
            String dbType,
            String jdbcUrl,
            String username,
            String encryptedPassword,
            String environment,
            boolean readonly,
            String groupName,
            String tags,
            String defaultSchema,
            String initSql,
            String description,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, name, dbType, jdbcUrl, username, encryptedPassword, environment, readonly,
                groupName, tags, defaultSchema, initSql, description, null, createdAt, updatedAt);
    }

    /** 隧道是否真的启用；未配置和显式关闭在调用方看来应当一样。 */
    public boolean usesSshTunnel() {
        return sshTunnel != null && sshTunnel.enabled();
    }
}
