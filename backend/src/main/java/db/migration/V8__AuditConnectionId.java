package db.migration;

import com.example.dbadmin.repo.AuditRepository;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计记录的连接归属从字符串改为独立字段。
 *
 * <p>历史记录里能认出 {@code "connection:<id>"} 的回填进新列；认不出来的（备份任务名、
 * 文件服务名、MCP 这类）保持为空 —— 猜一个可能错的连接比留空更糟。</p>
 *
 * <p>回填写在 Java 里而不是 SQL：从 {@code "connection:7 table:orders"} 里取出数字要处理
 * 分隔、非数字、溢出几种情况，用 SQL 的字符串函数拼出来的表达式既难读也难验证，而这里可以
 * 直接复用并单测 {@link AuditRepository#connectionIdFromTarget}。</p>
 */
public class V8__AuditConnectionId extends BaseJavaMigration {
    /** 一次提交的回填条数。审计表可能很大，一次性更新会把整张表锁住。 */
    private static final int BATCH_SIZE = 500;

    @Override
    public void migrate(Context context) {
        new ResourceDatabasePopulator(new ClassPathResource("audit-connection-schema.sql")).populate(context.getConnection());
        // Flyway 提供的连接不能关闭，SingleConnectionDataSource 的 suppressClose 正是为此。
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        backfill(jdbc);
    }

    /** 回填历史行，返回被填上的条数。可重入：已经填过的行不会再次匹配。 */
    public static int backfill(JdbcTemplate jdbc) {
        int updated = 0;
        // 认不出连接 id 的行不会被更新，它们会永远满足查询条件；用 skipped 当偏移把它们
        // 让过去，否则这个循环会一直读到同一批数据。
        int skipped = 0;
        while (true) {
            List<Object[]> batch = new ArrayList<>();
            int[] fetched = {0};
            jdbc.query(
                    "SELECT id, target FROM audit_log WHERE connection_id IS NULL AND target LIKE 'connection:%'"
                            + " ORDER BY id LIMIT " + BATCH_SIZE + " OFFSET " + skipped,
                    rs -> {
                        fetched[0]++;
                        Long connectionId = AuditRepository.connectionIdFromTarget(rs.getString("target"));
                        if (connectionId != null) batch.add(new Object[]{connectionId, rs.getLong("id")});
                    });
            if (!batch.isEmpty()) {
                jdbc.batchUpdate("UPDATE audit_log SET connection_id = ? WHERE id = ?", batch);
                updated += batch.size();
            }
            skipped += fetched[0] - batch.size();
            if (fetched[0] < BATCH_SIZE) break;
        }
        return updated;
    }
}
