package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V22__SqlTemplateParameters extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE sql_snippet ADD COLUMN IF NOT EXISTS parameters_json CLOB DEFAULT '[]'");
        }
    }
}
