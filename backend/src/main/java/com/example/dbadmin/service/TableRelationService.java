package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.MinedTableRelation;
import com.example.dbadmin.dto.ApiDtos.TableRelation;
import com.example.dbadmin.dto.ApiDtos.TableRelationDecisionRequest;
import com.example.dbadmin.dto.ApiDtos.TableRelationsResponse;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.example.dbadmin.repo.TableRelationRepository;
import com.example.dbadmin.service.SqlJoinMining.JoinPair;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表关系：从执行历史里挖候选，记下人的确认与否决。
 *
 * <p>挖掘读的是这条连接上所有人最近成功执行的 SQL，但只产出「表.列 = 表.列」的名字对和次数 ——
 * 正文不出服务端，所以普通用户也能看到团队实际在用的关联，却看不到别人写了什么 SQL。结果按连接缓存
 * 几分钟：历史每执行一次就重挖一遍没有必要，关联本来就是慢变量。</p>
 *
 * <p>确认与否决对这条连接的所有用户生效（补全排序与是否提示），每次改动都写审计。</p>
 */
@Service
public class TableRelationService {
    /** 挖多少条最近的成功执行。再多，几千条里已经出现过的关联不会因为多读一万条而变得更可信。 */
    static final int MINING_SAMPLE = 2_000;
    /** 返回给界面的候选上限：按次数排序，再往后基本是只出现过一两次的偶发写法。 */
    static final int MAX_MINED = 300;
    /** 每条连接最多记多少条判断：防止脚本误刷把表撑爆。 */
    static final int MAX_DECISIONS = 5_000;
    private static final Set<String> STATUSES = Set.of("CONFIRMED", "REJECTED");
    private static final Set<String> SOURCES = Set.of("HISTORY", "NAMING", "MANUAL");
    private static final int MAX_NAME = 256;

    private final ConnectionService connections;
    private final SqlHistoryRepository history;
    private final TableRelationRepository relations;
    private final AuditRepository audit;
    private final Cache<Long, Mined> minedCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(200)
            .build();

    public TableRelationService(ConnectionService connections, SqlHistoryRepository history,
                                TableRelationRepository relations, AuditRepository audit) {
        this.connections = connections;
        this.history = history;
        this.relations = relations;
        this.audit = audit;
    }

    private record Mined(List<MinedTableRelation> pairs, int scanned) {
    }

    public TableRelationsResponse view(long connectionId, boolean refresh) {
        connections.require(connectionId);
        if (refresh) minedCache.invalidate(connectionId);
        Mined mined = minedCache.get(connectionId, this::mine);
        List<TableRelation> decided = relations.list(connectionId);
        Set<String> decidedKeys = new HashSet<>();
        for (TableRelation relation : decided) {
            decidedKeys.add(JoinPair.of(relation.leftTable(), relation.leftColumn(), relation.rightTable(), relation.rightColumn()).key());
        }
        // 判断过的不再出现在候选里：确认的已经在「已确认」，否决的就是不想再看到。
        List<MinedTableRelation> pending = mined.pairs().stream()
                .filter(pair -> !decidedKeys.contains(JoinPair.of(pair.leftTable(), pair.leftColumn(), pair.rightTable(), pair.rightColumn()).key()))
                .toList();
        return new TableRelationsResponse(decided, pending, mined.scanned());
    }

