package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 脚本里 CREATE/ALTER … COMPILE 出来的 INVALID 对象：语句成功了，但对象是坏的，结果里要能看见。 */
public class V25__SqlFileCompilationErrors extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE sql_file_execution ADD COLUMN IF NOT EXISTS compilation_errors CLOB");
        }
    }
}
