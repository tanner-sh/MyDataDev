package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionPage;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.SqlFileExecution;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Service
public class SqlFileExecutionService {

    private static final int ERROR_SQL_PREVIEW = 2_000;

    private final SqlFileExecutionRepository jobs;

    private final ConnectionService connections;

    private final ExecutionGuard guard;

    private final SqlStatementClassifier classifier;

    private final SqlScriptSplitter scriptSplitter;

    private final DialectRegistry dialects;

    private final MetadataService metadata;

    private final AuditRepository audit;

    private final AppProperties properties;

    private final SqlFileExecutionCoordinator coordinator;

    private final BackgroundTaskControl taskControl;

    private final LargeFileUploadGuard uploadGuard;
    private final SqlFileJobExecutor executor;

    final Map<Long, Statement> runningStatements = new ConcurrentHashMap<>();

    final Map<Long, FileFingerprint> analyzedSources = new ConcurrentHashMap<>();

    /** 由调用方代为执行唯一那条语句的命令任务（后台调用例程）。只在内存里：参数值不落盘，重启后任务本来就会被判失败。 */
    final Map<Long, CommandUnitRunner> commandRunners = new ConcurrentHashMap<>();

    public SqlFileExecutionService(SqlFileExecutionRepository jobs, ConnectionService connections, ExecutionGuard guard,
                                   SqlStatementClassifier classifier, SqlScriptSplitter scriptSplitter, DialectRegistry dialects, MetadataService metadata,
                                   AuditRepository audit, AppProperties properties, SqlFileExecutionCoordinator coordinator,
                                   BackgroundTaskControl taskControl, LargeFileUploadGuard uploadGuard) {
        this.jobs = jobs;
        this.connections = connections;
        this.guard = guard;
        this.classifier = classifier;
        this.scriptSplitter = scriptSplitter;
        this.dialects = dialects;
        this.metadata = metadata;
        this.audit = audit;
        this.properties = properties;
        this.coordinator = coordinator;
        this.taskControl = taskControl;
        this.uploadGuard = uploadGuard;
        this.executor = new SqlFileJobExecutor(this, jobs, connections, dialects, metadata, audit, properties, classifier);
    }

    private java.util.function.IntSupplier scriptConcurrencyLimit = () -> 1;

    /** 命令窗口任务的设置与日志；为空时（部分单测）没有命令窗口。 */
    com.example.dbadmin.repo.SqlCommandRepository commands;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCommandRepository(com.example.dbadmin.repo.SqlCommandRepository commands) {
        this.commands = commands;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setMcpConfiguration(com.example.dbadmin.mcp.McpConfigurationService configuration) {
        scriptConcurrencyLimit = () -> configuration.snapshot().settings().maxScriptsPerConnection();
    }

    @PostConstruct
    public void recoverInterruptedJobs() {
        var interrupted = jobs.findActive();
        jobs.failStaleRunning();
        interrupted.forEach(this::deleteFileQuietly);
    }

    public SqlFileExecutionResponse upload(long connectionId, String rawFileName, long contentLength, InputStream input, String actor) throws Exception {
        return upload(connectionId, rawFileName, contentLength, input, actor, SqlScriptDelimiter.AUTO);
    }

    /**
     * @param delimiter 用户选的分隔方式。选「自动」时先看文件里有没有独占行的分隔符（DBeaver 的 !）：有就按它分段，
     *                  并把分隔方式写进任务 —— 开始执行之前，界面上能看到「按独占行 ! 分段、共 N 条」。
     */
    public SqlFileExecutionResponse upload(long connectionId, String rawFileName, long contentLength, InputStream input, String actor,
                                           SqlScriptDelimiter delimiter) throws Exception {
        DbConnection connection = connections.require(connectionId);
        String fileName = safeFileName(rawFileName);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".sql")) throw new IllegalArgumentException("只支持 .sql 文件。");
        long maximum = properties.getSqlFile().getMaxUploadBytes();
        if (contentLength == 0) throw new IllegalArgumentException("SQL 文件不能为空。");
        if (contentLength > maximum) throw new IllegalArgumentException("SQL 文件超过允许大小。");

