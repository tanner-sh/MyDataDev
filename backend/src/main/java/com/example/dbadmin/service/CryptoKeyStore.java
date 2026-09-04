package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/** 主密钥的系统托管入口：常规部署使用受保护文件，桌面版使用一次性标准输入。 */
@Component
public class CryptoKeyStore {
    public static final String DEFAULT_KEY_FILE = "./secrets/mydatadev-master.key";
    private static final int KEY_BYTES = 32;
    private static final long MAX_KEY_FILE_BYTES = 4096;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final SecureRandom random = new SecureRandom();
    private final InputStream standardInput;

    public CryptoKeyStore() {
        this(System.in);
    }

    CryptoKeyStore(InputStream standardInput) {
        this.standardInput = standardInput;
    }

    public LoadedKey load(AppProperties properties, Environment environment, boolean hasEncryptedSecrets) {
        rejectLegacyConfiguration(environment);
        String source = properties.getCryptoKeySource() == null
                ? "FILE"
                : properties.getCryptoKeySource().trim().toUpperCase(Locale.ROOT);
        return switch (source) {
            case "FILE" -> loadFile(properties.getCryptoKeyFile(), hasEncryptedSecrets);
            case "STDIN" -> new LoadedKey(readFromStandardInput(), null, null);
            default -> throw new IllegalStateException("不支持的主密钥来源：" + source + "。只允许 FILE 或 STDIN。");
        };
    }

    public void promote(LoadedKey loaded) {
        if (loaded.pendingPath() == null) return;
        if (Files.exists(loaded.finalPath())) {
            throw new IllegalStateException("正式主密钥文件已存在，不能提升待验证密钥：" + loaded.finalPath());
        }
        moveAtomically(loaded.pendingPath(), loaded.finalPath(), false);
    }

    /** 迁移命令只写 pending；只有后端成功解开全部历史密文后才会提升为正式文件。 */
    public void stageAdoption(Path keyFile, String legacyKey) {
        if (legacyKey == null || legacyKey.isBlank() || containsLineBreak(legacyKey)) {
            throw new IllegalArgumentException("旧主密钥不能为空，也不能包含换行符。");
        }
        Path normalized = normalize(keyFile);
        if (Files.exists(normalized)) {
            throw new IllegalStateException("正式主密钥文件已经存在，拒绝覆盖：" + normalized);
        }
        writeAtomically(pendingPath(normalized), legacyKey, true);
    }

    public static Path pendingPath(Path keyFile) {
        return keyFile.resolveSibling(keyFile.getFileName() + ".pending");
    }

    private LoadedKey loadFile(String configuredPath, boolean hasEncryptedSecrets) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("app.crypto-key-file 不能为空。");
        }
        Path keyFile = normalize(Path.of(configuredPath));
        Path pending = pendingPath(keyFile);
        if (Files.exists(keyFile) && Files.exists(pending)) {
            throw new IllegalStateException("正式与待验证主密钥文件同时存在，请先移走待验证文件：" + pending);
        }
        if (Files.exists(keyFile)) {
            return new LoadedKey(readKeyFile(keyFile), keyFile, null);
        }
        if (Files.exists(pending)) {
            return new LoadedKey(readKeyFile(pending), keyFile, pending);
        }
        if (hasEncryptedSecrets) {
            throw new IllegalStateException("检测到历史加密凭据，但主密钥文件不存在。请先执行 `crypto-key adopt` 接管旧密钥；为保护历史数据，本次没有生成新密钥。");
        }

        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        try {
            writeAtomically(keyFile, generated, false);
        } catch (IllegalStateException error) {
            if (!Files.exists(keyFile)) throw error;
        }
        return new LoadedKey(readKeyFile(keyFile), keyFile, null);
    }

    private String readFromStandardInput() {
        try {
            String value = new BufferedReader(new InputStreamReader(standardInput, StandardCharsets.UTF_8)).readLine();
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("桌面启动器没有通过标准输入提供主密钥。");
            }
            return value;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("无法从标准输入读取桌面主密钥。", error);
        }
    }

    private void rejectLegacyConfiguration(Environment environment) {
        if (environment.getProperty("DB_ADMIN_CRYPTO_KEY") != null
                || environment.getProperty("app.crypto-key") != null) {
            throw new IllegalStateException("DB_ADMIN_CRYPTO_KEY/app.crypto-key 已停止支持。已有安装请运行 `crypto-key adopt`，新安装会自动生成主密钥文件。");
        }
    }

    private String readKeyFile(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_KEY_FILE_BYTES) {
                throw new IllegalStateException("主密钥路径不是有效的小型普通文件：" + path);
            }
            String value = Files.readString(path, StandardCharsets.UTF_8);
            if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
            if (value.endsWith("\r")) value = value.substring(0, value.length() - 1);
            if (value.isBlank() || containsLineBreak(value)) {
                throw new IllegalStateException("主密钥文件为空或包含多行内容：" + path);
            }
            return value;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("无法读取主密钥文件：" + path, error);
        }
    }

    private void writeAtomically(Path target, String value, boolean replace) {
        Path parent = target.getParent();
        Path temporary = null;
        try {
            boolean parentExisted = Files.exists(parent);
            Files.createDirectories(parent);
            if (!parentExisted) setPermissions(parent, DIRECTORY_PERMISSIONS);
            temporary = supportsPosix(parent)
                    ? Files.createTempFile(parent, ".mydatadev-key-", ".tmp", PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
                    : Files.createTempFile(parent, ".mydatadev-key-", ".tmp");
            Files.writeString(temporary, value + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            setPermissions(temporary, FILE_PERMISSIONS);
            moveAtomically(temporary, target, replace);
            temporary = null;
        } catch (FileAlreadyExistsException error) {
            throw new IllegalStateException("主密钥文件已经存在，拒绝覆盖：" + target, error);
        } catch (Exception error) {
            throw new IllegalStateException("无法安全写入主密钥文件：" + target, error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // 启动错误保留为主因；遗留的随机临时文件不包含可用路径引用。
                }
            }
        }
    }

    private void moveAtomically(Path source, Path target, boolean replace) {
        try {
            if (replace) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            try {
                if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(source, target);
            } catch (Exception fallbackError) {
                throw new IllegalStateException("无法提交主密钥文件：" + target, fallbackError);
            }
        } catch (Exception error) {
            throw new IllegalStateException("无法提交主密钥文件：" + target, error);
        }
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean supportsPosix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws Exception {
        if (supportsPosix(path)) Files.setPosixFilePermissions(path, permissions);
    }

    public record LoadedKey(String material, Path finalPath, Path pendingPath) {
    }
}
