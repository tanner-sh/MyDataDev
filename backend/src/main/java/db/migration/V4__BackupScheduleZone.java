package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * 记录备份执行计划所属时区。此前 cron 一律按服务端默认时区解释，
 * 服务器跑在 UTC 时用户设置的 02:00 实际会在本地 10:00 触发。
 */
public class V4__BackupScheduleZone extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE backup_task ADD COLUMN IF NOT EXISTS schedule_zone VARCHAR(80)");
        }
    }
}
