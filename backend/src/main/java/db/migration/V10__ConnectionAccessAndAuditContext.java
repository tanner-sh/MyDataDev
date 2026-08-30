package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 新增连接 ACL、用户组和审计请求上下文；已有连接保持共享。 */
public class V10__ConnectionAccessAndAuditContext extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        new ResourceDatabasePopulator(new ClassPathResource("access-control-schema.sql")).populate(context.getConnection());
    }
}
