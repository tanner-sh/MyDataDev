package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.NativeToolStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class NativeToolLocator {
    private static final int MAX_VERSION_BYTES = 8 * 1024;

    private final AppProperties properties;
    private final Map<String, String> environment;
    private final String osName;

    @Autowired
    public NativeToolLocator(AppProperties properties) {
        this(properties, System.getenv(), System.getProperty("os.name", ""));
    }

    NativeToolLocator(AppProperties properties, Map<String, String> environment, String osName) {
        this.properties = properties;
        this.environment = Map.copyOf(environment);
        this.osName = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    }

    public ResolvedTool resolve(Tool tool, String override) {
        List<Candidate> candidates = candidates(tool, override);
        List<String> checked = new ArrayList<>();
        List<String> probeFailures = new ArrayList<>();
        for (Candidate candidate : candidates) {
            checked.add(candidate.path().toString());
            Path executable = executable(candidate.path(), tool);
            if (executable == null) continue;
            Probe probe = probe(executable, tool);
            if (probe.success()) {
                return new ResolvedTool(tool, executable, probe.version(), candidate.source());
            }
            probeFailures.add(executable + "（" + probe.message() + "）");
            if (override != null && !override.isBlank()) {
                throw new IllegalArgumentException(tool.displayName() + " 无法执行：" + probe.message());
            }
        }
        String suffix = checked.isEmpty() ? "未配置任何可搜索目录。" : "已检查 " + String.join("、", checked.stream().limit(8).toList()) + (checked.size() > 8 ? " 等位置。" : "。");
        if (!probeFailures.isEmpty()) suffix += "候选工具验证失败：" + String.join("、", probeFailures.stream().limit(3).toList()) + "。";
        throw new IllegalArgumentException("未在应用服务器上找到可用的 " + tool.displayName() + "。" + suffix + "可通过 app.native-tools 配置固定路径，或切换为手动路径。");
    }

    public NativeToolStatus detect(Tool tool) {
        try {
            ResolvedTool resolved = resolve(tool, null);
            return new NativeToolStatus(tool.name(), tool.displayName(), true, resolved.path().toString(), resolved.version(), resolved.source(), "已自动发现");
        } catch (Exception error) {
            return new NativeToolStatus(tool.name(), tool.displayName(), false, null, null, null, safeMessage(error));
        }
    }

    public List<NativeToolStatus> detectAll() {
        return java.util.Arrays.stream(Tool.values()).map(this::detect).toList();
    }

    public void validateOverrideName(Tool tool, String override) {
        if (override == null || override.isBlank()) return;
        String normalized = override.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (!tool.fileNames(windows()).contains(name)) {
            throw new IllegalArgumentException("工具路径必须指向 " + tool.displayName() + " 可执行文件。");
        }
    }

    private List<Candidate> candidates(Tool tool, String override) {
        LinkedHashSet<Candidate> result = new LinkedHashSet<>();
        if (override != null && !override.isBlank()) {
            validateOverrideName(tool, override);
            Path raw = Path.of(override.trim());
            if (raw.isAbsolute() || override.contains("/") || override.contains("\\")) {
                result.add(new Candidate(raw, "MANUAL"));
            } else {
                addFromSearchPath(result, tool, environment.get("PATH"), "MANUAL_PATH");
            }
            return List.copyOf(result);
        }

        String configured = configuredPath(tool);
        if (configured != null && !configured.isBlank()) result.add(new Candidate(Path.of(configured.trim()), "CONFIG"));
        addHome(result, tool, tool.mysqlFamily() ? environment.get("MYSQL_HOME") : environment.get("ORACLE_HOME"));
        addFromSearchPath(result, tool, environment.get("PATH"), "PATH");
        for (String path : properties.getNativeTools().getExtraSearchPaths()) addDirectory(result, tool, path, "EXTRA_PATH");
        for (Path directory : commonDirectories()) addDirectory(result, tool, directory.toString(), "COMMON");
        return List.copyOf(result);
    }

    private void addHome(Set<Candidate> result, Tool tool, String home) {
        if (home == null || home.isBlank()) return;
        addDirectory(result, tool, Path.of(home).resolve("bin").toString(), tool.mysqlFamily() ? "MYSQL_HOME" : "ORACLE_HOME");
    }

    private void addFromSearchPath(Set<Candidate> result, Tool tool, String searchPath, String source) {
        if (searchPath == null || searchPath.isBlank()) return;
        String separator = windows() ? ";" : File.pathSeparator;
        for (String directory : searchPath.split(java.util.regex.Pattern.quote(separator))) {
            addDirectory(result, tool, directory, source);
        }
    }

    private void addDirectory(Set<Candidate> result, Tool tool, String directory, String source) {
        if (directory == null || directory.isBlank()) return;
        Path root;
        try { root = Path.of(directory.trim()); } catch (Exception ignored) { return; }
        for (String fileName : tool.fileNames(windows())) result.add(new Candidate(root.resolve(fileName), source));
    }

    private List<Path> commonDirectories() {
        List<Path> paths = new ArrayList<>();
        if (windows()) {
            addWindowsHomes(paths, environment.get("ProgramFiles"));
            addWindowsHomes(paths, environment.get("ProgramFiles(x86)"));
        } else {
            for (String value : List.of("/usr/bin", "/usr/local/bin", "/opt/mysql/bin", "/opt/oracle/bin",
                    "/opt/homebrew/bin", "/opt/homebrew/opt/mysql-client/bin", "/usr/local/opt/mysql-client/bin")) {
                paths.add(Path.of(value));
            }
        }
        return paths;
    }

    private void addWindowsHomes(List<Path> paths, String programFiles) {
        if (programFiles == null || programFiles.isBlank()) return;
        Path root = Path.of(programFiles);
        paths.add(root.resolve("MySQL/MySQL Server 8.4/bin"));
        paths.add(root.resolve("MySQL/MySQL Server 8.0/bin"));
        paths.add(root.resolve("Oracle/bin"));
    }

    private Path executable(Path candidate, Tool tool) {
        try {
            Path path = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) return null;
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!tool.fileNames(windows()).contains(name)) return null;
            return path.toRealPath();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Probe probe(Path executable, Tool tool) {
        Process process = null;
        CompletableFuture<String> output = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.addAll(tool.probeArguments());
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process target = process;
            output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(target.getInputStream().readNBytes(MAX_VERSION_BYTES), StandardCharsets.UTF_8).trim();
                } catch (Exception error) {
                    return "";
                }
            });
            int timeout = Math.max(1, Math.min(properties.getNativeTools().getProbeTimeoutSeconds(), 30));
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                terminate(process);
                return new Probe(false, null, "版本探测超时");
            }
            String text = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 && text.isBlank()) return new Probe(false, null, "版本命令退出码 " + process.exitValue());
            String version = text.lines().findFirst().orElse(tool.displayName());
            return new Probe(true, truncate(version, 500), null);
        } catch (Exception error) {
            if (process != null) terminate(process);
            if (output != null) output.cancel(true);
            return new Probe(false, null, safeMessage(error));
        }
    }

    private String configuredPath(Tool tool) {
        AppProperties.NativeTools tools = properties.getNativeTools();
        return switch (tool) {
            case MYSQLDUMP -> tools.getMysqldumpPath();
            case MYSQL -> tools.getMysqlPath();
            case ORACLE_EXP -> tools.getOracleExpPath();
            case ORACLE_IMP -> tools.getOracleImpPath();
        };
    }

    private void terminate(Process process) {
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        if (process.isAlive()) process.destroyForcibly();
    }

    private boolean windows() { return osName.startsWith("windows"); }
    private String truncate(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }
    private String safeMessage(Exception error) { return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage(); }

    public enum Tool {
        MYSQLDUMP("MySQL mysqldump", true, List.of("--version")),
        MYSQL("MySQL mysql", true, List.of("--version")),
        ORACLE_EXP("Oracle exp", false, List.of("help=y")),
        ORACLE_IMP("Oracle imp", false, List.of("help=y"));

        private final String displayName;
        private final boolean mysqlFamily;
        private final List<String> probeArguments;

        Tool(String displayName, boolean mysqlFamily, List<String> probeArguments) {
            this.displayName = displayName;
            this.mysqlFamily = mysqlFamily;
            this.probeArguments = probeArguments;
        }

        public String displayName() { return displayName; }
        boolean mysqlFamily() { return mysqlFamily; }
        List<String> probeArguments() { return probeArguments; }
        Set<String> fileNames(boolean windows) {
            String base = switch (this) {
                case MYSQLDUMP -> "mysqldump";
                case MYSQL -> "mysql";
                case ORACLE_EXP -> "exp";
                case ORACLE_IMP -> "imp";
            };
            return windows ? Set.of(base + ".exe") : Set.of(base);
        }
    }

    public record ResolvedTool(Tool tool, Path path, String version, String source) { }
    private record Candidate(Path path, String source) { }
    private record Probe(boolean success, String version, String message) { }
}
