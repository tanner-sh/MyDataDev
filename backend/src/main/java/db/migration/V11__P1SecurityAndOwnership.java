package db.migration;

import com.example.dbadmin.audit.AuditChain;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** P1 增量迁移：旧 SQL 片段保持共享，并为全部既有审计记录回填哈希链。 */
public class V11__P1SecurityAndOwnership extends BaseJavaMigration {
    /**
     * 回填的 batch 刷新间隔。
     *
     * <p>攒满整张表再 executeBatch 会把所有参数集堆在驱动内存里：审计表大的实例升级时会在
     * 迁移过程中 OOM，而迁移这时已被标记为失败，应用直接起不来 —— 修复代价远高于多几次
     * 往返。同理，读游标也要限制 fetch size，否则 H2 之外的驱动会先把结果集全拉下来。</p>
     */
    private static final int BATCH_SIZE = 2_000;

    @Override
    public void migrate(Context context) throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("p1-schema.sql")).populate(context.getConnection());
        String previous = null;
        try (Statement statement = context.getConnection().createStatement();
             ResultSet rows = fetchInBatches(statement).executeQuery("""
                     SELECT id, actor, action, connection_id, target, detail, remote_address,
                            forwarded_for, user_agent, request_id, created_at
                     FROM audit_log ORDER BY id
                     """);
             PreparedStatement update = context.getConnection().prepareStatement(
                     "UPDATE audit_log SET previous_hash = ?, event_hash = ? WHERE id = ?")) {
            int pending = 0;
            while (rows.next()) {
                Long connectionId = rows.getObject("connection_id", Long.class);
                String hash = AuditChain.hash(
                        previous, rows.getString("actor"), rows.getString("action"), connectionId,
                        rows.getString("target"), rows.getString("detail"), rows.getString("remote_address"),
                        rows.getString("forwarded_for"), rows.getString("user_agent"), rows.getString("request_id"),
                        rows.getTimestamp("created_at")
                );
                update.setString(1, previous);
                update.setString(2, hash);
                update.setLong(3, rows.getLong("id"));
                update.addBatch();
                previous = hash;
                if (++pending >= BATCH_SIZE) {
                    update.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) update.executeBatch();
        }
    }

    private static Statement fetchInBatches(Statement statement) throws java.sql.SQLException {
        statement.setFetchSize(BATCH_SIZE);
        return statement;
    }
}
