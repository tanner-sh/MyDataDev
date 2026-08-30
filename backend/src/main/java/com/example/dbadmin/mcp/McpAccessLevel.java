package com.example.dbadmin.mcp;

import com.example.dbadmin.service.SqlStatementClassifier;

import java.util.Locale;

/**
 * MCP Agent 在单条连接上的访问档位。
 *
 * <p>此前 MCP 工具一律只读，能力上限写死在代码里。实际需求是分档的：查询类 Agent 只该看，
 * 迁移或修数类 Agent 需要写，而这两者不该共用一把钥匙。档位按连接授予（不是按 Agent），
 * 因此同一个 Agent 可以在开发库上有完全权限、在生产库上只读。</p>
 *
 * <p>档位只决定「允许尝试什么」。真正的执行仍然要过 {@code ExecutionGuard}：只读连接拒绝一切
 * 写入，生产连接要求回传连接名确认，未限定范围的 UPDATE/DELETE 要求单独确认，且每一次调用都
 * 落审计。授予档位不会绕过其中任何一项。</p>
 */
public enum McpAccessLevel {
    /** 只读：元数据、表浏览、SELECT、EXPLAIN。 */
    READ_ONLY,
    /** 数据读写：额外允许 INSERT / UPDATE / DELETE。 */
    DATA_WRITE,
    /** 完全：额外允许 DDL 与无法识别的语句。 */
    FULL;

    /** 授予的档位是否覆盖所需档位。枚举声明顺序即从低到高。 */
    public boolean covers(McpAccessLevel required) {
        return ordinal() >= required.ordinal();
    }

    /**
     * 执行一条语句所需的最低档位。
     *
     * <p>UNKNOWN 归到 FULL：分类器认不出来的语句可能是任何东西，按最危险的算。这与
     * {@code ConnectionAccessService.permissionFor} 对 UNKNOWN 的处理保持一致。</p>
     */
    public static McpAccessLevel requiredFor(SqlStatementClassifier.Kind kind) {
        return switch (kind) {
            case QUERY -> READ_ONLY;
            case MUTATION -> DATA_WRITE;
            case DDL, UNKNOWN -> FULL;
        };
    }

    public static McpAccessLevel parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return READ_ONLY;
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("MCP 访问档位只支持 READ_ONLY、DATA_WRITE 或 FULL：" + value);
        }
    }

    public String label() {
        return switch (this) {
            case READ_ONLY -> "只读";
            case DATA_WRITE -> "数据读写";
            case FULL -> "完全";
        };
    }
}
