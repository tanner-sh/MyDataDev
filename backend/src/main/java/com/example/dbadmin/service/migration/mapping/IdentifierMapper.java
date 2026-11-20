package com.example.dbadmin.service.migration.mapping;

import com.example.dbadmin.service.migration.model.DbTargetDialect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 跨库标识符映射器：统一处理大小写策略（PRESERVE / LOWER / UPPER）与目标库标识符长度上限截断防碰撞。
 */
public final class IdentifierMapper {
    private IdentifierMapper() {
    }

    public record MappedIdentifier(String targetName, boolean truncated, String originalName) {
    }

    public static MappedIdentifier map(String originalName, DbTargetDialect target, String casePolicy) {
        if (originalName == null || originalName.isBlank()) {
            return new MappedIdentifier("", false, originalName);
        }
        String cased = applyCase(originalName.trim(), casePolicy);
        int maxLen = target == null ? 128 : target.maxIdentifierLength();
        if (cased.length() <= maxLen) {
            return new MappedIdentifier(cased, false, originalName);
        }
        String hash = shortHash(originalName);
        int keep = Math.max(1, maxLen - hash.length() - 1);
        return new MappedIdentifier(cased.substring(0, keep) + "_" + hash, true, originalName);
    }

    public static String applyCase(String name, String casePolicy) {
        if (name == null) return "";
        if ("LOWER".equalsIgnoreCase(casePolicy)) return name.toLowerCase(Locale.ROOT);
        if ("UPPER".equalsIgnoreCase(casePolicy)) return name.toUpperCase(Locale.ROOT);
        return name;
    }

    private static String shortHash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 3);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
