package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 未在界面保存时继续使用部署配置，不用迁移中的默认值覆盖已有部署。 */
public class V26__RemotePoolSettings extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS remote_pool_settings (
                        id INT PRIMARY KEY CHECK (id = 1),
                        maximum_pool_size INT NOT NULL CHECK (maximum_pool_size BETWEEN 2 AND 50),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }
}
