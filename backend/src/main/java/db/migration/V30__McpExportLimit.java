package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * db_export_query 的文件上限（KB）。文件整份进工具响应、进模型上下文，默认 1 MB；
 * 管理员按自己客户端的能力在 MCP 设置里调。
 */
public class V30__McpExportLimit extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE mcp_settings ADD COLUMN IF NOT EXISTS export_max_kilobytes INT NOT NULL DEFAULT 1024");
        }
    }
}
