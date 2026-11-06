package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * SQL 文件任务的语句分隔方式：DBeaver「自定义分隔符」（独占行 !）与「空行分隔」导出的脚本按默认的
 * 分号规则根本切不开。分析与执行是两次独立的读取，必须用同一种切法，所以要落库。null 即默认规则，
 * 与这一列加上之前的任务完全一致。
 */
public class V27__SqlFileStatementDelimiter extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE sql_file_execution ADD COLUMN IF NOT EXISTS statement_delimiter VARCHAR(32)");
        }
    }
}
