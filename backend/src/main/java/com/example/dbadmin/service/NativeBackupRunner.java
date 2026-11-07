package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.BackupService.BackupFile;
import com.example.dbadmin.service.BackupTargets.ResolvedTarget;
import com.example.dbadmin.service.BackupTargets.TableRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.example.dbadmin.service.BackupTargets.blankToNull;

/**
 * 原生工具备份：mysqldump、pg_dump、Oracle exp。
 *
 * <p>从 BackupService 拆出来。这一半管的是外部进程 —— 拼命令行、密码走环境变量或参数文件不上命令行、
 * 超时与强杀、输出截留与密码脱敏；SQL 备份那一半在 {@link SqlBackupWriter}。</p>
 */
final class NativeBackupRunner {
    private static final Logger log = LoggerFactory.getLogger(NativeBackupRunner.class);
    private final ConnectionService connections;
    private final AppProperties properties;
    private final NativeToolLocator nativeTools;
    private final BackupTargets targets;

    NativeBackupRunner(ConnectionService connections, AppProperties properties, NativeToolLocator nativeTools, BackupTargets targets) {
        this.connections = connections;
        this.properties = properties;
        this.nativeTools = nativeTools;
        this.targets = targets;
    }

    /** method 由调用方按当前方言校验过。原生工具是独立进程，连不上连接池里的隧道，得为这次备份单独开一条；dump 跑完才关。 */
    BackupFile run(BackupTask task, DbConnection connection, String method) throws Exception {
        try (RemoteDataSourceRegistry.NativeAccess access = connections.openNativeAccess(connection.id())) {
            String url = access.jdbcUrl();
            ResolvedTarget resolved = targets.resolveTaskTarget(task, connection);
            return switch (method) {
                case "MYSQLDUMP" -> runMysqlDump(task, connection, url, resolved, nativeTools.resolve(NativeToolLocator.Tool.MYSQLDUMP, task.toolPath()).path().toString());
                case "PG_DUMP" -> runPgDump(task, connection, url, resolved, nativeTools.resolve(NativeToolLocator.Tool.PG_DUMP, task.toolPath()).path().toString());
                default -> runOracleExp(task, connection, url, resolved, nativeTools.resolve(NativeToolLocator.Tool.ORACLE_EXP, task.toolPath()).path().toString());
            };
        }
    }

    static boolean isNativeMethod(String method) {
        return "MYSQLDUMP".equals(method) || "PG_DUMP".equals(method) || "ORACLE_EXP".equals(method);
    }

    private BackupFile runMysqlDump(BackupTask task, DbConnection connection, String jdbcUrl, ResolvedTarget resolved, String toolPath) throws Exception {
        MysqlJdbcTarget target = mysqlTarget(jdbcUrl);
        String database = "DATABASE".equals(resolved.scope()) ? target.database() : resolved.namespace();
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("mysqldump 备份需要 JDBC URL 中包含数据库名，或选择一个数据库。");
        }
        Path file = backupPath(task, "mysqldump", ".sql");
        List<String> command = new ArrayList<>();
        command.add(toolPath);
        command.add("--host=" + target.host());
        command.add("--port=" + target.port());
        if (connection.username() != null && !connection.username().isBlank()) {
            command.add("--user=" + connection.username());
        }
        command.add("--result-file=" + file.toAbsolutePath().normalize());
        command.addAll(extraArgs(task.backupMethod(), task.extraArgs()));
        command.add(database);
        if ("TABLES".equals(resolved.scope())) {
            command.addAll(resolved.tables().stream().map(TableRef::name).toList());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        String password = connections.password(connection.id());
        if (password != null) {
            builder.environment().put("MYSQL_PWD", password);
        }
        runNativeProcess(builder, file, "mysqldump", password);
        return new BackupFile(file, Files.size(file));
    }

