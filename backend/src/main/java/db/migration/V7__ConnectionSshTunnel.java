package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 连接上的 SSH 隧道配置。
 *
 * <p>生产库通常只对跳板机开放，工具进程直连不到。把跳板机信息记在连接上，建连时先起一条
 * 本地端口转发，JDBC 地址在运行时被改写到转发端口，其余功能（元数据、备份、MCP）都不用感知。</p>
 */
public class V7__ConnectionSshTunnel extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        new ResourceDatabasePopulator(new ClassPathResource("connection-ssh-schema.sql")).populate(context.getConnection());
    }
}
