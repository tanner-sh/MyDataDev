package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 命令窗口：编辑器里的脚本按 SQL*Plus 的方式逐条执行，结果是一条滚动的日志而不是一堆结果标签。
 *
 * <p>任务本身仍是一个普通的 SQL 文件任务（排队、并发闸门、取消、编译错误都沿用），这里只多两样东西：
 * 遇错策略与失败计数（遇错继续时任务跑完了也可能有失败），以及逐条日志。日志单独成表、按 seq 增量读，
 * 不塞进任务行 —— 一份几千条的脚本，每次轮询都把整份日志读一遍是不行的。</p>
 */
public class V28__SqlCommandWindow extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sql_command_job (
                        job_id BIGINT PRIMARY KEY,
                        on_error VARCHAR(16) NOT NULL,
                        failed_count BIGINT NOT NULL DEFAULT 0,
                        first_failed_index BIGINT,
                        log_count BIGINT NOT NULL DEFAULT 0,
                        log_truncated BOOLEAN NOT NULL DEFAULT FALSE,
                        CONSTRAINT fk_sql_command_job_execution FOREIGN KEY (job_id) REFERENCES sql_file_execution(id) ON DELETE CASCADE
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sql_command_log (
                        job_id BIGINT NOT NULL,
                        seq BIGINT NOT NULL,
                        kind VARCHAR(16) NOT NULL,
                        statement_index BIGINT,
                        start_line INT,
                        end_line INT,
                        elapsed_ms BIGINT,
                        text CLOB NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (job_id, seq),
                        CONSTRAINT fk_sql_command_log_execution FOREIGN KEY (job_id) REFERENCES sql_file_execution(id) ON DELETE CASCADE
                    )""");
        }
    }
}
