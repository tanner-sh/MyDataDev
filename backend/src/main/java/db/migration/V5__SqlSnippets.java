package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 保存的 SQL 片段。执行历史有保留期且会被定期清理，反复使用的查询需要一个长期归宿。
 */
public class V5__SqlSnippets extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        new ResourceDatabasePopulator(new ClassPathResource("snippet-schema.sql")).populate(context.getConnection());
    }
}
