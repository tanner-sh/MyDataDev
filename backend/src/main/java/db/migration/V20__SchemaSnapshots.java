package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public class V20__SchemaSnapshots extends BaseJavaMigration {
    @Override public void migrate(Context context) {
        new ResourceDatabasePopulator(new ClassPathResource("schema-snapshot-schema.sql")).populate(context.getConnection());
    }
}
