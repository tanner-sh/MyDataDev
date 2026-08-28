package com.example.dbadmin.model;

/**
 * 连接上保存的 SSH 隧道配置。
 *
 * <p>口令、私钥、私钥口令都是 {@code CryptoService} 加密后的密文，和数据库密码同一套密钥；
 * 主机指纹与是否跳过校验是明文，因为它们要在界面上回显给用户确认。</p>
 */
public record SshTunnelSettings(
        boolean enabled,
        String host,
        int port,
        String username,
        String authMode,
        String encryptedPassword,
        String encryptedPrivateKey,
        String encryptedPassphrase,
        String serverFingerprint,
        boolean skipHostKeyCheck
) {
    public static final int DEFAULT_PORT = 22;

    /** 关闭状态的空配置，用于「这条连接不走隧道」。 */
    public static SshTunnelSettings disabled() {
        return new SshTunnelSettings(false, null, DEFAULT_PORT, null, null, null, null, null, null, false);
    }
}
