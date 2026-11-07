package com.example.dbadmin.core;

import java.util.Locale;

/**
 * 数据库类型串 → 方言家族（Oracle 系、MySQL 系……）的唯一一份映射。
 *
 * <p>只给恢复（{@code RestoreService}）用：它要判断的是一个备份文件记下的源库类型，手里只有类型串、没有连接，
 * 所以按串映射。手里有连接的地方一律从 {@link DialectRegistry} 取方言 —— 对象目录原来也在这里按串分家族，
 * 现在它的写法挂在方言上（{@link DatabaseDialect#schemaObjects()}），不要再往这里加新的调用方。</p>
 */
public final class DatabaseFamily {
    private DatabaseFamily() {
    }

    /** 小写家族名：postgresql / oracle / sqlserver / sqlite / h2 / clickhouse / mariadb / mysql；认不出的原样（小写）返回。 */
    public static String of(String dbType) {
        String type = dbType == null ? "" : dbType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "postgres", "postgresql", "pg" -> "postgresql";
            case "oracle", "dm", "dameng", "oceanbase-oracle" -> "oracle";
            case "sqlserver", "mssql" -> "sqlserver";
            case "sqlite" -> "sqlite";
            case "h2" -> "h2";
            case "clickhouse" -> "clickhouse";
            case "mariadb" -> "mariadb";
            case "mysql", "oceanbase-mysql" -> "mysql";
            default -> type;
        };
    }

    /** MySQL 与 MariaDB 在 dump 文件、协议层面互通，需要按「MySQL 兼容」看待时用它。 */
    public static boolean isMySqlCompatible(String dbType) {
        String family = of(dbType);
        return family.equals("mysql") || family.equals("mariadb");
    }
}
