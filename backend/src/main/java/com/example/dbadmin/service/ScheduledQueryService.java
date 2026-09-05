package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.ScheduledQueryRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.ScheduledQuery;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.ScheduledQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 定时导出：按 cron 跑一条查询，把结果写成文件。
 *
 * <p>调度这件事此前只服务备份一件事，而 {@code BackupScheduler} 的 cron、时区、下次执行预览
 * 是通用的。这里复用同一套判定，执行则交给现成的导出管线 —— 单条查询校验、只读作用域、
 * 行数与字节上限、审计与历史都不必重写。</p>
 *
 * <p><b>只导出，不执行写操作。</b>定时跑任意 SQL 是另一件事，也是另一种风险：没人盯着的
 * 写操作出错时，等发现已经晚了。</p>
 *
 * <p>生产连接的确认在**创建任务时**完成（用户输入连接名），运行时凭那次确认放行 —— 定时任务
 * 没有交互确认的机会，而完全跳过确认等于给生产库开了一个无人值守的出口。</p>
 */
@Service
public class ScheduledQueryService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryService.class);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    public static final String ACTION_RUN = "SCHEDULED_QUERY_RUN";

    private final ScheduledQueryRepository repository;
    private final ConnectionService connections;
    private final ExportService exports;
    private final AuditRepository audit;
    private final AppProperties properties;

    public ScheduledQueryService(
            ScheduledQueryRepository repository,
            ConnectionService connections,
            ExportService exports,
            AuditRepository audit,
            AppProperties properties
    ) {
        this.repository = repository;
        this.connections = connections;
        this.exports = exports;
        this.audit = audit;
        this.properties = properties;
    }

    public List<ScheduledQuery> list(Long connectionId) {
        return connectionId == null ? repository.findAll() : repository.findByConnectionId(connectionId);
    }

    public ScheduledQuery require(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("定时导出任务不存在：" + id));
    }

    public ScheduledQuery create(ScheduledQueryRequest request, String actor) {
        ScheduledQuery task = normalize(0, request);
        long id = repository.insert(task);
        audit.onConnection(actor, "SCHEDULED_QUERY_CREATE", task.connectionId(),
                "name=" + task.name() + " cron=" + task.cron() + " format=" + task.exportFormat());
        return require(id);
    }

    public ScheduledQuery update(long id, ScheduledQueryRequest request, String actor) {
        ScheduledQuery existing = require(id);
        ScheduledQuery task = normalize(id, request);
        if (task.connectionId() != existing.connectionId()) {
            throw new IllegalArgumentException("不能把定时导出任务改到另一条连接上，请新建一个。");
        }
        repository.update(task);
        audit.onConnection(actor, "SCHEDULED_QUERY_UPDATE", task.connectionId(),
                "name=" + task.name() + " cron=" + task.cron() + " enabled=" + task.enabled());
        return require(id);
    }

    public ScheduledQuery setEnabled(long id, boolean enabled, String actor) {
        ScheduledQuery task = require(id);
        repository.updateEnabled(id, enabled);
        audit.onConnection(actor, "SCHEDULED_QUERY_UPDATE", task.connectionId(),
                "name=" + task.name() + " enabled=" + enabled);
        return require(id);
    }

    public void delete(long id, String actor) {
        ScheduledQuery task = require(id);
        repository.delete(id);
        audit.onConnection(actor, "SCHEDULED_QUERY_DELETE", task.connectionId(), "name=" + task.name());
    }

    /**
     * 跑一次并把结果写成文件。
     *
     * <p>失败不抛给调度线程：一条任务写不出去不该让这一轮的其他任务跟着停，结果记在任务上，
     * 界面看得见。手动触发时同样走这条路 —— 两条路给出的结果必须是同一个样子。</p>
     */
    public ScheduledQuery run(long id, String actor) {
        ScheduledQuery task = require(id);
        Instant startedAt = Instant.now();
        try {
            DbConnection connection = connections.require(task.connectionId());
            String confirmation = "prod".equalsIgnoreCase(connection.environment()) && task.productionConfirmed()
                    ? connection.name() : null;
            Path directory = Path.of(properties.getScheduledQuery().getDirectory());
            Files.createDirectories(directory);
            Path file = directory.resolve(fileName(task, startedAt));
            ExportService.PreparedExport prepared = exports.prepare(
                    task.connectionId(), task.sql(), task.exportFormat(), actor, confirmation, null);
            try (OutputStream output = Files.newOutputStream(file)) {
                prepared.writeTo(output);
            }
            pruneOldFiles(directory, task);
            String message = prepared.truncated()
                    ? "导出完成，但已达到行数上限，文件是截断的。" : "导出完成。";
            repository.recordRun(id, startedAt, "SUCCESS", message, file.toString());
            audit.onConnection(actor, ACTION_RUN, task.connectionId(),
                    "name=" + task.name() + " status=SUCCESS file=" + file.getFileName());
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            repository.recordRun(id, startedAt, "FAILED", message, null);
            audit.onConnection(actor, ACTION_RUN, task.connectionId(),
                    "name=" + task.name() + " status=FAILED");
            log.warn("定时导出任务 {} 执行失败：{}", id, message);
        }
        return require(id);
    }

    /** 下一次执行时间，给界面用。cron 解析不了时返回 null —— 保存时已经拦过一次了。 */
    public Instant nextRunAt(ScheduledQuery task) {
        if (!task.enabled() || task.cron() == null || task.cron().isBlank()) return null;
        try {
            ZonedDateTime next = CronExpression.parse(task.cron())
                    .next(ZonedDateTime.ofInstant(Instant.now(), task.scheduleZoneId()));
            return next == null ? null : next.toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private ScheduledQuery normalize(long id, ScheduledQueryRequest request) {
        String name = require(request.name(), "任务名不能为空。");
        // 保存时就把「必须是单条查询」判掉：一条写操作留到半夜由调度线程发现，代价是白等
        // 一晚上加一条谁也没看见的失败记录。
        String sql = exports.requireSingleQuery(require(request.sql(), "SQL 不能为空。"));
        String cron = require(request.cron(), "cron 表达式不能为空。");
        if (!CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("cron 表达式无法解析：" + cron);
        }
        String format = request.exportFormat() == null ? "csv" : request.exportFormat().trim().toLowerCase(Locale.ROOT);
        if (!List.of("csv", "json", "sql", "xml", "markdown", "xlsx").contains(format)) {
            throw new IllegalArgumentException("不支持的导出格式：" + request.exportFormat());
        }
        if (request.scheduleZone() != null && !request.scheduleZone().isBlank()) {
            try {
                ZoneId.of(request.scheduleZone().trim());
            } catch (Exception ignored) {
                throw new IllegalArgumentException("无法识别的时区：" + request.scheduleZone());
            }
        }
        DbConnection connection = connections.require(request.connectionId());
        // 生产连接必须在创建时确认一次：定时任务没有交互确认的机会，跳过确认等于给生产库
        // 开了一个无人值守的出口。
        boolean production = "prod".equalsIgnoreCase(connection.environment());
        if (production && !connection.name().equals(request.productionConfirmation())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "PRODUCTION_CONFIRMATION_REQUIRED",
                    "这是生产连接，请输入连接名以确认创建定时导出任务。",
                    java.util.Map.of("confirmationText", connection.name()));
        }
        return new ScheduledQuery(id, request.connectionId(), name, sql, format, cron.trim(),
                request.scheduleZone(), request.enabled(), production, null, null, null, null, null, null);
    }

    /** 产物文件名：任务名 + 时间戳。任务名里的路径分隔符要去掉，否则会写到别的目录去。 */
    static String fileName(ScheduledQuery task, Instant at) {
        String safe = task.name().replaceAll("[\\\\/:*?\"<>|\\x00]", "_");
        if (safe.length() > 80) safe = safe.substring(0, 80);
        String stamp = FILE_STAMP.format(ZonedDateTime.ofInstant(at, task.scheduleZoneId()));
        return safe + "-" + stamp + "." + ("markdown".equals(task.exportFormat()) ? "md" : task.exportFormat());
    }

    /**
     * 只保留最近若干个产物。
     *
     * <p>一个每小时跑一次的任务一年会留下八千多个文件 —— 没有人会去清理它们，磁盘却是会满的。
     * 清理失败只记日志：导出本身已经成功了，不该因为删旧文件失败而报成失败。</p>
     */
    private void pruneOldFiles(Path directory, ScheduledQuery task) {
        int keep = Math.max(1, properties.getScheduledQuery().getKeepFiles());
        String prefix = task.name().replaceAll("[\\\\/:*?\"<>|\\x00]", "_");
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> mine = files
                    .filter(path -> path.getFileName().toString().startsWith(prefix + "-"))
                    .sorted(Comparator.comparing((Path path) -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant();
                        } catch (Exception ignored) {
                            return Instant.EPOCH;
                        }
                    }).reversed())
                    .toList();
            for (int index = keep; index < mine.size(); index++) Files.deleteIfExists(mine.get(index));
        } catch (Exception error) {
            log.debug("清理定时导出旧文件失败：{}", error.toString());
        }
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
