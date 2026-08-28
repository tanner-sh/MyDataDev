package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class MySqlDialect extends DefaultDialect {
    private static final String IDENTIFIER_QUOTE = String.valueOf((char) 96);

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of("MYSQLDUMP"), List.of("MYSQL"), SchemaObjectCapabilities.mysql());
    }

    @Override
    public void configureStreamingStatement(Connection connection, Statement statement, int fetchSize, int timeoutSeconds) throws java.sql.SQLException {
        if (timeoutSeconds > 0) statement.setQueryTimeout(timeoutSeconds);
        // Connector/J uses this sentinel for a forward-only streaming result set
        // unless cursor fetching has explicitly been enabled in the JDBC URL.
        statement.setFetchSize(Integer.MIN_VALUE);
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("mysql") || (!type.equals("mariadb") && url.startsWith("jdbc:mysql:"));
    }

    @Override
    public NamespaceKind namespaceKind() {
        return NamespaceKind.CATALOG;
    }

    @Override
    public String currentSchema(Connection connection) throws Exception {
        String catalog = connection.getCatalog();
        if (catalog != null && !catalog.isBlank()) {
            return catalog;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DATABASE()")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return IDENTIFIER_QUOTE + identifier.replace(IDENTIFIER_QUOTE, IDENTIFIER_QUOTE + IDENTIFIER_QUOTE) + IDENTIFIER_QUOTE;
    }

    @Override
    public Optional<String> nativeDdl(Connection connection, String schemaName, String objectName, String objectType) throws Exception {
        String sql = "SHOW CREATE " + (objectType != null && objectType.toUpperCase(Locale.ROOT).contains("VIEW") ? "VIEW " : "TABLE ")
                + qualifiedName(schemaName, objectName);
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? Optional.ofNullable(rs.getString(2)) : Optional.empty();
        }
    }

    @Override
    protected List<String> alterColumnSql(String table, String columnName, ColumnInfo original, ColumnDesign column) {
        boolean changed = !sameType(original, column)
                || original.nullable() != column.nullable()
                || !java.util.Objects.equals(normalizeDefault(original.defaultValue()), normalizeDefault(column.defaultValue()));
        return changed
                ? List.of("ALTER TABLE " + table + " MODIFY COLUMN " + columnDefinition(column))
                : List.of();
    }

    @Override
    protected String renameColumnSql(String table, String originalName, ColumnDesign column) {
        return "ALTER TABLE " + table + " CHANGE COLUMN " + quoteIdentifier(originalName) + " " + columnDefinition(column);
    }

    @Override
    protected boolean renameIncludesDefinition() {
        return true;
    }

    @Override
    protected String dropIndexSql(String table, String indexName) {
        return "DROP INDEX " + quoteIdentifier(indexName) + " ON " + table;
    }

    /**
     * MySQL 的字符串字面量默认还认反斜杠转义（sql_mode 不含 NO_BACKSLASH_ESCAPES）。
     *
     * <p>只翻倍单引号是不够的：值末尾的一个反斜杠会把闭合引号转义掉，后面的内容就跑到字符串
     * 外面变成 SQL 了；值中间的 {@code \n} 也会被悄悄变成真的换行。这里按 mysqldump 的惯例
     * 把反斜杠也翻倍。</p>
     *
     * <p>注意这个写法依赖会话的 sql_mode：目标库开了 NO_BACKSLASH_ESCAPES 时，翻倍出来的两个
     * 反斜杠会被原样存下来。预览可以接受这点偏差（它只是给人看的），要真正执行的脚本不行 ——
     * 那条路走 {@link #scriptLiteral}。</p>
     */
    @Override
    public String literal(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof CharSequence text) {
            return "'" + text.toString().replace("\\", "\\\\").replace("'", "''") + "'";
        }
        return super.literal(value);
    }

    /**
     * 生成脚本时避开 sql_mode 的分歧。
     *
     * <p>含反斜杠的值在两种 sql_mode 下需要两种不同的写法，而脚本是先生成、后执行的，生成时
     * 猜到的模式不一定就是执行时的模式（连接的会话初始化 SQL 能改它，脚本也可能被拿到别处
     * 执行）。所以这类值改用十六进制字面量：它不参与任何转义，两种模式下都还原成同一串字节。
     * 带 {@code _utf8mb4} 引导符是为了让 MySQL 知道这串字节的字符集，再按目标列的字符集转换，
     * 而不是当成裸二进制。</p>
     *
     * <p>只有真的含反斜杠的值才这样写 —— 生成的脚本用户是要看的，不能整份都变成十六进制。</p>
     */
    @Override
    public String scriptLiteral(Object value) {
        if (value instanceof byte[] bytes) return scriptBinaryLiteral(bytes);
        if (value instanceof CharSequence text && text.toString().indexOf('\\') >= 0) {
            return "_utf8mb4 0x" + HexFormat.of().formatHex(text.toString().getBytes(StandardCharsets.UTF_8));
        }
        return literal(value);
    }

    @Override
    public String scriptBinaryLiteral(byte[] value) {
        return "0x" + HexFormat.of().formatHex(value);
    }

    @Override
    public String activeSessionsSql() {
        return """
                SELECT ID AS session_id, USER AS session_user, HOST AS session_host, DB AS session_database,
                       STATE AS session_state, COMMAND AS session_command, TIME AS duration_seconds, INFO AS session_sql
                FROM information_schema.PROCESSLIST
                -- 排除工具自己这条连接。面板每 5 秒刷新一次，不排除的话「正在执行」里
                -- 永远挂着本查询自身，反而盖住真正在跑的语句。PostgreSQL 方言一直是这么做的
                -- （pid <> pg_backend_pid()），这里与它对齐。
                WHERE ID <> CONNECTION_ID()
                ORDER BY TIME DESC
                """;
    }

    @Override
    public boolean supportsKillSession() {
        return true;
    }

    @Override
    public String killSessionSql(String sessionId) {
        return "KILL " + Long.parseLong(sessionId);
    }
}
