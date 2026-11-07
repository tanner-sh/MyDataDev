package com.example.dbadmin.core;

import java.util.Locale;

/**
 * 数据库类型串 → 方言家族（Oracle 系、MySQL 系……）的唯一一份映射。
 *
 * <p>原来有两份：对象目录（{@code SchemaObjectCatalog}）一份、恢复（{@code RestoreService}）一份，别名认得还不一样
 * （一份认 {@code pg}，一份认 {@code dameng}）。恢复要判断的是一个备份文件记下的源库类型，手里只有类型串、没有连接，
 * 所以这里按串映射，而不是从 {@link DialectRegistry} 取方言。</p>
 *
 * <p>对象目录里按家族分支的那一大段 SQL（列对象、取定义、模板）还没有搬进各方言 —— 那是单独一次改动。</p>
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
