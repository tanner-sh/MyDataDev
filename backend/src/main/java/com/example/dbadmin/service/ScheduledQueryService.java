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
import com.example.dbadmin.model.ScheduledQueryRun;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Statement;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.*;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
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
    private final BackgroundTaskControl backgroundControl;
    private final Map<Long, RunControl> running = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "scheduled-export");
                thread.setDaemon(true);
                return thread;
            });

    public ScheduledQueryService(
            ScheduledQueryRepository repository,
            ConnectionService connections,
            ExportService exports,
            AuditRepository audit,
            AppProperties properties, BackgroundTaskControl backgroundControl
    ) {
        this.repository = repository;
        this.connections = connections;
        this.exports = exports;
        this.audit = audit;
        this.properties = properties;
        this.backgroundControl = backgroundControl;
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

    public synchronized ScheduledQuery update(long id, ScheduledQueryRequest request, String actor) {
        requireIdle(id);
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

    public synchronized void delete(long id, String actor) {
        ScheduledQuery task = require(id);
        requireIdle(id);
        Path directory = taskDirectory(id);
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            } catch (Exception error) { throw new IllegalStateException("删除导出文件失败，请检查目录权限后重试。", error); }
        }
        repository.delete(id);
        audit.onConnection(actor, "SCHEDULED_QUERY_DELETE", task.connectionId(), "name=" + task.name());
    }

    @PostConstruct
    public void recover() {
        for (ScheduledQueryRun run : repository.activeRuns(null)) {
            Path directory = taskDirectory(run.taskId());
            Path file = run.filePath() == null ? null : Path.of(run.filePath()).toAbsolutePath().normalize();
            // 发布前先登记产物；进程在 rename 和完成记录之间退出时，可由完整文件恢复结果。
            if (file != null && directory.equals(file.getParent()) && !Files.isSymbolicLink(directory)
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                repository.completeRun(run.id(), run.taskId(), run.startedAt(), "SUCCESS", run.message(),
                        run.fileName(), run.filePath(), run.fileSize());
            }
        }
        repository.recoverInterruptedRuns();
        for (ScheduledQuery task : repository.findAll()) {
            Path directory = taskDirectory(task.id());
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".part")).toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (Exception error) { log.warn("清理中断导出临时文件失败 task={}", task.id(), error); }
        }
    }

    @PreDestroy
    public void shutdown() {
        running.values().forEach(RunControl::cancel);
        executor.shutdown(); // 已排队的 worker 也要走 finally，记录取消并释放连接名额。
        try { executor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    public ScheduledQuery submit(long id, String actor) {
        RunControl control = admit(id);
        try { executor.execute(() -> execute(control, actor)); }
        catch (RejectedExecutionException full) {
            try { finish(control, "FAILED", "后台导出队列已满，请稍后重试。", null, 0); }
            finally { release(control); }
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "EXPORT_QUEUE_FULL", "后台导出队列已满，请稍后重试。");
        }
        return require(id);
    }

    /** 同步入口用于集成验证；与手动和定时任务共用准入、产物与完成逻辑。 */
    public ScheduledQuery run(long id, String actor) {
        RunControl control = admit(id);
        execute(control, actor);
        return require(id);
    }

    private synchronized RunControl admit(long id) {
        ScheduledQuery task = require(id);
        if (running.containsKey(id)) throw new ApiProblemException(HttpStatus.CONFLICT,
                "EXPORT_ALREADY_RUNNING", "该导出任务正在运行，请等待结束或先取消。");
        RunControl control = new RunControl(task);
        if (!backgroundControl.tryAcquire(task.connectionId(), control.id)) throw new ApiProblemException(HttpStatus.CONFLICT,
                "EXPORT_CONNECTION_BUSY", "该连接已有后台任务，请等待结束后重试。");
        try {
            repository.beginRun(control.id, task, control.startedAt);
            running.put(id, control);
        } catch (RuntimeException failure) {
            backgroundControl.releaseCompleted(task.connectionId(), control.id);
            throw failure;
        }
        return control;
    }

    public void cancel(long id) {
        require(id);
        RunControl control = running.get(id);
        if (control == null) return;
        control.cancel();
        repository.progressRun(control.id, "CANCELLING", "正在取消，等待数据库停止并清理临时文件");
    }

    public List<ScheduledQueryRun> history(long id, int limit) { require(id); return repository.runs(id, limit); }
    public List<ScheduledQueryRun> active(Long connectionId) { return repository.activeRuns(connectionId); }

    public ScheduledQueryRun requireRun(long taskId, String runId) {
        require(taskId);
        return repository.run(runId).filter(run -> run.taskId() == taskId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "EXPORT_RUN_NOT_FOUND", "导出记录不存在。"));
    }

    public Path download(long taskId, String runId, String actor) throws Exception {
        ScheduledQueryRun run = requireRun(taskId, runId);
        if (!run.downloadable()) throw new ApiProblemException(HttpStatus.NOT_FOUND, "EXPORT_FILE_EXPIRED", "文件已按保留策略清理，或本次导出未完成，请重新运行。");
        Path file = Path.of(run.filePath()).toAbsolutePath().normalize();
        Path directory = taskDirectory(taskId);
        if (!file.getParent().equals(directory) || Files.isSymbolicLink(directory)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "EXPORT_FILE_EXPIRED", "导出文件已不存在，请重新运行。");
        }
        audit.onConnection(actor, "SQL_EXPORT_DOWNLOAD", run.connectionId(), "scheduled-run=" + runId);
        return file;
    }

    private void execute(RunControl control, String actor) {
        ScheduledQuery task = control.task;
        Path partial = null;
        ExportService.PreparedExport prepared = null;
        control.worker = Thread.currentThread();
        try {
            control.check();
            repository.progressRun(control.id, "RUNNING", "正在查询并生成文件");
            repository.recordRun(task.id(), control.startedAt, "RUNNING", "正在查询并生成文件", null);
            DbConnection connection = connections.require(task.connectionId());
            String confirmation = "prod".equalsIgnoreCase(connection.environment()) && task.productionConfirmed() ? connection.name() : null;
            Path directory = taskDirectory(task.id());
            if (Files.isSymbolicLink(directory)) throw new IllegalStateException("导出目录不能是符号链接。");
            Files.createDirectories(directory);
            Path file = directory.resolve(control.id + "-" + fileName(task, control.startedAt));
            partial = directory.resolve(control.id + ".part");
            prepared = exports.prepareCancellable(task.connectionId(), task.sql(), task.exportFormat(), actor, confirmation,
                    statement -> { control.statement = statement; control.check(); });
            control.statement = null;
            control.check();
            try (OutputStream output = Files.newOutputStream(partial, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                prepared.writeTo(output);
            }
            String completedMessage = prepared.truncated() ? "导出完成，已达到行数上限，文件包含部分结果。" : "导出完成。";
            long size = Files.size(partial);
            repository.prepareRunFile(control.id, completedMessage, file.getFileName().toString(), file.toString(), size);
            // 取消与正式发布互斥：一旦进入发布，完成状态就是成功，不能再报成已取消。
            synchronized (control) {
                control.check();
                try { Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(partial, file); }
                control.published = true;
            }
            finish(control, "SUCCESS", completedMessage, file, size);
            pruneOldFiles(directory, task);
            auditRun(actor, control, "SUCCESS");
        } catch (Exception error) {
            if (control.published) {
                // 完整文件已发布，不能抹掉文件记录或宣称未生效；重启恢复会补齐完成状态。
                log.error("导出文件已发布，但完成记录失败，重启后将恢复 run={}", control.id, error);
                return;
            }
            boolean cancelled = control.cancelled || error instanceof CancellationException || Thread.currentThread().isInterrupted();
            // JDBC 取消可能设置中断位；先清除，确保元数据库的完成记录能够写入。
            Thread.interrupted();
            String status = cancelled ? "CANCELLED" : "FAILED";
            String message = cancelled ? "导出已取消，未发布文件。" : (error.getMessage() == null ? "导出失败，请检查数据库与存储空间。" : error.getMessage());
            finish(control, status, message, null, 0);
            auditRun(actor, control, status);
            if (!cancelled) log.warn("定时导出失败 run={}", control.id, error);
        } finally {
            if (prepared != null) prepared.discard();
            if (partial != null) try { Files.deleteIfExists(partial); } catch (Exception error) { log.warn("清理导出暂存文件失败", error); }
            release(control);
        }
    }

    private void finish(RunControl control, String status, String message, Path file, long size) {
        repository.completeRun(control.id, control.task.id(), control.startedAt, status, message,
                file == null ? null : file.getFileName().toString(), file == null ? null : file.toString(), size);
    }

    private void auditRun(String actor, RunControl control, String status) {
        try { audit.onConnection(actor, ACTION_RUN, control.task.connectionId(), "run=" + control.id + " status=" + status); }
        catch (RuntimeException error) { log.error("导出运行审计记录失败 run={}", control.id, error); }
    }

    private void release(RunControl control) {
        running.remove(control.task.id(), control);
        backgroundControl.releaseCompleted(control.task.connectionId(), control.id);
    }

    Path taskDirectory(long id) {
        return Path.of(properties.getScheduledQuery().getDirectory()).toAbsolutePath().normalize().resolve("task-" + id);
    }

    private static final class RunControl {
        final String id = UUID.randomUUID().toString();
        final ScheduledQuery task;
        final Instant startedAt = Instant.now();
        volatile boolean cancelled;
        volatile boolean published;
        volatile Statement statement;
        volatile Thread worker;
        RunControl(ScheduledQuery task) { this.task = task; }
        void check() { if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("导出已取消"); }
        synchronized void cancel() {
            if (published) return;
            cancelled = true;
            Statement current = statement;
            if (current != null) try { current.cancel(); } catch (Exception ignored) { /* 超时与 worker 的检查仍会收尾 */ }
            if (worker != null) worker.interrupt();
        }
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
        // 文件系统通常按 UTF-8 字节限制文件名，不能只截取 80 个中文字符。
        StringBuilder bounded = new StringBuilder();
        int bytes = 0;
        for (int codePoint : safe.codePoints().toArray()) {
            String character = new String(Character.toChars(codePoint));
            int length = character.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + length > 160) break;
            bounded.append(character);
            bytes += length;
        }
        safe = bounded.toString();
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
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> mine = files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !path.getFileName().toString().endsWith(".part"))
                    .sorted(Comparator.comparing((Path path) -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant();
                        } catch (Exception ignored) {
                            return Instant.EPOCH;
                        }
                    }).reversed())
                    .toList();
            for (int index = keep; index < mine.size(); index++) {
                Path old = mine.get(index);
                Files.deleteIfExists(old);
                repository.expireFile(old.toString());
            }
        } catch (Exception error) {
            log.debug("清理定时导出旧文件失败：{}", error.toString());
        }
    }

    private void requireIdle(long id) {
        if (running.containsKey(id)) throw new ApiProblemException(HttpStatus.CONFLICT, "EXPORT_ALREADY_RUNNING", "任务正在运行，请先取消或等待结束后再修改。");
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
