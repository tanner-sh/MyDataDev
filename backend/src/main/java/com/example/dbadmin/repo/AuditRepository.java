package com.example.dbadmin.repo;

import com.example.dbadmin.audit.AuditRequestContext;
import com.example.dbadmin.audit.AuditChain;
import com.example.dbadmin.dto.ApiDtos.AuditEventPage;
import com.example.dbadmin.dto.ApiDtos.AuditEventResponse;
import com.example.dbadmin.dto.ApiDtos.AuditFacets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import com.example.dbadmin.service.AuditAlertService;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Repository
public class AuditRepository {
    private static final Logger log = LoggerFactory.getLogger(AuditRepository.class);
    public static final int MAX_LISTED_DETAIL_CHARS = 4_000;
    /** 单次校验最多回放的事件数：审计表可以有几百万行，一次请求得有个可预期的上界。 */
    private static final int MAX_VERIFIED_EVENTS = 200_000;
    /** 每批取的行数。detail 最大 100 000 字符，批次再大就等于把 CLOB 成片拉进堆里。 */
    private static final int VERIFY_BATCH_SIZE = 500;

    /** 链校验与保留期裁剪的互斥锁。写入不参与：它只往尾部追加，和只读的校验互不干扰。 */
    private final ReentrantLock chainLock = new ReentrantLock();
    private static final String CONNECTION_TARGET_PREFIX = "connection:";
    private final JdbcTemplate jdbc;
    private final MetadataWriteQueue writes;
    private final AuditAlertService alerts;

    @Autowired
    public AuditRepository(JdbcTemplate jdbc, MetadataWriteQueue writes, AuditAlertService alerts) {
        this.jdbc = jdbc;
        this.writes = writes;
        this.alerts = alerts;
    }

    /** 测试和嵌入式调用兼容入口。 */
    public AuditRepository(JdbcTemplate jdbc, MetadataWriteQueue writes) {
        this.jdbc = jdbc;
        this.writes = writes;
        this.alerts = null;
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
        AuditRequestContext context = AuditRequestContext.current();
        String remoteAddress = truncate(context == null ? null : context.remoteAddress(), 120);
        String forwardedFor = truncate(context == null ? null : context.forwardedFor(), 500);
        String userAgent = truncate(context == null ? null : context.userAgent(), 1_000);
        String requestId = truncate(context == null ? null : context.requestId(), 120);
        Timestamp createdAt = Timestamp.from(Instant.now());
        // 审计只写不读，挪出请求线程可以省掉一次同步 H2 写；写失败的处理与之前一致。
        writes.submit(() -> insert(safeActor, safeAction, connectionId, safeTarget, safeDetail,
                remoteAddress, forwardedFor, userAgent, requestId, createdAt));
    }

