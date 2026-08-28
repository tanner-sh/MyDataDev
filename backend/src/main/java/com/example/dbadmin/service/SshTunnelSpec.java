package com.example.dbadmin.service;

import java.util.Locale;

/**
 * 打开一条 SSH 隧道所需的全部参数，密钥与口令都已经解密。
 *
 * <p>这个对象只在内存里存在，来自 {@link ConnectionService} 对连接配置的解密结果；持久化的
 * 一侧是 {@code DbConnection} 上的 SSH 字段。</p>
 */
public record SshTunnelSpec(
        String host,
        int port,
        String username,
        String authMode,
        String password,
        String privateKey,
        String passphrase,
        String serverFingerprint,
        boolean skipHostKeyCheck
) {
    public static final String AUTH_PASSWORD = "PASSWORD";
    public static final String AUTH_PRIVATE_KEY = "PRIVATE_KEY";

    public boolean usesPrivateKey() {
        return AUTH_PRIVATE_KEY.equalsIgnoreCase(authMode);
    }

    /**
     * 连接池指纹用的摘要素材。
     *
     * <p>隧道参数变了必须重建池：池里的物理连接连的是旧隧道的本地端口。</p>
     */
    public String fingerprintMaterial() {
        return String.join(" ",
                nullSafe(host),
                Integer.toString(port),
                nullSafe(username),
                nullSafe(authMode).toUpperCase(Locale.ROOT),
                nullSafe(password),
                nullSafe(privateKey),
                nullSafe(passphrase),
                nullSafe(serverFingerprint),
                Boolean.toString(skipHostKeyCheck));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
