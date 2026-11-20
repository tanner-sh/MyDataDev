package com.example.dbadmin.service.migration.model;

import java.util.Locale;

/**
 * MyDataDev 支持转换的全部 11 种数据库方言及其结构/转换元属性。
 *
 * <p>集中收敛所有方言家族判断、标识符长度上限、引用符号与支持能力，避免在映射/DDL/校验各层散落字符串比较。</p>
 */
public enum DbTargetDialect {
    ORACLE("oracle", Family.ORACLE, 128, "\"", "\"", true, true, true, true, true, true, 9, false),
    OCEANBASE_ORACLE("oceanbase-oracle", Family.ORACLE, 128, "\"", "\"", true, true, true, true, true, true, 9, false),
    DAMENG("dm", Family.ORACLE, 128, "\"", "\"", true, true, true, true, true, true, 6, false),
    MYSQL("mysql", Family.MYSQL, 64, "`", "`", false, true, true, true, true, true, 6, true),
    OCEANBASE_MYSQL("oceanbase-mysql", Family.MYSQL, 64, "`", "`", false, true, true, true, true, true, 6, true),
    MARIADB("mariadb", Family.MYSQL, 64, "`", "`", true, true, true, true, true, true, 6, true),
    POSTGRESQL("postgresql", Family.POSTGRESQL, 63, "\"", "\"", true, true, true, true, true, true, 6, false),
    SQLSERVER("sqlserver", Family.SQLSERVER, 128, "[", "]", true, true, true, true, true, true, 7, false),
    SQLITE("sqlite", Family.SQLITE, 128, "\"", "\"", false, true, true, false, false, true, 3, false),
    H2("h2", Family.H2, 128, "\"", "\"", true, true, true, true, true, true, 9, false),
    CLICKHOUSE("clickhouse", Family.CLICKHOUSE, 128, "`", "`", false, false, false, true, true, false, 9, false);

    public enum Family {
        ORACLE,
        MYSQL,
        POSTGRESQL,
        SQLSERVER,
        SQLITE,
        H2,
        CLICKHOUSE
    }

    private final String code;
    private final Family family;
    private final int maxIdentifierLength;
    private final String openQuote;
    private final String closeQuote;
    private final boolean supportsSequences;
    private final boolean supportsForeignKeys;
    private final boolean supportsSecondaryIndexes;
    private final boolean supportsColumnComments;
    private final boolean supportsTableComments;
    private final boolean supportsCheckConstraints;
    private final int maxTemporalFsp;
    private final boolean catalogNamespace;

    DbTargetDialect(String code, Family family, int maxIdentifierLength, String openQuote, String closeQuote,
                    boolean supportsSequences, boolean supportsForeignKeys, boolean supportsSecondaryIndexes,
                    boolean supportsColumnComments, boolean supportsTableComments, boolean supportsCheckConstraints,
                    int maxTemporalFsp, boolean catalogNamespace) {
        this.code = code;
        this.family = family;
        this.maxIdentifierLength = maxIdentifierLength;
        this.openQuote = openQuote;
        this.closeQuote = closeQuote;
        this.supportsSequences = supportsSequences;
        this.supportsForeignKeys = supportsForeignKeys;
        this.supportsSecondaryIndexes = supportsSecondaryIndexes;
        this.supportsColumnComments = supportsColumnComments;
        this.supportsTableComments = supportsTableComments;
        this.supportsCheckConstraints = supportsCheckConstraints;
        this.maxTemporalFsp = maxTemporalFsp;
        this.catalogNamespace = catalogNamespace;
    }

    public String code() { return code; }
    public Family family() { return family; }
    public int maxIdentifierLength() { return maxIdentifierLength; }
    public boolean supportsSequences() { return supportsSequences; }
    public boolean supportsForeignKeys() { return supportsForeignKeys; }
    public boolean supportsSecondaryIndexes() { return supportsSecondaryIndexes; }
    public boolean supportsColumnComments() { return supportsColumnComments; }
    public boolean supportsTableComments() { return supportsTableComments; }
    public boolean supportsCheckConstraints() { return supportsCheckConstraints; }
    public int maxTemporalFsp() { return maxTemporalFsp; }
    public boolean catalogNamespace() { return catalogNamespace; }

    public boolean sameFamily(DbTargetDialect other) {
        return other != null && this.family == other.family;
    }

    public boolean isOracleFamily() {
        return family == Family.ORACLE;
    }

    public boolean isMySqlFamily() {
        return family == Family.MYSQL;
    }

    public String quoteIdentifier(String name) {
        if (name == null) return "";
        if ("[".equals(openQuote)) {
            return "[" + name.replace("]", "]]") + "]";
        }
        return openQuote + name.replace(closeQuote, closeQuote + closeQuote) + closeQuote;
    }

    public String qualifiedName(String schema, String name) {
        if (this == SQLITE || schema == null || schema.isBlank()) {
            return quoteIdentifier(name);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(name);
    }

    public static DbTargetDialect fromDbType(String dbType) {
        if (dbType == null || dbType.isBlank()) {
            throw new IllegalArgumentException("数据库类型不能为空。");
        }
        String normalized = dbType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "oracle" -> ORACLE;
            case "oceanbase-oracle" -> OCEANBASE_ORACLE;
            case "dm", "dameng" -> DAMENG;
            case "mysql" -> MYSQL;
            case "oceanbase-mysql" -> OCEANBASE_MYSQL;
            case "mariadb" -> MARIADB;
            case "postgres", "postgresql", "pg" -> POSTGRESQL;
            case "sqlserver", "sql-server", "mssql" -> SQLSERVER;
            case "sqlite" -> SQLITE;
            case "h2" -> H2;
            case "clickhouse" -> CLICKHOUSE;
            default -> throw new IllegalArgumentException("暂不支持迁移转换的数据库类型：" + dbType);
        };
    }
}