        Path root = root();
        Files.createDirectories(root);
        requireFreeSpace(root, contentLength);
        Path target = root.resolve("sql-" + UUID.randomUUID() + ".sql").normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("SQL 文件路径不安全。");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        if (!uploadGuard.tryAcquire()) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_BUSY", "当前大文件上传数量已达上限，请稍后重试。");
        }
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                size += read;
                if (size > maximum) throw new IllegalArgumentException("SQL 文件超过允许大小。");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (Exception error) {
            Files.deleteIfExists(target);
            throw error;
        } finally {
            uploadGuard.release();
        }
        if (size == 0) {
            Files.deleteIfExists(target);
            throw new IllegalArgumentException("SQL 文件不能为空。");
        }

        SqlScriptDelimiter resolved = resolveDelimiter(target, connection.dbType(), delimiter);
        return register(connection, fileName, target, size, HexFormat.of().formatHex(digest.digest()),
                actor, "SQL_FILE_UPLOAD", fileName + "; size=" + size + delimiterAuditSuffix(resolved), false, resolved, null);
    }

    /**
     * 把编辑器里的脚本交给后台执行（工作台里语句太多时的去处）。与上传文件同一条管线：落盘、分析、
     * 等用户在任务面板里点开始；生产确认、无 WHERE 写确认、并发闸门都在开始那一步。
     */
    public SqlFileExecutionResponse submitEditorScript(long connectionId, String sql, String actor, SqlScriptDelimiter delimiter) throws Exception {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("请输入要执行的 SQL");
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        return uploadScript(connectionId, "workbench-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".sql",
                bytes.length, writer -> { writer.write(sql); return "source=workbench"; }, actor, "SQL_FILE_UPLOAD", "editor script",
                false, delimiter == null ? SqlScriptDelimiter.AUTO : delimiter, true);
    }

    /**
     * 命令窗口：编辑器里的脚本交给后台逐条执行，结果是一条滚动日志。与 {@link #submitScript} 一样落盘、
     * 当场分析、立即开始 —— 生产确认在这一步就要带上；分析失败或开始失败时任务不会留在等待确认的状态。
     *
     * @param onError        STOP（遇错停止）或 CONTINUE（遇错继续）；脚本里的 WHENEVER SQLERROR 会在执行中途改它
     * @param transactionMode AUTOCOMMIT（逐条提交）或 SCRIPT（Oracle 系，按脚本里的 COMMIT/ROLLBACK）
     */
    public SqlFileExecutionResponse submitCommand(long connectionId, String sql, String actor, SqlScriptDelimiter delimiter,
                                                  String onError, String transactionMode, String endOfFileAction,
                                                  String productionConfirmation) throws Exception {
        if (commands == null) throw new IllegalStateException("命令窗口不可用。");
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("请输入要执行的 SQL");
        String policy = onError == null ? "STOP" : onError.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("STOP", "CONTINUE").contains(policy)) throw new IllegalArgumentException("不支持的遇错策略：" + onError);
        String mode = transactionMode == null ? "AUTOCOMMIT" : transactionMode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTOCOMMIT", "SCRIPT").contains(mode)) throw new IllegalArgumentException("命令窗口的事务方式只能是逐条提交或脚本控制。");
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        var job = uploadScript(connectionId, "command-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".sql",
                bytes.length, writer -> { writer.write(sql); return "source=command-window; onError=" + policy; }, actor, "SQL_FILE_UPLOAD", "command window",
                true, delimiter == null ? SqlScriptDelimiter.AUTO : delimiter, false, id -> commands.insert(id, policy));
        if (!"READY".equals(job.status())) return job;
        try {
            return start(job.id(), productionConfirmation, actor, mode, "SCRIPT".equals(mode) ? endOfFileAction : "COMMIT");
        } catch (Exception error) {
            SqlFileExecution draft = require(job.id());
            if ("READY".equals(draft.status())) {
                jobs.markTerminal(draft.id(), "FAILED", "NOT_STARTED", 0, 0, 0, null, null,
                        "命令未开始执行：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                deleteFileQuietly(draft);
            }
            throw error;
        }
    }

    /** 命令窗口的一次轮询：任务状态、失败计数，加上 afterSeq 之后的日志。 */
    public CommandView commandView(long id, long afterSeq, int limit) {
        if (commands == null) throw new IllegalStateException("命令窗口不可用。");
        var command = commands.find(id).orElseThrow(() -> new IllegalArgumentException("不是命令窗口任务：" + id));
        int size = Math.min(Math.max(limit, 1), 1000);
        var entries = commands.logs(id, Math.max(afterSeq, 0), size + 1);
        boolean hasMore = entries.size() > size;
        if (hasMore) entries = entries.subList(0, size);
        return new CommandView(response(require(id)), command, List.copyOf(entries), hasMore);
    }

    public record CommandView(SqlFileExecutionResponse job, com.example.dbadmin.repo.SqlCommandRepository.CommandJob command,
                              List<com.example.dbadmin.repo.SqlCommandRepository.LogEntry> entries, boolean hasMore) { }

    /**
     * 命令任务里那一条语句由调用方自己执行：例程调用要绑定参数、取 OUT 参数，这些脚本文本表达不了。
     * 其余 —— 落盘、分析、生产确认、排队、每连接并发闸门、超时、取消、日志 —— 与命令窗口同一条路。
     */
    @FunctionalInterface
    public interface CommandUnitRunner {
        CommandUnitResult run(Connection connection, CommandUnitContext context) throws Exception;
    }

    public interface CommandUnitContext {
        /** 登记正在执行的语句：任务被取消时 cancel 它。 */
        void register(Statement statement);

        /** 后台任务的语句超时（{@code app.sql-file.statement-timeout-seconds}）。 */
        int timeoutSeconds();

        /** 用户是否已请求取消 —— 驱动报「取消」和「超时」常是同一个错误，靠这个区分。 */
        boolean cancelRequested();
    }

    /** @param text 写进日志 RESULT 行的文字；@param output 例程打印的消息，逐条写成 OUTPUT 行 */
    public record CommandUnitResult(String text, List<String> output, long queryRows) {
        public CommandUnitResult {
            output = output == null ? List.of() : List.copyOf(output);
        }
    }

    /**
     * 提交一个只有一条语句、由 {@code runner} 执行的命令任务（逐条自动提交、遇错停止）。
     * {@code displaySql} 是落盘、写进日志的那句，不该带任何参数值。
     */
    public SqlFileExecutionResponse submitRoutineCommand(long connectionId, String displaySql, String actor,
                                                         String productionConfirmation, CommandUnitRunner runner) throws Exception {
        if (commands == null) throw new IllegalStateException("命令窗口不可用。");
        byte[] bytes = displaySql.getBytes(StandardCharsets.UTF_8);
        long[] registered = {-1};
        SqlFileExecutionResponse job;
        try {
            job = uploadScript(connectionId, "routine-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".sql",
                    bytes.length, writer -> { writer.write(displaySql); return "source=routine-invoke"; }, actor, "SQL_FILE_UPLOAD", "routine invoke",
                    true, SqlScriptDelimiter.AUTO, false, id -> {
                        registered[0] = id;
                        commands.insert(id, "STOP");
                        commandRunners.put(id, runner);
                    });
        } catch (Exception error) {
            if (registered[0] >= 0) commandRunners.remove(registered[0]);
            throw error;
        }
        if (!"READY".equals(job.status())) {
            commandRunners.remove(job.id());
            return job;
        }
        try {
            // runner 只会被调用一次：调用语句被拆成了不止一条，说明生成的那句有问题，宁可不跑。
            if (job.statementTotal() != 1) throw new IllegalStateException("后台调用语句解析异常（" + job.statementTotal() + " 条），未执行。");
            return start(job.id(), productionConfirmation, actor, "AUTOCOMMIT", "COMMIT");
        } catch (Exception error) {
            commandRunners.remove(job.id());
            SqlFileExecution draft = require(job.id());
            if ("READY".equals(draft.status())) {
                jobs.markTerminal(draft.id(), "FAILED", "NOT_STARTED", 0, 0, 0, null, null,
                        "调用未开始执行：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                deleteFileQuietly(draft);
            }
            throw error;
        }
    }

    boolean isCommand(long id) {
        return commands != null && commands.isCommand(id);
    }

    /** 命令窗口任务在分析、开始前校验时也要允许 WHENEVER SQLERROR，否则还没执行就被当成不支持的指令拒掉。 */
    private SqlScriptParser.DirectiveListener directivesFor(SqlFileExecution job) {
        return isCommand(job.id()) ? ignored -> { } : null;
    }

    /** 自动模式下识别独占行分隔符；识别不出或用户已经指定了方式时原样返回。只读文件开头，符号都是 ASCII，编码不影响判断。 */
    private SqlScriptDelimiter resolveDelimiter(Path file, String dbType, SqlScriptDelimiter requested) throws IOException {
        if (requested != null && !requested.isAuto()) return requested;
        try (var reader = new java.io.InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            return SqlScriptDelimiter.detect(reader, dbType).map(SqlScriptDelimiter::custom).orElse(SqlScriptDelimiter.AUTO);
        }
    }

    private static String delimiterAuditSuffix(SqlScriptDelimiter delimiter) {
        return delimiter.isAuto() ? "" : "; delimiter=" + delimiter.encode();
    }

    static SqlScriptDelimiter delimiterOf(SqlFileExecution job) {
        return SqlScriptDelimiter.decode(job.statementDelimiter());
    }

    /**
     * 由服务端生成脚本内容的回调；写入的字符会直接落到最终的脚本文件里。
     *
     * <p>返回值会拼进审计明细 —— 生成过程里才知道的事实（比如一份 CSV 实际转换了多少行）
     * 只有在这里才拿得到。</p>
     */
    public interface ScriptWriter {
        String write(Writer out) throws Exception;
    }

    /**
     * 把服务端生成的 SQL 脚本注册成一个待执行任务。
     *
     * <p>与 {@link #upload} 的区别只在于内容来源：不是客户端上传的字节流，而是调用方现场写出的
     * 文本（例如 CSV 转成的批量 INSERT）。落盘、摘要、分析、排队全部复用同一套逻辑，因此生成的
     * 导入任务天然具备进度、取消与并发闸门。</p>
     *
     * @param estimatedBytes 预计写出的字节数，用于落盘前的剩余空间检查；估不出来传 0。生成的
     *                       脚本通常比源文件大（列名与 INSERT 头按批重复），调用方应把这部分
     *                       膨胀算进去。
     */
    public SqlFileExecutionResponse uploadScript(long connectionId, String rawFileName, long estimatedBytes,
                                                 ScriptWriter writer, String actor, String auditAction,
                                                 String auditDetailPrefix) throws Exception {
        return uploadScript(connectionId, rawFileName, estimatedBytes, writer, actor, auditAction, auditDetailPrefix, false);
    }

    private SqlFileExecutionResponse uploadScript(long connectionId, String rawFileName, long estimatedBytes,
                                                 ScriptWriter writer, String actor, String auditAction,
                                                 String auditDetailPrefix, boolean inlineAnalysis) throws Exception {
        // 服务端自己生成的脚本（CSV/Excel 导入、跨库传输）不做分隔符识别：内容是我们写的，就是默认规则。
        return uploadScript(connectionId, rawFileName, estimatedBytes, writer, actor, auditAction, auditDetailPrefix,
                inlineAnalysis, SqlScriptDelimiter.AUTO, false);
    }

    private SqlFileExecutionResponse uploadScript(long connectionId, String rawFileName, long estimatedBytes,
                                                 ScriptWriter writer, String actor, String auditAction,
                                                 String auditDetailPrefix, boolean inlineAnalysis,
                                                 SqlScriptDelimiter delimiter, boolean detectDelimiter) throws Exception {
        return uploadScript(connectionId, rawFileName, estimatedBytes, writer, actor, auditAction, auditDetailPrefix,
                inlineAnalysis, delimiter, detectDelimiter, null);
    }

    private SqlFileExecutionResponse uploadScript(long connectionId, String rawFileName, long estimatedBytes,
                                                 ScriptWriter writer, String actor, String auditAction,
                                                 String auditDetailPrefix, boolean inlineAnalysis,
                                                 SqlScriptDelimiter delimiter, boolean detectDelimiter,
                                                 java.util.function.LongConsumer onRegistered) throws Exception {
        String auditDetail = auditDetailPrefix;
        DbConnection connection = connections.require(connectionId);
        String fileName = safeFileName(rawFileName);
        long maximum = properties.getSqlFile().getMaxUploadBytes();
        Path root = root();
        Files.createDirectories(root);
        // 生成脚本同样是「一边收请求体一边往磁盘写几个 GB」，磁盘与并发这两道闸门必须和
        // 直传 SQL 文件一视同仁：少了它们，max-concurrent-uploads 对 CSV 导入形同虚设。
        requireFreeSpace(root, estimatedBytes);
        Path target = root.resolve("sql-" + UUID.randomUUID() + ".sql").normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("SQL 文件路径不安全。");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size;
        if (!uploadGuard.tryAcquire()) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_BUSY", "当前大文件上传数量已达上限，请稍后重试。");
        }
        try (OutputStream file = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
             BoundedOutputStream bounded = new BoundedOutputStream(file, maximum);
             DigestOutputStream digested = new DigestOutputStream(bounded, digest);
             Writer out = new BufferedWriter(new OutputStreamWriter(digested, StandardCharsets.UTF_8), 128 * 1024)) {
            String detail = writer.write(out);
            out.flush();
            size = bounded.written();
            if (detail != null && !detail.isBlank()) auditDetail = auditDetail + "; " + detail;
        } catch (Exception error) {
            Files.deleteIfExists(target);
            throw error;
        } finally {
            uploadGuard.release();
        }
        if (size == 0) {
            Files.deleteIfExists(target);
            throw new IllegalArgumentException("生成的 SQL 脚本为空。");
        }
        SqlScriptDelimiter resolved = detectDelimiter ? resolveDelimiter(target, connection.dbType(), delimiter) : delimiter;
        return register(connection, fileName, target, size, HexFormat.of().formatHex(digest.digest()),
                actor, auditAction, auditDetail + delimiterAuditSuffix(resolved), inlineAnalysis, resolved, onRegistered);
    }

    /**
     * 落盘前的剩余空间检查。
     *
     * <p>{@code expectedBytes} 是这次要写多少的估算，{@code <= 0} 表示估不出来（客户端没给
     * Content-Length），那就只做后面写入时的 BoundedOutputStream 兜底。额外留 100 MB 是给
     * 元数据库和日志的余量 —— 把磁盘写到一个字节不剩会连 H2 都写不动。</p>
     */
    private void requireFreeSpace(Path root, long expectedBytes) throws Exception {
        if (expectedBytes <= 0) return;
        FileStore store = Files.getFileStore(root);
        if (store.getUsableSpace() < expectedBytes + 100L * 1024 * 1024) {
            throw new IllegalStateException("服务器 SQL 文件目录剩余空间不足。");
        }
    }

    private SqlFileExecutionResponse register(DbConnection connection, String fileName, Path target, long size,
                                              String checksum, String actor, String auditAction, String auditDetail, boolean inlineAnalysis,
                                              SqlScriptDelimiter delimiter, java.util.function.LongConsumer onRegistered) throws Exception {
        Instant expiresAt = Instant.now().plus(Math.max(1, properties.getSqlFile().getReadyTtlHours()), ChronoUnit.HOURS);
        SqlFileExecution draft = new SqlFileExecution(0, connection.id(), connection.name(), connection.dbType(), fileName,
                target.toString(), size, checksum, null, "ANALYZING", "DETECTING_ENCODING",
                0, null, 0, 0, 0, 0, 0, 0, 0, null, null, "正在分析 SQL 文件。", false, false,
                false, actor, expiresAt, null, null, Instant.now());
        long id;
        try {
            id = jobs.insert(draft);
        } catch (Exception error) {
            Files.deleteIfExists(target);
            throw error;
        }
        try {
            if (delimiter != null && !delimiter.isAuto()) jobs.updateStatementDelimiter(id, delimiter.encode());
            // 分析之前就要知道它是不是命令窗口任务：命令窗口允许 WHENEVER SQLERROR CONTINUE，普通任务不允许。
            if (onRegistered != null) onRegistered.accept(id);
            analyzedSources.put(id, fingerprint(Files.readAttributes(target, BasicFileAttributes.class)));
            taskControl.clear(sqlFileOperationKey(id));
            if (inlineAnalysis) analyze(id);
            else coordinator.submit(id, () -> analyze(id));
        } catch (RejectedExecutionException error) {
            jobs.markTerminal(id, "FAILED", "QUEUE_FULL", 0, 0, 0, null, null, "SQL 文件分析队列已满，任务未启动。");
            Files.deleteIfExists(target);
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "SQL_FILE_QUEUE_FULL", "SQL 文件任务队列已满，请稍后重试。");
        }
        audit.onConnection(actor, auditAction, connection.id(), auditDetail);
        return response(require(id));
    }

    /** 统计写出字节并在超限时立刻中断，避免生成脚本把磁盘写满。 */
    private static final class BoundedOutputStream extends FilterOutputStream {
        private final long maximum;
        private long written;

        BoundedOutputStream(OutputStream delegate, long maximum) {
            super(delegate);
            this.maximum = maximum;
        }

        long written() { return written; }

        @Override
        public void write(int value) throws IOException {
            grow(1);
            out.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            grow(length);
            out.write(buffer, offset, length);
        }

        private void grow(int length) throws IOException {
            written += length;
            if (written > maximum) throw new ApiProblemException(HttpStatus.PAYLOAD_TOO_LARGE, "SQL_FILE_TOO_LARGE", "生成的 SQL 脚本超过允许大小。");
        }
    }

    public SqlFileExecutionResponse start(long id, String productionConfirmation, String actor) throws Exception {
        return start(id, productionConfirmation, actor, null, null);
    }

    public SqlFileExecutionResponse start(long id, String productionConfirmation, String actor,
                                           String transactionMode, String endOfFileAction) throws Exception {
        // Share the lifecycle monitor with update/delete/closePool until the job is queued.
        synchronized (connections) {
            return startLocked(id, productionConfirmation, actor, transactionMode, endOfFileAction);
        }
    }

    private SqlFileExecutionResponse startLocked(long id, String productionConfirmation, String actor,
                                                String transactionMode, String endOfFileAction) throws Exception {
        String mode = transactionMode == null ? "BATCH" : transactionMode.toUpperCase(Locale.ROOT);
        String ending = endOfFileAction == null ? ("SCRIPT".equals(mode) ? "ROLLBACK" : "COMMIT") : endOfFileAction.toUpperCase(Locale.ROOT);
        boolean command = isCommand(id);
        // 逐条提交只给命令窗口：普通文件任务按批提交是为了吞吐，逐条提交对它没有好处。
        if (!(Set.of("BATCH", "SCRIPT").contains(mode) || (command && "AUTOCOMMIT".equals(mode))) || !Set.of("COMMIT", "ROLLBACK").contains(ending)) {
            throw new IllegalArgumentException("不支持的事务模式或文件结束策略。");
        }
        if (!"SCRIPT".equals(mode) && !"COMMIT".equals(ending)) throw new IllegalArgumentException("分批提交模式必须提交尾批。");
        SqlFileExecution job = require(id);
        if (!"READY".equals(job.status())) throw new ApiProblemException(HttpStatus.CONFLICT, "SQL_FILE_NOT_READY", "SQL 文件尚未完成解析或已经执行。");
        if (job.expiresAt().isBefore(Instant.now())) throw new ApiProblemException(HttpStatus.CONFLICT, "SQL_FILE_EXPIRED", "SQL 文件已过期，请重新选择文件。");
        DbConnection connection = connections.require(job.connectionId());
        if (!connection.dbType().equalsIgnoreCase(job.targetDbType())) throw new IllegalStateException("目标连接类型已变化，请重新选择文件。");
        if ("SCRIPT".equals(mode) && !SqlScriptSyntax.scriptTransactionsSupported(job.targetDbType())) {
            throw new IllegalArgumentException("脚本事务模式目前支持 Oracle 和 OceanBase Oracle 连接。");
        }
        if ("BATCH".equals(mode) && job.transaction().controlCount() > 0) {
            throw new IllegalArgumentException("文件包含顶层事务控制语句，请选择脚本控制事务模式。");
        }
        if ("SCRIPT".equals(mode)) {
            connections.requireNoManualTransaction(job.connectionId());
            SqlFileStatementReader.readUnits(checkedPath(job), Charset.forName(job.detectedCharset()), job.targetDbType(), delimiterOf(job),
                    properties.getSqlFile().getMaxStatementChars(), unit -> SqlScriptSyntax.validateScriptControl(unit.sql()), ignored -> { }, directivesFor(job));
        }
        boolean mutation = job.mutationCount() + job.ddlCount() + job.unknownCount() > 0;
        if (mutation || "SCRIPT".equals(mode)) guard.requireMutationAllowed(connection, productionConfirmation);
        else guard.requireQueryAllowed(connection, SqlStatementClassifier.Kind.QUERY, productionConfirmation);
        int scriptLimit = scriptConcurrencyLimit.getAsInt();
        if (jobs.countRunningByConnection(connection.id()) >= scriptLimit) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "SQL_FILE_ALREADY_RUNNING", "该连接的脚本任务已达到并发上限（" + scriptLimit + "），请等待完成后重试。");
        }
        String operationKey = sqlFileOperationKey(id);
        if (!taskControl.tryAcquireScript(connection.id(), operationKey, scriptLimit)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "CONNECTION_BACKGROUND_BUSY", "该连接已有后台重任务正在执行，请等待完成后重试。");
        }
        if (!jobs.queue(id, mode, ending, scriptLimit)) {
            taskControl.releaseCompleted(connection.id(), operationKey);
            throw new ApiProblemException(HttpStatus.CONFLICT, "SQL_FILE_NOT_READY", "SQL 文件任务状态已发生变化。");
        }
        try {
            coordinator.submit(
                    id,
                    () -> executor.execute(id),
                    () -> taskControl.releaseCompleted(connection.id(), operationKey)
            );
        } catch (RejectedExecutionException error) {
            taskControl.releaseCompleted(connection.id(), operationKey);
            jobs.markTerminal(id, "FAILED", "QUEUE_FULL", 0, 0, 0, null, null, "SQL 文件执行队列已满，任务未启动。");
            deleteFileQuietly(job);
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "SQL_FILE_QUEUE_FULL", "SQL 文件执行队列已满，请稍后重试。");
        }
        audit.onConnection(actor, "SQL_FILE_START", connection.id(), "job=" + id + "; file=" + job.fileName() + "; mode=" + mode + "; end=" + ending);
        return response(require(id));
    }

    public SqlFileExecutionResponse cancel(long id, String actor) {
        SqlFileExecution job = require(id);
        if (!Set.of("ANALYZING", "READY", "QUEUED", "RUNNING").contains(job.status())) return response(job);
        jobs.requestCancel(id);
        taskControl.requestCancel(sqlFileOperationKey(id));
        Statement statement = runningStatements.get(id);
        if (statement != null) coordinator.cancelStatement(statement);
        if ("READY".equals(require(id).status())) {
            jobs.markTerminal(id, "CANCELLED", "CANCELLED", job.statementCurrent(), job.successCount(), job.queryRowCount(),
                    null, null, "SQL 文件任务已取消。");
            deleteFileQuietly(job);
        }
        // Queued/running workers observe the cancellation flag and own rollback, terminal status and cleanup.
        audit.onConnection(actor, "SQL_FILE_CANCEL", job.connectionId(), "job=" + id);
        return response(require(id));
    }

    public SqlFileExecutionResponse submitScript(long connectionId, String sql, String actor,
                                                 String productionConfirmation, String mode, String ending) throws Exception {
        // Agent 交来的脚本没有机会回答「按 ! 分段吗」：识别到独占行分隔符就直接按它分，分隔方式写在任务里可查。
        var job = uploadScript(connectionId, "mcp-script.sql", sql.getBytes(StandardCharsets.UTF_8).length,
                writer -> { writer.write(sql); return "source=MCP"; }, actor, "MCP_SCRIPT_UPLOAD", "script", true,
                SqlScriptDelimiter.AUTO, true);
        if (!"READY".equals(job.status())) return job;
        try {
            return start(job.id(), productionConfirmation, actor, mode, ending);
        } catch (Exception error) {
            // 没启动起来的脚本不会再有人去点「执行」：留在 READY 会让它在 db_list_script_tasks 里
            // 一直显示成进行中的任务，调用方据此去等、去取消一个根本不会跑的任务。
            SqlFileExecution draft = require(job.id());
            if ("READY".equals(draft.status())) {
                jobs.markTerminal(draft.id(), "FAILED", "NOT_STARTED", 0, 0, 0, null, null,
                        "脚本未启动：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                deleteFileQuietly(draft);
            }
            throw error;
        }
    }

    public SqlFileExecutionPage ownedScripts(long connectionId, String actor, boolean activeOnly, int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (long) page * pageSize > 1_000_000) {
            throw new IllegalArgumentException("任务分页参数无效：page 从 0 开始，pageSize 为 1–100。");
        }
        var rows = jobs.findOwnedPage(connectionId, actor, activeOnly, pageSize + 1, (long) page * pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);
        return new SqlFileExecutionPage(rows.stream().map(this::response).toList(), page, pageSize, hasMore);
    }

    public SqlFileExecutionResponse ownedScript(long id, String actor) {
        var job = require(id);
        if (!java.util.Objects.equals(job.actor(), actor)) throw new IllegalArgumentException("脚本任务不存在或不属于当前 agent。");
        return response(job);
    }

    public SqlFileExecutionResponse get(long id) { return response(require(id)); }

    /** 排队或运行中的脚本任务数，与 start 时比较并发上限用的是同一个计数。 */
    public int activeScriptCount(long connectionId) { return jobs.countRunningByConnection(connectionId); }

    public SqlFileExecutionPage list(Long connectionId, Integer page, Integer pageSize) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int size = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 100);
        long offset = (long) safePage * size;
        if (offset > 1_000_000) throw new IllegalArgumentException("SQL 文件任务分页偏移过大，请缩小页码。");
        var rows = jobs.findPage(connectionId, size + 1, offset);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        return new SqlFileExecutionPage(rows.stream().map(this::response).toList(), safePage, size, hasMore);
    }

    private void analyze(long id) {
        SqlFileExecution job = require(id);
        try {
            ensureNotCancelled(id);
            Path path = checkedPath(job);
            FileFingerprint before = fingerprint(Files.readAttributes(path, BasicFileAttributes.class));
            FileFingerprint uploaded = analyzedSources.get(id);
            if (uploaded == null || !uploaded.equals(before)) verifyChecksum(job);
            Charset bomCharset = SqlFileStatementReader.detectBomCharset(path);
            Charset charset = bomCharset == null ? StandardCharsets.UTF_8 : bomCharset;
            FileAnalysis analysis;
            try {
                analysis = analyzeFile(job, path, charset);
            } catch (CharacterCodingException invalidUtf8) {
                if (bomCharset != null) throw invalidUtf8;
                charset = Charset.forName("GB18030");
                analysis = analyzeFile(job, path, charset);
            }
            if (analysis.counts()[0] == 0) throw new IllegalArgumentException("SQL 文件中没有可执行语句。");
            FileFingerprint after = fingerprint(Files.readAttributes(path, BasicFileAttributes.class));
            if (!before.equals(after)) throw new IllegalStateException("SQL 文件在分析期间发生变化，请重新选择文件。");
            analyzedSources.put(id, after);
            long[] counts = analysis.counts();
            boolean[] flags = analysis.flags();
            jobs.updateTransactionAnalysis(id, counts[5], counts[6]);
            jobs.markReady(id, charset.name(), counts[0], counts[1], counts[2], counts[3], counts[4], flags[0], flags[1]);
            // Close the race where cancel was requested after the final parser callback.
            ensureNotCancelled(id);
        } catch (CancelledException ignored) {
            jobs.markTerminal(id, "CANCELLED", "CANCELLED", 0, 0, 0, null, null, "SQL 文件解析已取消。");
            deleteFileQuietly(job);
        } catch (Exception error) {
            jobs.markTerminal(id, "FAILED", "ANALYSIS_FAILED", 0, 0, 0, null, null, safeMessage(error));
            deleteFileQuietly(job);
        }
    }

    private FileAnalysis analyzeFile(SqlFileExecution job, Path path, Charset charset) throws Exception {
        long[] counts = new long[7];
        boolean[] flags = new boolean[2];
        long[] lastProgressNanos = {0};
        SqlScriptDelimiter delimiter = delimiterOf(job);
        SqlFileStatementReader.readUnits(path, charset, job.targetDbType(), delimiter, properties.getSqlFile().getMaxStatementChars(), unit -> {
            String sql = unit.sql();
            ensureNotCancelled(job.id());
            // GO batches may contain multiple ordinary SQL statements; PL/SQL bodies must remain opaque.
            boolean sqlServer = Set.of("sqlserver", "sql-server", "mssql").contains(job.targetDbType().toLowerCase(Locale.ROOT));
            boolean routine = SqlScriptSyntax.leadingSql(sql).matches("(?is)(?:CREATE(?:\\s+OR\\s+ALTER)?|ALTER)\\s+(?:PROC(?:EDURE)?|FUNCTION|TRIGGER)\\b.*");
            var whole = java.util.List.of(new SqlScriptSplitter.StatementSegment(sql, 0, sql.length()));
            // 自定义分隔符下一段可以写好几条：按种类要权限、计数时要逐条看，不能只看开头。
            var parts = sqlServer && !routine ? scriptSplitter.split(sql, "plain")
                    : scriptSplitter.classificationUnits(whole, job.targetDbType(), delimiter);
            if (parts.stream().anyMatch(part -> SqlScriptSyntax.transaction(part.sql()) != SqlScriptSyntax.Transaction.NONE)) counts[5]++;
            if (SqlScriptSyntax.opaqueBlock(sql)) { counts[6]++; flags[0] = true; flags[1] = true; }
            SqlStatementClassifier.Kind kind = strongestKind(parts.stream().map(part -> classifier.classify(part.sql())).toList());
            counts[0]++;
            switch (kind) {
                case QUERY -> counts[1]++;
                case MUTATION -> counts[2]++;
                case DDL -> { counts[3]++; flags[0] = true; }
                case UNKNOWN -> counts[4]++;
            }
            flags[1] = flags[1] || parts.stream().anyMatch(part -> classifier.changesSession(part.sql()));
        }, bytes -> {
            ensureNotCancelled(job.id());
            if (progressDue(lastProgressNanos)) {
                jobs.updateAnalysisProgress(job.id(), Math.min(bytes, job.fileSize()));
            }
        }, directivesFor(job));
        return new FileAnalysis(counts, flags);
    }

    boolean cancellationRequested(long id) {
        return Thread.currentThread().isInterrupted() || jobs.findById(id).map(SqlFileExecution::cancelRequested).orElse(false);
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void cleanupExpiredReadyJobs() {
        for (SqlFileExecution job : jobs.findExpiredReady(Instant.now())) {
            jobs.markTerminal(job.id(), "EXPIRED", "EXPIRED", 0, 0, 0, null, null, "SQL 文件等待确认超时，已自动清理。");
            deleteFileQuietly(job);
        }
    }

    SqlFileExecution require(long id) {
        return jobs.findById(id).orElseThrow(() -> new IllegalArgumentException("SQL file execution not found: " + id));
    }

    void ensureNotCancelled(long id) {
        if (taskControl.isCancelled(sqlFileOperationKey(id), () -> jobs.isCancelRequested(id))) {
            throw new CancelledException();
        }
    }

    private Path root() { return Path.of(properties.getSqlFile().getDirectory()).toAbsolutePath().normalize(); }

    Path checkedPath(SqlFileExecution job) { return FileIntegrity.checkedPath(root(), job.filePath()); }

    private void verifyChecksum(SqlFileExecution job) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(checkedPath(job))) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                ensureNotCancelled(job.id());
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        if (!job.checksumSha256().equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()))) {
            throw new IllegalStateException("SQL 文件校验失败，文件内容已发生变化。");
        }
    }

    void verifyUnchanged(SqlFileExecution job) throws Exception {
        Path path = checkedPath(job);
        FileFingerprint verified = analyzedSources.get(job.id());
        FileFingerprint current = fingerprint(Files.readAttributes(path, BasicFileAttributes.class));
        if (verified == null || !verified.equals(current)) verifyChecksum(job);
    }

    private FileFingerprint fingerprint(BasicFileAttributes attributes) {
        return new FileFingerprint(attributes.size(), attributes.lastModifiedTime().toMillis(),
                attributes.fileKey() == null ? null : attributes.fileKey().toString());
    }

    boolean progressDue(long[] lastProgressNanos) {
        long now = System.nanoTime();
        long interval = Math.max(100, properties.getBackgroundTasks().getProgressIntervalMs()) * 1_000_000L;
        if (lastProgressNanos[0] != 0 && now - lastProgressNanos[0] < interval) return false;
        lastProgressNanos[0] = now;
        return true;
    }

    private String sqlFileOperationKey(long id) {
        return "sql-file:" + id;
    }

    void deleteFileQuietly(SqlFileExecution job) {
        try { Files.deleteIfExists(checkedPath(job)); } catch (Exception ignored) { }
        analyzedSources.remove(job.id());
        taskControl.clear(sqlFileOperationKey(job.id()));
    }

    private String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("缺少 SQL 文件名。");
        String value = Path.of(raw).getFileName().toString().trim();
        if (value.isBlank() || value.length() > 500 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("SQL 文件名不合法。");
        return value;
    }

    String preview(String sql) { return sql.length() <= ERROR_SQL_PREVIEW ? sql : sql.substring(0, ERROR_SQL_PREVIEW) + "…"; }

    private SqlStatementClassifier.Kind strongestKind(java.util.List<SqlStatementClassifier.Kind> kinds) {
        // UNKNOWN requires DDL/FULL permission; an ordinary write must never downgrade an opaque operation.
        if (kinds.stream().anyMatch(kind -> kind == SqlStatementClassifier.Kind.UNKNOWN)) return SqlStatementClassifier.Kind.UNKNOWN;
        if (kinds.stream().anyMatch(kind -> kind == SqlStatementClassifier.Kind.DDL)) return SqlStatementClassifier.Kind.DDL;
        if (kinds.stream().anyMatch(kind -> kind == SqlStatementClassifier.Kind.MUTATION)) return SqlStatementClassifier.Kind.MUTATION;
        return SqlStatementClassifier.Kind.QUERY;
    }

    String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.isBlank() ? (error == null ? "未知错误" : error.getClass().getSimpleName()) : (value.length() <= 10_000 ? value : value.substring(0, 10_000));
    }

    private SqlFileExecutionResponse response(SqlFileExecution job) {
        return new SqlFileExecutionResponse(job.id(), job.connectionId(), job.connectionName(), job.targetDbType(), job.fileName(),
                job.fileSize(), job.checksumSha256(), job.detectedCharset(), job.status(), job.phase(), job.processedBytes(),
                job.statementTotal(), job.statementCurrent(), job.queryCount(), job.mutationCount(), job.ddlCount(), job.unknownCount(),
                job.successCount(), job.queryRowCount(), job.failedStatementIndex(), job.failedSqlPreview(), job.message(),
                job.metadataChanged(), job.sessionChanged(), job.cancelRequested(), job.expiresAt(), job.startedAt(), job.finishedAt(), job.createdAt(), job.transaction(),
                job.compilationErrors() == null || job.compilationErrors().isBlank() ? List.of() : job.compilationErrors().lines().toList(),
                delimiterOf(job).isAuto() ? null : delimiterOf(job).label());
    }

    static final class CancelledException extends RuntimeException { }

    private record FileAnalysis(long[] counts, boolean[] flags) { }

    record FileFingerprint(long size, long modifiedMillis, String fileKey) { }
}
