package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.AuditEventPage;
import com.example.dbadmin.dto.ApiDtos.AuditEventResponse;
import com.example.dbadmin.dto.ApiDtos.AuditFacets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class AuditRepository {
    private static final Logger log = LoggerFactory.getLogger(AuditRepository.class);
    public static final int MAX_LISTED_DETAIL_CHARS = 4_000;
    private static final String CONNECTION_TARGET_PREFIX = "connection:";
    private final JdbcTemplate jdbc;
    private final MetadataWriteQueue writes;

    public AuditRepository(JdbcTemplate jdbc, MetadataWriteQueue writes) {
        this.jdbc = jdbc;
        this.writes = writes;
    }

    /**
     * 记录一次发生在某条连接上的操作。
     *
     * <p>连接 id 写进独立字段，而不是拼进 {@code target} 字符串。以前全靠调用方各自拼
     * {@code "connection:" + id}，只要有一处没照做（备份、恢复、连接自身的增删改都没照做），
     * 按连接筛选就会静默漏掉它 —— 而「谁改了这条生产连接」恰恰是最需要查出来的那类记录。</p>
     */
    public void onConnection(String actor, String action, long connectionId, String detail) {
        onConnection(actor, action, connectionId, null, detail);
    }

    /**
     * 同上，{@code subject} 补充连接内部的对象，例如 {@code "table:orders"}。
     * 它只影响给人看的 {@code target} 文案，不参与筛选。
     */
    public void onConnection(String actor, String action, long connectionId, String subject, String detail) {
        String target = subject == null || subject.isBlank()
                ? CONNECTION_TARGET_PREFIX + connectionId
                : CONNECTION_TARGET_PREFIX + connectionId + " " + subject;
        write(actor, action, connectionId, target, detail);
    }

    /**
     * 记录一次与具体连接无关的操作：MCP 配置与 Agent、文件服务、SQL 片段。
     *
     * <p>凡是有连接可归属的都该走 {@link #onConnection}；这个方法故意起了个显眼的名字，
     * 免得又有人把连接 id 拼进 target 字符串。</p>
     */
    public void global(String actor, String action, String target, String detail) {
        write(actor, action, null, target, detail);
    }

    private void write(String actor, String action, Long connectionId, String target, String detail) {
        // 参数在提交时就截断并固定下来，后台线程不再依赖调用方的任何可变状态。
        String safeActor = truncate(actor == null || actor.isBlank() ? "anonymous" : actor, 120);
        String safeAction = truncate(action, 80);
        String safeTarget = truncate(target, 500);
        String safeDetail = truncate(detail, 100_000);
        // 审计只写不读，挪出请求线程可以省掉一次同步 H2 写；写失败的处理与之前一致。
        writes.submit(() -> insert(safeActor, safeAction, connectionId, safeTarget, safeDetail));
    }

    private void insert(String actor, String action, Long connectionId, String target, String detail) {
        try {
            jdbc.update("INSERT INTO audit_log(actor, action, connection_id, target, detail) VALUES (?, ?, ?, ?, ?)",
                    actor, action, connectionId, target, detail);
        } catch (RuntimeException error) {
            // A local observability failure must never make an already-completed
            // remote database operation look failed to the caller.
            log.error("Unable to persist audit event action={} target={}", action, target, error);
        }
    }

    /**
     * 从历史记录的 {@code target} 里还原连接 id，供迁移回填使用。
     *
     * <p>只认 {@code "connection:<数字>"} 这一种写法（后面可以跟别的片段）；认不出来的返回
     * {@code null}，那些行的 connection_id 保持为空，而不是猜一个可能错的值。</p>
     */
    public static Long connectionIdFromTarget(String target) {
        if (target == null || !target.startsWith(CONNECTION_TARGET_PREFIX)) return null;
        int start = CONNECTION_TARGET_PREFIX.length();
        int end = target.indexOf(' ', start);
        String digits = end < 0 ? target.substring(start) : target.substring(start, end);
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) return null;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /**
     * 分页查询审计记录。
     *
     * <p>审计此前只写不读：全仓除了这里的 INSERT 和 HistoryCleanupService 的保留期清理，
     * 没有任何地方查询过 audit_log。而 /api 本身没有用户认证（安全边界由外层反向代理承担），
     * 出事之后能回溯「谁在哪条连接上做了什么」是这张表存在的唯一意义。</p>
     *
     * <p>detail 是最大 100 000 字符的 CLOB，列表里按 {@link #MAX_LISTED_DETAIL_CHARS} 截断，
     * 并通过 detailTruncated 告诉前端还有后续内容。</p>
     */
    public AuditEventPage findPage(AuditQuery query) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (query.actor() != null) {
            where.append(" AND actor = ?");
            parameters.add(query.actor());
        }
        if (query.action() != null) {
            where.append(" AND action = ?");
            parameters.add(query.action());
        }
        if (query.connectionId() != null) {
            where.append(" AND connection_id = ?");
            parameters.add(query.connectionId());
        }
        if (query.keyword() != null) {
            where.append(" AND (LOWER(target) LIKE ? ESCAPE '!' OR LOWER(detail) LIKE ? ESCAPE '!')");
            String pattern = likePattern(query.keyword().toLowerCase(Locale.ROOT));
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (query.from() != null) {
            where.append(" AND created_at >= ?");
            parameters.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            where.append(" AND created_at <= ?");
            parameters.add(Timestamp.from(query.to()));
        }
        // 多取一条用来判断 hasMore，避免为了分页再跑一次 COUNT(*)。
        parameters.add(query.pageSize() + 1);
        parameters.add((long) query.page() * query.pageSize());

        List<AuditEventResponse> rows = jdbc.query(
                "SELECT id, actor, action, target, detail, created_at FROM audit_log"
                        + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    String detail = rs.getString("detail");
                    boolean truncated = detail != null && detail.length() > MAX_LISTED_DETAIL_CHARS;
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    return new AuditEventResponse(
                            rs.getLong("id"),
                            rs.getString("actor"),
                            rs.getString("action"),
                            rs.getString("target"),
                            truncated ? detail.substring(0, MAX_LISTED_DETAIL_CHARS) : detail,
                            truncated,
                            createdAt == null ? "" : createdAt.toInstant().toString()
                    );
                },
                parameters.toArray()
        );
        boolean hasMore = rows.size() > query.pageSize();
        return new AuditEventPage(
                hasMore ? List.copyOf(rows.subList(0, query.pageSize())) : List.copyOf(rows),
                query.page(),
                query.pageSize(),
                hasMore
        );
    }

    /** 过滤下拉的候选值取自实际写入过的记录，新增动作码不必同步维护一份枚举。 */
    public AuditFacets facets() {
        return new AuditFacets(
                jdbc.queryForList("SELECT DISTINCT actor FROM audit_log ORDER BY actor", String.class),
                jdbc.queryForList("SELECT DISTINCT action FROM audit_log ORDER BY action", String.class)
        );
    }

    /** 单条记录的完整 detail，列表里被截断后由前端按需拉取。 */
    public Optional<String> detail(long id) {
        return jdbc.query("SELECT detail FROM audit_log WHERE id = ?", (rs, rowNum) -> rs.getString("detail"), id)
                .stream().findFirst();
    }

    private String likePattern(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, maximumLength);
    }

    /** 审计查询条件。所有字段都已归一化：空串一律转成 null。 */
    public record AuditQuery(
            String actor,
            String action,
            Long connectionId,
            String keyword,
            Instant from,
            Instant to,
            int page,
            int pageSize
    ) {
    }
}