    private synchronized void insert(String actor, String action, Long connectionId, String target, String detail,
                        String remoteAddress, String forwardedFor, String userAgent, String requestId, Timestamp createdAt) {
        try {
            // event_hash 可空：直接改库、导入旧备份或回填中断都会留下空值。这里不能用
            // findFirst() —— 表非空但首行哈希为空时它会抛 NPE，而 insert 吞掉 RuntimeException，
            // 结果是此后每一条审计都静默失败。空值原样往下传：链在这里本来就断了，
            // 让 verifyChain 把它报成断链，比继续写审计更重要的事情没有。
            List<String> latest = jdbc.query("SELECT event_hash FROM audit_log ORDER BY id DESC LIMIT 1",
                    (rs, row) -> rs.getString(1));
            String previousHash = latest.isEmpty()
                    ? jdbc.queryForObject("SELECT anchor_hash FROM audit_chain_state WHERE id = 1", String.class)
                    : latest.get(0);
            String eventHash = AuditChain.hash(previousHash, actor, action, connectionId, target, detail,
                    remoteAddress, forwardedFor, userAgent, requestId, createdAt);
            jdbc.update("""
                    INSERT INTO audit_log(actor, action, connection_id, target, detail,
                                          remote_address, forwarded_for, user_agent, request_id, created_at,
                                          previous_hash, event_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, actor, action, connectionId, target, detail,
                    remoteAddress, forwardedFor, userAgent, requestId, createdAt, previousHash, eventHash);
            if (alerts != null) alerts.publish(new AuditAlertService.Event(actor, action, target, detail,
                    requestId, remoteAddress, eventHash, createdAt.toInstant()));
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
                "SELECT id, actor, action, target, detail, remote_address, forwarded_for, user_agent, request_id, created_at FROM audit_log"
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
                            rs.getString("remote_address"),
                            rs.getString("forwarded_for"),
                            rs.getString("user_agent"),
                            rs.getString("request_id"),
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

    /** 从保留期锚点开始逐条重算，能检测删除、插入、字段修改和顺序篡改。 */
    public ChainVerification verifyChain() {
        return verifyChain(null);
    }

    /**
     * 校验审计哈希链，可以分次做完。
     *
     * <p>按 id 分批读，不再一次性物化整张表 —— detail 是最大 100 000 字符的 CLOB，几百万行
     * 拉进堆里就是 OOM，而这个接口是审计面板一打开就会调的。</p>
     *
     * <p>也不再占用 {@link #insert} 那把锁。校验只读，写入只往尾部追加，两者互不干扰；开始时
     * 取一次 id 上界，之后新写入的留给下一次校验，循环因此一定会结束。真正要互斥的是
     * {@link #purgeBefore} —— 它删前缀并推进锚点，校验中途撞上会把好链报成坏链 —— 所以只有
     * 这两者共用 {@code chainLock}。以前是全部挤在同一个 monitor 上，打开一次审计面板就把
     * 所有审计写入卡住整整一趟全表扫描。</p>
     *
     * <p>单次最多回放 {@value #MAX_VERIFIED_EVENTS} 条。到顶时返回 {@code complete=false} 和
     * {@code nextId}，调用方既不会误以为整条链都验过，也能接着往下验。</p>
     *
     * @param fromId 从这个 id 开始接着校验；null 表示从保留期锚点开始
     */
    public ChainVerification verifyChain(Long fromId) {
        chainLock.lock();
        try {
            String anchor = anchorHash();
            String expectedPrevious = anchor;
            long lastId = 0;
            if (fromId != null) {
                // 续验：上一批最后验过的那条就是这里的「上一个哈希」，不需要凭空信任谁。
                List<String> resumed = jdbc.query("SELECT event_hash FROM audit_log WHERE id < ? ORDER BY id DESC LIMIT 1",
                        (rs, row) -> rs.getString(1), fromId);
                if (!resumed.isEmpty()) expectedPrevious = resumed.get(0);
                lastId = fromId - 1;
            }
            Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
            long maxId = max == null ? 0 : max;
            long checked = 0;
            while (lastId < maxId && checked < MAX_VERIFIED_EVENTS) {
                int limit = (int) Math.min(VERIFY_BATCH_SIZE, MAX_VERIFIED_EVENTS - checked);
                List<ChainRow> batch = jdbc.query("""
                        SELECT id, actor, action, connection_id, target, detail, remote_address, forwarded_for,
                               user_agent, request_id, created_at, previous_hash, event_hash
                        FROM audit_log WHERE id > ? AND id <= ? ORDER BY id LIMIT ?
                        """, (rs, row) -> new ChainRow(rs.getLong("id"), rs.getString("actor"), rs.getString("action"),
                        rs.getObject("connection_id", Long.class), rs.getString("target"), rs.getString("detail"),
                        rs.getString("remote_address"), rs.getString("forwarded_for"), rs.getString("user_agent"),
                        rs.getString("request_id"), rs.getTimestamp("created_at"), rs.getString("previous_hash"),
                        rs.getString("event_hash")), lastId, maxId, limit);
                if (batch.isEmpty()) break;
                for (ChainRow row : batch) {
                    String calculated = AuditChain.hash(expectedPrevious, row.actor(), row.action(), row.connectionId(),
                            row.target(), row.detail(), row.remoteAddress(), row.forwardedFor(), row.userAgent(),
                            row.requestId(), row.createdAt());
                    if (!java.util.Objects.equals(expectedPrevious, row.previousHash()) || !calculated.equals(row.eventHash())) {
                        return new ChainVerification(false, checked, row.id(), anchor, headHash(anchor), true, null);
                    }
                    expectedPrevious = row.eventHash();
                    lastId = row.id();
                    checked++;
                }
            }
            boolean complete = lastId >= maxId;
            return new ChainVerification(true, checked, null, anchor, headHash(anchor), complete, complete ? null : lastId + 1);
        } finally {
            chainLock.unlock();
        }
    }

    private String anchorHash() {
        return jdbc.queryForObject("SELECT anchor_hash FROM audit_chain_state WHERE id = 1", String.class);
    }

    /** 表尾那条记录的哈希；表空时就是锚点。event_hash 可空，所以不能用 findFirst()。 */
    private String headHash(String anchor) {
        List<String> head = jdbc.query("SELECT event_hash FROM audit_log ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getString(1));
        return head.isEmpty() ? anchor : head.get(0);
    }

    @Transactional
    public int purgeBefore(Timestamp cutoff, int batch) {
        // chainLock 要在 monitor 之前拿：反过来的话，裁剪会一边等着校验跑完一边把写入堵死。
        chainLock.lock();
        try {
            synchronized (this) {
                // 只能裁掉连续前缀，否则锚点会跨过仍保留的事件，链校验将失去意义。
                List<PrunedRow> prefix = jdbc.query("SELECT id, event_hash, created_at FROM audit_log ORDER BY id LIMIT ?",
                        (rs, row) -> new PrunedRow(rs.getLong("id"), rs.getString("event_hash"), rs.getTimestamp("created_at")), batch);
                List<PrunedRow> rows = prefix.stream().takeWhile(row -> row.createdAt() != null && row.createdAt().before(cutoff)).toList();
                if (rows.isEmpty()) return 0;
                long lastId = rows.get(rows.size() - 1).id();
                jdbc.update("UPDATE audit_chain_state SET anchor_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                        rows.get(rows.size() - 1).hash());
                return jdbc.update("DELETE FROM audit_log WHERE id <= ?", lastId);
            }
        } finally {
            chainLock.unlock();
        }
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

    /**
     * 校验结果。{@code complete=false} 表示这一次没验到表尾（单次上限），
     * {@code nextId} 是继续校验的起点 —— 不能把它当成「整条链完整」。
     */
    public record ChainVerification(boolean valid, long checkedEvents, Long firstInvalidId, String anchorHash,
                                    String headHash, boolean complete, Long nextId) {}
    private record PrunedRow(long id, String hash, Timestamp createdAt) {}
    private record ChainRow(long id, String actor, String action, Long connectionId, String target, String detail,
                            String remoteAddress, String forwardedFor, String userAgent, String requestId,
                            Timestamp createdAt, String previousHash, String eventHash) {}
}
