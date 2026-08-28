package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.SshTunnelRequest;
import com.example.dbadmin.dto.ApiDtos.SshTunnelSummary;
import com.example.dbadmin.model.SshTunnelSettings;

import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * SSH 隧道配置的规范化、校验与密文转换。
 *
 * <p>加解密本身由调用方以函数传入，这样这个类保持纯逻辑、可以直接测；
 * {@link ConnectionService} 负责把 {@code CryptoService} 接上来。</p>
 *
 * <p>三个密钥字段（登录口令、私钥、私钥口令）沿用数据库密码的约定：{@code ******} 表示沿用
 * 已保存的值，空串表示清除。否则用户每次改个端口都得把私钥重新贴一遍。</p>
 */
public final class SshTunnelProfile {
    public static final String SECRET_MASK = ConnectionService.PASSWORD_MASK;
    public static final int MAX_HOST_LENGTH = 500;
    public static final int MAX_USERNAME_LENGTH = 240;
    public static final int MAX_FINGERPRINT_LENGTH = 200;
    private static final Set<String> AUTH_MODES = Set.of(SshTunnelSpec.AUTH_PASSWORD, SshTunnelSpec.AUTH_PRIVATE_KEY);

    private SshTunnelProfile() {
    }

    /**
     * 把提交的隧道配置转换成可持久化的设置。
     *
     * @param existing 库里已有的设置，用于解释 {@code ******}；新建连接时传 {@code null}
     * @param encrypt  密文生成函数
     */
    public static SshTunnelSettings toSettings(SshTunnelRequest request, SshTunnelSettings existing, UnaryOperator<String> encrypt) {
        if (request == null || !request.enabled()) return SshTunnelSettings.disabled();
        SshTunnelSettings previous = existing == null ? SshTunnelSettings.disabled() : existing;
        String host = required(request.host(), MAX_HOST_LENGTH, "跳板机地址");
        String username = required(request.username(), MAX_USERNAME_LENGTH, "跳板机登录用户名");
        int port = normalizePort(request.port());
        String authMode = normalizeAuthMode(request.authMode());
        String password = secret(request.password(), previous.encryptedPassword(), encrypt);
        String privateKey = pemSecret(request.privateKey(), previous.encryptedPrivateKey(), encrypt);
        String passphrase = secret(request.passphrase(), previous.encryptedPassphrase(), encrypt);
        String fingerprint = trimToNull(request.serverFingerprint(), MAX_FINGERPRINT_LENGTH, "跳板机主机指纹");

        if (SshTunnelSpec.AUTH_PRIVATE_KEY.equals(authMode) && privateKey == null) {
            throw new IllegalArgumentException("SSH 隧道选择了密钥认证，请粘贴私钥内容。");
        }
        // 与 SshTunnel 建连时的校验保持一致，但提前到保存这一步：让用户在编辑器里就看到问题，
        // 而不是保存成功后每次打开连接都失败。
        if (!request.skipHostKeyCheck() && fingerprint == null) {
            throw new IllegalArgumentException(
                    "请填写跳板机主机指纹（例如 SHA256:xxxx），或在明确风险后勾选「跳过主机密钥校验」。");
        }
        return new SshTunnelSettings(true, host, port, username, authMode,
                password, privateKey, passphrase, fingerprint, request.skipHostKeyCheck());
    }

    /**
     * 把持久化的设置解密成可以建隧道的参数；未启用隧道时返回 {@code null}。
     */
    public static SshTunnelSpec toSpec(SshTunnelSettings settings, UnaryOperator<String> decrypt) {
        if (settings == null || !settings.enabled()) return null;
        return new SshTunnelSpec(
                settings.host(),
                settings.port() <= 0 ? SshTunnelSettings.DEFAULT_PORT : settings.port(),
                settings.username(),
                normalizeAuthMode(settings.authMode()),
                decryptOrNull(settings.encryptedPassword(), decrypt),
                decryptOrNull(settings.encryptedPrivateKey(), decrypt),
                decryptOrNull(settings.encryptedPassphrase(), decrypt),
                settings.serverFingerprint(),
                settings.skipHostKeyCheck()
        );
    }

    /** 回显给界面的摘要：只说哪些密钥已配置，不回传密文。 */
    public static SshTunnelSummary summarize(SshTunnelSettings settings) {
        if (settings == null || !settings.enabled()) {
            return new SshTunnelSummary(false, null, SshTunnelSettings.DEFAULT_PORT, null,
                    SshTunnelSpec.AUTH_PASSWORD, false, false, false, null, false);
        }
        return new SshTunnelSummary(
                true,
                settings.host(),
                settings.port() <= 0 ? SshTunnelSettings.DEFAULT_PORT : settings.port(),
                settings.username(),
                normalizeAuthMode(settings.authMode()),
                isPresent(settings.encryptedPassword()),
                isPresent(settings.encryptedPrivateKey()),
                isPresent(settings.encryptedPassphrase()),
                settings.serverFingerprint(),
                settings.skipHostKeyCheck()
        );
    }

    private static String normalizeAuthMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return SshTunnelSpec.AUTH_PASSWORD;
        if (!AUTH_MODES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的 SSH 认证方式：" + value);
        }
        return normalized;
    }

    private static int normalizePort(Integer value) {
        if (value == null) return SshTunnelSettings.DEFAULT_PORT;
        if (value <= 0 || value > 65535) {
            throw new IllegalArgumentException("跳板机端口超出范围：" + value);
        }
        return value;
    }

    /**
     * 解释一个口令字段：掩码沿用旧值，空串清除，其余原样加密保存。
     *
     * <p>刻意不 {@code trim()}：口令的首尾空格是有效字符，抹掉之后存进去的凭据就和用户
     * 输入的不是同一个，认证会莫名其妙地失败。数据库密码在 {@code ConnectionService} 里
     * 也是原样保存的，两处必须一致。</p>
     */
    private static String secret(String submitted, String storedCipher, UnaryOperator<String> encrypt) {
        if (submitted == null || SECRET_MASK.equals(submitted)) return isPresent(storedCipher) ? storedCipher : null;
        if (submitted.isEmpty()) return null;
        return encrypt.apply(submitted);
    }

    /**
     * 解释私钥字段。
     *
     * <p>与口令相反，这里要 {@code trim()}：PEM 文本的首尾空白没有语义，而从终端或网页
     * 复制私钥时几乎一定会带上换行。</p>
     */
    private static String pemSecret(String submitted, String storedCipher, UnaryOperator<String> encrypt) {
        if (submitted == null || SECRET_MASK.equals(submitted)) return isPresent(storedCipher) ? storedCipher : null;
        String trimmed = submitted.trim();
        if (trimmed.isEmpty()) return null;
        return encrypt.apply(trimmed);
    }

    private static String decryptOrNull(String cipher, UnaryOperator<String> decrypt) {
        return isPresent(cipher) ? decrypt.apply(cipher) : null;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String required(String value, int maxLength, String label) {
        String trimmed = trimToNull(value, maxLength, label);
        if (trimmed == null) throw new IllegalArgumentException("请填写" + label + "。");
        return trimmed;
    }

    private static String trimToNull(String value, int maxLength, String label) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + "超过 " + maxLength + " 个字符。");
        }
        return trimmed;
    }
}
