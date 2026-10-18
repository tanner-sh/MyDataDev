package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** db_execute 原先用全局 SQL 超时（60 秒），默认值沿用它，升级后行为不变。 */
public class V24__McpExecuteTimeout extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE mcp_settings ADD COLUMN IF NOT EXISTS execute_timeout_seconds INT NOT NULL DEFAULT 60");
        }
    }
}
