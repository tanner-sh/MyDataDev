package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** MCP Agent 的连接授权增加访问档位；既有授权保持只读，升级不改变任何 Agent 的现有能力。 */
public class V12__McpAccessLevels extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        new ResourceDatabasePopulator(new ClassPathResource("mcp-access-level-schema.sql")).populate(context.getConnection());
    }
}
