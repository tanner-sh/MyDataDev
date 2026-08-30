package com.example.dbadmin.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;

/** 审计事件的稳定 SHA-256 串联格式，迁移回填和运行时写入必须共用这一实现。 */
public final class AuditChain {
    private AuditChain() {
    }

    public static String hash(
            String previousHash,
            String actor,
            String action,
            Long connectionId,
            String target,
            String detail,
            String remoteAddress,
            String forwardedFor,
            String userAgent,
            String requestId,
            Timestamp createdAt
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, previousHash);
            append(digest, actor);
            append(digest, action);
            append(digest, connectionId == null ? null : Long.toString(connectionId));
            append(digest, target);
            append(digest, detail);
            append(digest, remoteAddress);
            append(digest, forwardedFor);
            append(digest, userAgent);
            append(digest, requestId);
            append(digest, createdAt == null ? null : Long.toString(createdAt.getTime()));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }

    private static void append(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0xff);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