    public TableRelationsResponse decide(long connectionId, TableRelationDecisionRequest request, String actor) {
        connections.require(connectionId);
        String status = normalizeChoice(request.status(), STATUSES, "CONFIRMED", "判断");
        String source = normalizeChoice(request.source(), SOURCES, "MANUAL", "来源");
        JoinPair pair = pairOf(request);
        boolean exists = relations.list(connectionId).stream()
                .anyMatch(item -> JoinPair.of(item.leftTable(), item.leftColumn(), item.rightTable(), item.rightColumn()).key().equals(pair.key()));
        if (!exists && relations.count(connectionId) >= MAX_DECISIONS) {
            throw new IllegalArgumentException("这条连接上的表关系已达到 " + MAX_DECISIONS + " 条上限，请先删除不再需要的记录。");
        }
        String decidedBy = actor == null || actor.isBlank() ? "admin" : actor.trim();
        relations.upsert(connectionId, pair.key(), new TableRelation(pair.leftTable(), pair.leftColumn(), pair.rightTable(), pair.rightColumn(),
                status, source, decidedBy.length() > 120 ? decidedBy.substring(0, 120) : decidedBy, null));
        audit.onConnection(actor, "TABLE_RELATION_DECIDE", connectionId, describe(pair),
                "status=" + status + " source=" + source);
        return view(connectionId, false);
    }

    public TableRelationsResponse remove(long connectionId, TableRelationDecisionRequest request, String actor) {
        connections.require(connectionId);
        JoinPair pair = pairOf(request);
        if (relations.delete(connectionId, pair.key())) {
            audit.onConnection(actor, "TABLE_RELATION_REMOVE", connectionId, describe(pair), "removed");
        }
        return view(connectionId, false);
    }

    private Mined mine(long connectionId) {
        var sources = history.recentSuccessfulSql(connectionId, MINING_SAMPLE, SqlJoinMining.MAX_SQL_CHARS);
        Map<String, MinedAccumulator> counts = new LinkedHashMap<>();
        for (var source : sources) {
            for (JoinPair pair : SqlJoinMining.extract(source.sql())) {
                counts.computeIfAbsent(pair.key(), ignored -> new MinedAccumulator(pair)).add(source.createdAt());
            }
        }
        List<MinedTableRelation> pairs = new ArrayList<>();
        counts.values().stream()
                .sorted(Comparator.comparingInt(MinedAccumulator::hits).reversed())
                .limit(MAX_MINED)
                .forEach(item -> pairs.add(item.toResponse()));
        return new Mined(List.copyOf(pairs), sources.size());
    }

    private static final class MinedAccumulator {
        private final JoinPair pair;
        private int hits;
        private Instant lastUsed;

        MinedAccumulator(JoinPair pair) {
            this.pair = pair;
        }

        void add(Instant createdAt) {
            hits++;
            if (createdAt != null && (lastUsed == null || createdAt.isAfter(lastUsed))) lastUsed = createdAt;
        }

        int hits() {
            return hits;
        }

        MinedTableRelation toResponse() {
            return new MinedTableRelation(pair.leftTable(), pair.leftColumn(), pair.rightTable(), pair.rightColumn(),
                    hits, lastUsed == null ? null : lastUsed.toString());
        }
    }

    private static JoinPair pairOf(TableRelationDecisionRequest request) {
        String leftTable = name(request.leftTable(), "左侧表名");
        String leftColumn = name(request.leftColumn(), "左侧列名");
        String rightTable = name(request.rightTable(), "右侧表名");
        String rightColumn = name(request.rightColumn(), "右侧列名");
        if (leftTable.equalsIgnoreCase(rightTable) && leftColumn.equalsIgnoreCase(rightColumn)) {
            throw new IllegalArgumentException("关联的两侧不能是同一张表的同一列。");
        }
        return JoinPair.of(leftTable, leftColumn, rightTable, rightColumn);
    }

    private static String name(String value, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(label + "不能为空。");
        if (trimmed.length() > MAX_NAME) throw new IllegalArgumentException(label + "过长。");
        if (trimmed.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch == '.' || ch == '=')) {
            throw new IllegalArgumentException(label + "只填名字本身，不带 Schema 前缀、空格或等号。");
        }
        return trimmed;
    }

    private static String normalizeChoice(String value, Set<String> allowed, String fallback, String label) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("不支持的" + label + "：" + value);
        return normalized;
    }

    private static String describe(JoinPair pair) {
        return pair.leftTable() + "." + pair.leftColumn() + " = " + pair.rightTable() + "." + pair.rightColumn();
    }
}