    /**
     * pg_dump 备份。
     *
     * <p>用 custom 格式（-Fc）而不是纯 SQL：它是 pg_restore 唯一能做选择性恢复的格式，也自带
     * 压缩。密码通过 PGPASSWORD 环境变量传，不会出现在进程命令行里。</p>
     */
    private BackupFile runPgDump(BackupTask task, DbConnection connection, String jdbcUrl, ResolvedTarget resolved, String toolPath) throws Exception {
        PostgresJdbcTarget target = postgresTarget(jdbcUrl);
        // 与 mysqldump 不同：MySQL 的「schema」就是数据库，而 PostgreSQL 的 schema 在库内，
        // 所以无论备份整库还是单个 schema，连的都是 JDBC URL 里的那个库，范围由 --schema 收窄。
        String database = target.database();
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("pg_dump 备份需要 JDBC URL 中包含数据库名。");
        }
        Path file = backupPath(task, "pg-dump", ".dump");
        List<String> command = new ArrayList<>();
        command.add(toolPath);
        command.add("--host=" + target.host());
        command.add("--port=" + target.port());
        if (connection.username() != null && !connection.username().isBlank()) {
            command.add("--username=" + connection.username());
        }
        command.add("--no-password");
        command.add("--format=custom");
        command.add("--file=" + file.toAbsolutePath().normalize());
        if ("SCHEMA".equals(resolved.scope()) && resolved.namespace() != null) {
            command.add("--schema=" + resolved.namespace());
        }
        if ("TABLES".equals(resolved.scope())) {
            for (TableRef table : resolved.tables()) {
                command.add("--table=" + (resolved.namespace() == null ? table.name() : resolved.namespace() + "." + table.name()));
            }
        }
        command.addAll(extraArgs(task.backupMethod(), task.extraArgs()));
        command.add(database);
        ProcessBuilder builder = new ProcessBuilder(command);
        String password = connections.password(connection.id());
        if (password != null) {
            builder.environment().put("PGPASSWORD", password);
        }
        runNativeProcess(builder, file, "pg_dump", password);
        return new BackupFile(file, Files.size(file));
    }

    private BackupFile runOracleExp(BackupTask task, DbConnection connection, String jdbcUrl, ResolvedTarget resolved, String toolPath) throws Exception {
        validateOracleNativeTarget(resolved);
        String username = blankToNull(connection.username());
        if (username == null) {
            throw new IllegalArgumentException("Oracle exp 备份需要配置数据库用户名。");
        }
        Path file = backupPath(task, "oracle-exp", ".dmp");
        Path log = file.resolveSibling(file.getFileName() + ".log");
        String password = connections.password(connection.id());
        String connectName = task.nativeConnectName() == null || task.nativeConnectName().isBlank()
                ? oracleConnectName(jdbcUrl)
                : task.nativeConnectName();
        String userid = username + "/" + (password == null ? "" : password) + (connectName == null || connectName.isBlank() ? "" : "@" + connectName);
        validateOracleParameterValue(userid, "Oracle 用户名、密码或连接名");
        validateOracleParameterValue(file.toAbsolutePath().normalize().toString(), "Oracle 备份文件路径");
        validateOracleParameterValue(log.toAbsolutePath().normalize().toString(), "Oracle 日志路径");
        List<String> parameters = new ArrayList<>();
        parameters.add("userid=" + userid);
        parameters.add("file=" + file.toAbsolutePath().normalize());
        parameters.add("log=" + log.toAbsolutePath().normalize());
        if ("TABLES".equals(resolved.scope())) {
            List<String> tables = resolved.tables().stream()
                    .map(table -> resolved.namespace() == null || resolved.namespace().isBlank()
                            ? table.name()
                            : resolved.namespace() + "." + table.name())
                    .toList();
            parameters.add("tables=(" + String.join(",", tables) + ")");
        } else if ("SCHEMA".equals(resolved.scope())) {
            parameters.add("owner=" + resolved.namespace());
        } else {
            parameters.add("full=y");
        }
        Path parameterFile = writeOracleParameterFile(file, parameters);
        List<String> command = new ArrayList<>();
        command.add(toolPath);
        command.add("parfile=" + parameterFile.toAbsolutePath().normalize());
        command.addAll(extraArgs(task.backupMethod(), task.extraArgs()));
        try {
            runNativeProcess(new ProcessBuilder(command), file, "Oracle exp", password);
            return new BackupFile(file, Files.size(file));
        } finally {
            deleteTemporaryFile(parameterFile);
            deleteTemporaryFile(log);
        }
    }

    private Path writeOracleParameterFile(Path outputFile, List<String> parameters) throws Exception {
        Files.createDirectories(outputFile.getParent());
        Set<PosixFilePermission> ownerOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Path parameterFile = Files.getFileStore(outputFile.getParent()).supportsFileAttributeView("posix")
                ? Files.createTempFile(outputFile.getParent(), ".oracle-exp-", ".par", PosixFilePermissions.asFileAttribute(ownerOnly))
                : Files.createTempFile(outputFile.getParent(), ".oracle-exp-", ".par");
        try {
            Files.write(parameterFile, parameters, StandardCharsets.UTF_8);
            return parameterFile;
        } catch (Exception error) {
            deleteTemporaryFile(parameterFile);
            throw error;
        }
    }

    private void validateOracleParameterValue(String value, String label) {
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException(label + "不能包含换行符或 NUL 字符。");
        }
    }

    private void runNativeProcess(ProcessBuilder builder, Path outputFile, String label, String secret) throws Exception {
        Files.createDirectories(outputFile.getParent());
        builder.redirectErrorStream(true);
        Process process = null;
        ProcessOutputCollector outputCollector = null;
        boolean success = false;
        try {
            process = builder.start();
            outputCollector = new ProcessOutputCollector(process.getInputStream());
            outputCollector.start(label);
            boolean finished = process.waitFor(Math.max(1, properties.getBackup().getTimeoutSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                throw new IllegalStateException(label + " 执行超时。");
            }
            outputCollector.await(5_000);
            String output = outputCollector.output();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(label + " 执行失败，退出码 " + process.exitValue() + (output.isBlank() ? "" : "：" + scrub(output, secret)));
            }
            if (!Files.exists(outputFile) || !Files.isRegularFile(outputFile)) {
                throw new IllegalStateException(label + " 未生成备份文件。");
            }
            success = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            terminateProcess(process);
            if (outputCollector != null) outputCollector.await(5_000);
            if (!success) deleteTemporaryFile(outputFile);
        }
    }

    private void terminateProcess(Process process) {
        if (process == null) return;
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        if (!process.isAlive()) return;
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("Native backup process did not exit after forcible termination pid={}", process.pid());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteTemporaryFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception error) {
            log.warn("Unable to delete temporary backup file {}", path, error);
        }
    }

    private Path backupPath(BackupTask task, String prefix, String extension) throws Exception {
        Path dir = Path.of(properties.getBackup().getDirectory());
        Files.createDirectories(dir);
        return dir.resolve(prefix + "-task-" + task.id() + "-" + Instant.now().toEpochMilli() + extension);
    }

    static NativeToolLocator.Tool extraArgsTool(String backupMethod) {
        return backupMethod == null || "SQL".equals(backupMethod) ? null : backupTool(backupMethod);
    }

    static NativeToolLocator.Tool backupTool(String backupMethod) {
        return switch (backupMethod) {
            case "MYSQLDUMP" -> NativeToolLocator.Tool.MYSQLDUMP;
            case "PG_DUMP" -> NativeToolLocator.Tool.PG_DUMP;
            default -> NativeToolLocator.Tool.ORACLE_EXP;
        };
    }

    List<String> extraArgs(String backupMethod, String extraArgs) {
        return NativeToolArguments.parse(extraArgsTool(backupMethod), extraArgs, "备份");
    }

    MysqlJdbcTarget mysqlTarget(String jdbcUrl) {
        try {
            String raw = jdbcUrl.replaceFirst("^jdbc:", "");
            URI uri = URI.create(raw);
            String database = uri.getPath() == null ? null : uri.getPath().replaceFirst("^/", "");
            if (database != null && database.contains("/")) {
                database = database.substring(0, database.indexOf('/'));
            }
            return new MysqlJdbcTarget(uri.getHost() == null ? "localhost" : uri.getHost(), uri.getPort() == -1 ? 3306 : uri.getPort(), database == null || database.isBlank() ? null : database);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 MySQL JDBC URL：" + jdbcUrl);
        }
    }

    /** jdbc:postgresql://host:port/database?params */
    PostgresJdbcTarget postgresTarget(String jdbcUrl) {
        try {
            URI uri = URI.create(jdbcUrl.replaceFirst("^jdbc:", ""));
            String database = uri.getPath() == null ? null : uri.getPath().replaceFirst("^/", "");
            if (database != null && database.contains("/")) database = database.substring(0, database.indexOf('/'));
            return new PostgresJdbcTarget(
                    uri.getHost() == null ? "localhost" : uri.getHost(),
                    uri.getPort() == -1 ? 5432 : uri.getPort(),
                    database == null || database.isBlank() ? null : database
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 PostgreSQL JDBC URL：" + jdbcUrl);
        }
    }

    String oracleConnectName(String jdbcUrl) {
        String prefix = "jdbc:oracle:thin:@";
        if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
            return null;
        }
        return jdbcUrl.substring(prefix.length());
    }

    private String scrub(String message, String secret) {
        if (secret == null || secret.isBlank()) {
            return message;
        }
        return message.replace(secret, "******");
    }

    private void validateOracleNativeTarget(ResolvedTarget target) {
        if ("DATABASE".equals(target.scope())) {
            return;
        }
        validateOracleNativeIdentifier(target.namespace(), "Schema");
        if ("TABLES".equals(target.scope())) {
            for (TableRef table : target.tables()) {
                validateOracleNativeIdentifier(table.name(), "表");
            }
        }
    }

    private void validateOracleNativeIdentifier(String value, String label) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_$#]*") || !value.equals(value.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Oracle exp 暂不支持需要引号或区分大小写的" + label + "标识符：" + value);
        }
    }

    private static final class ProcessOutputCollector {
        private static final int MAX_CAPTURE_BYTES = 4_000;
        private final InputStream input;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream(MAX_CAPTURE_BYTES);
        private Thread thread;

        private ProcessOutputCollector(InputStream input) {
            this.input = input;
        }

        private void start(String label) {
            thread = new Thread(this::drain, "dbadmin-native-output-" + label.replaceAll("[^A-Za-z0-9_-]", "-"));
            thread.setDaemon(true);
            thread.start();
        }

        private void drain() {
            try (input) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    synchronized (captured) {
                        int retained = Math.min(read, MAX_CAPTURE_BYTES - captured.size());
                        if (retained > 0) captured.write(buffer, 0, retained);
                    }
                }
            } catch (IOException ignored) {
                // Process termination can close the pipe while the collector
                // is blocked. The retained prefix remains useful for errors.
            }
        }

        private void await(long timeoutMillis) {
            Thread running = thread;
            if (running == null) return;
            try {
                running.join(timeoutMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private String output() {
            synchronized (captured) {
                return captured.toString(StandardCharsets.UTF_8);
            }
        }
    }

    private record MysqlJdbcTarget(String host, int port, String database) {
    }

    private record PostgresJdbcTarget(String host, int port, String database) {
    }
}
