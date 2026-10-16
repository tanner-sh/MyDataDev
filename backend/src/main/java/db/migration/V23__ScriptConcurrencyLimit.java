package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V23__ScriptConcurrencyLimit extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE mcp_settings ADD COLUMN IF NOT EXISTS max_scripts_per_connection INT NOT NULL DEFAULT 1");
        }
    }
}
