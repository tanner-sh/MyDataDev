package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class OracleDialect extends DefaultDialect {
    /** Oracle 把未加引号的标识符折成大写存进字典。 */
    @Override
    public String foldUnquotedIdentifier(String identifier) {
        return identifier == null ? null : identifier.toUpperCase(Locale.ROOT);
    }

    /** 多行 VALUES 要到 23 才支持；按旧版本的写法生成，脚本在任何一台 Oracle 上都跑得通。 */
    @Override
    public boolean supportsMultiRowValues() {
        return false;
    }

    // Oracle-compatible unquoted identifiers must start with a letter. Keep this
    // helper unquoted so ResultSet metadata is consistent across Oracle and
    // OceanBase Oracle mode.
    private static final String PAGE_ROW_COLUMN = "DBADMIN_PAGE_RN";


    @Override
    public String castToText(String expression) {
        return "TO_CHAR(" + expression + ")";
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of("ORACLE_EXP"), List.of("ORACLE_IMP"), SchemaObjectCapabilities.oracleFamily());
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "oracle".equalsIgnoreCase(dbType)
                || (jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:oracle:"));
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        long upperBound = (long) offset + limit;
        return "SELECT * FROM (SELECT dbadmin_page_source.*, ROWNUM " + PAGE_ROW_COLUMN + " FROM (" + baseSql
                + ") dbadmin_page_source WHERE ROWNUM <= " + upperBound + ") WHERE " + PAGE_ROW_COLUMN + " > " + offset;
    }

    @Override
    public String paginationHelperColumn() {
        return PAGE_ROW_COLUMN;
    }

    @Override
    public String currentSchema(Connection connection) throws Exception {
        try {
            String schema = sessionCurrentSchema(connection);
            if (schema != null) return schema;
        } catch (Exception ignored) {
            // Fall back to the portable JDBC schema/catalog lookup.
        }
        return super.currentSchema(connection);
    }

    /** 从会话上下文读当前 schema，Oracle 与 OceanBase 的 Oracle 模式都认这一条。读不到返回 null。 */
    static String sessionCurrentSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL")) {
            String schema = rs.next() ? rs.getString(1) : null;
            return schema == null || schema.isBlank() ? null : schema;
        }
    }

    @Override
    public SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception {
        long started = System.nanoTime();
        try (Statement explain = connection.createStatement()) {
            explain.setQueryTimeout(timeoutSeconds);
            explain.execute("EXPLAIN PLAN FOR " + sql);
        }
        try (Statement display = connection.createStatement()) {
            display.setQueryTimeout(timeoutSeconds);
            display.setMaxRows(maxRows + 1);
            try (ResultSet rs = display.executeQuery("SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
                return readResult(rs, (System.nanoTime() - started) / 1_000_000, maxRows);
            }
        }
    }

    @Override
    public Optional<String> nativeDdl(Connection connection, String schemaName, String objectName, String objectType) throws Exception {
        String ddlType = objectType != null && objectType.toUpperCase(Locale.ROOT).contains("VIEW") ? "VIEW" : "TABLE";
        String schema = schemaName == null || schemaName.isBlank() ? currentSchema(connection) : schemaName;
        String sql = "SELECT DBMS_METADATA.GET_DDL('" + ddlType + "', ?, ?) FROM DUAL";
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, objectName);
            statement.setString(2, schema);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    protected List<String> alterColumnSql(String table, String columnName, ColumnInfo original, ColumnDesign column) {
        boolean typeChanged = !sameType(original, column);
        boolean nullableChanged = original.nullable() != column.nullable();
        boolean defaultChanged = !java.util.Objects.equals(normalizeDefault(original.defaultValue()), normalizeDefault(column.defaultValue()));
        if (!typeChanged && !nullableChanged && !defaultChanged) {
            return List.of();
        }
        String definition = quoteIdentifier(columnName);
        if (typeChanged) {
            definition += " " + type(column.type(), column.size());
        }
        if (defaultChanged) {
            definition += blankToNull(column.defaultValue()) == null ? " DEFAULT NULL" : " DEFAULT " + column.defaultValue().trim();
        }
        if (nullableChanged) {
            definition += column.nullable() ? " NULL" : " NOT NULL";
        }
        return List.of("ALTER TABLE " + table + " MODIFY (" + definition + ")");
    }

    @Override
    protected String addColumnSql(String table, ColumnDesign column) {
        return "ALTER TABLE " + table + " ADD (" + columnDefinition(column) + ")";
    }

    @Override
    protected List<String> primaryKeySql(String table, ObjectDetail original, List<String> requestedPrimaryKeys) {
        List<String> requested = requestedPrimaryKeys == null ? List.of() : requestedPrimaryKeys.stream().filter(name -> name != null && !name.isBlank()).toList();
        if (sameNames(original.primaryKeys(), requested)) {
            return List.of();
        }
        List<String> sql = new ArrayList<>();
        if (!original.primaryKeys().isEmpty()) {
            if (original.primaryKeyName() == null || original.primaryKeyName().isBlank()) {
                throw new IllegalArgumentException("Oracle 主键变更需要可识别的主键约束名。");
            }
            sql.add("ALTER TABLE " + table + " DROP CONSTRAINT " + quoteIdentifier(original.primaryKeyName()));
        }
        if (!requested.isEmpty()) {
            sql.add("ALTER TABLE " + table + " ADD PRIMARY KEY (" + String.join(", ", requested.stream().map(this::quoteIdentifier).toList()) + ")");
        }
        return sql;
    }

    @Override
    public String literal(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        return super.literal(value);
    }

    @Override
    public String scriptBinaryLiteral(byte[] value) {
        return "hextoraw('" + HexFormat.of().formatHex(value) + "')";
    }

    /**
     * 时间字面量必须写成显式转换。
     *
     * <p>Oracle 按会话的 {@code NLS_DATE_FORMAT} 解析裸字符串，默认是 {@code DD-MON-RR}：
     * {@code '2026-09-09 12:34:56.123456'} 在那个格式下直接 ORA-01843「月份无效」。也就是说
     * 导出的 SQL 与备份文件里的时间列，在 Oracle 上压根跑不回去 —— 而脚本是先生成、后执行的，
     * 执行时的会话设置无从假设，所以只能在字面量里把格式写死。</p>
     *
     * <p>{@code FF} 不带位数时匹配任意位小数秒，所以有没有小数秒都用同一个格式串。</p>
     */
    @Override
    public String scriptTemporalLiteral(String isoText) {
        String text = isoText.trim();
        String quoted = "'" + text.replace("'", "''") + "'";
        boolean hasTime = text.indexOf(':') >= 0;
        boolean hasDate = text.indexOf('-') > 0;
        if (!hasDate) {
            // Oracle 没有独立的 TIME 类型；只有时间的值只能当文本落地，交给列的类型去决定。
            return quoted;
        }
        if (!hasTime) return "TO_DATE(" + quoted + ", 'YYYY-MM-DD')";
        // 带时区偏移（OffsetDateTime/ZonedDateTime 的 toString）要用 TZ 版本，否则偏移被当成垃圾字符。
        String afterTime = text.substring(text.indexOf(':'));
        if (afterTime.indexOf('+') >= 0 || afterTime.indexOf('Z') >= 0 || afterTime.lastIndexOf('-') > 0) {
            return "TO_TIMESTAMP_TZ(" + quoted + ", 'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM')";
        }
        boolean hasFraction = text.indexOf('.') >= 0;
        return "TO_TIMESTAMP(" + quoted + ", 'YYYY-MM-DD HH24:MI:SS" + (hasFraction ? ".FF" : "") + "')";
    }

    @Override
    public String activeSessionsSql() {
        return """
                SELECT s.SID AS session_id, s.USERNAME AS session_user, s.MACHINE AS session_host,
                       s.SCHEMANAME AS session_database, s.STATUS AS session_state, s.PROGRAM AS session_command,
                       s.LAST_CALL_ET AS duration_seconds, q.SQL_TEXT AS session_sql
                FROM V$SESSION s LEFT JOIN V$SQL q ON q.SQL_ID = s.SQL_ID
                -- 同 MySQL/PostgreSQL：排除工具自己这条会话，否则自动刷新会让它常驻在
                -- 「正在执行」的第一条。
                WHERE s.TYPE = 'USER' AND s.SID <> SYS_CONTEXT('USERENV', 'SID')
                ORDER BY s.LAST_CALL_ET DESC
                """;
    }

    /**
     * Oracle 的编译错误。
     *
     * <p>这条是 Oracle 独有的必需品：{@code CREATE OR REPLACE PROCEDURE} 语法错了也会返回
     * 成功，对象以 INVALID 状态留在库里。{@code ALL_ERRORS} 才是真正的结果。</p>
     *
     * <p>{@code owner} 传 null 时落回当前 schema —— 建对象时没写 schema 就是建在自己名下。</p>
     *
     * <p>名字按大小写不敏感匹配：Oracle 把未加引号的标识符折成大写存进字典表，而用户在表单里
     * 填的通常是小写。按原样比对的话这条查询永远返回空 —— 于是「带编译错误创建成功」又变回
     * 一句干净的「已创建」，这个功能等于没做。</p>
     */
    @Override
    public String compilationErrorsSql() {
        return """
                SELECT LINE AS line, POSITION AS position, TEXT AS text
                FROM ALL_ERRORS
                WHERE UPPER(OWNER) = UPPER(NVL(?, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')))
                  AND UPPER(NAME) = UPPER(?) AND TYPE = ?
                ORDER BY SEQUENCE
                """;
    }

    /** 缓冲不打开，DBMS_OUTPUT.PUT_LINE 写进去的东西就直接丢了。 */
    @Override
    public String routineOutputEnableSql() {
        return "BEGIN DBMS_OUTPUT.ENABLE(1000000); END;";
    }

    @Override
    public String routineOutputFetchCall() {
        return "{call DBMS_OUTPUT.GET_LINE(?, ?)}";
    }

    /**
     * 谁在等谁。
     *
     * <p>{@code V$SESSION.BLOCKING_SESSION} 直接给出直接阻塞者，不必自己去 {@code V$LOCK} 里
     * 配对。{@code BLOCKING_SESSION_STATUS = 'VALID'} 这个条件不能省：它还有 UNKNOWN 与
     * NO HOLDER 这些取值，那时 BLOCKING_SESSION 里的数字不是一个真会话号。</p>
     *
     * <p>会话号用 SID，与 {@link #activeSessionsSql()} 保持同一套编号。</p>
     */
    @Override
    public String blockingSessionsSql() {
        return """
                SELECT s.SID AS blocked_session_id, s.BLOCKING_SESSION AS blocking_session_id,
                       o.OWNER || '.' || o.OBJECT_NAME AS wait_object,
                       s.SECONDS_IN_WAIT AS wait_seconds, q.SQL_TEXT AS blocked_sql
                FROM V$SESSION s
                LEFT JOIN ALL_OBJECTS o ON o.OBJECT_ID = s.ROW_WAIT_OBJ#
                LEFT JOIN V$SQL q ON q.SQL_ID = s.SQL_ID
                WHERE s.BLOCKING_SESSION IS NOT NULL AND s.BLOCKING_SESSION_STATUS = 'VALID'
                """;
    }

    @Override
    public boolean supportsKillSession() {
        return true;
    }

    @Override
    public String killSessionSql(String sessionId) {
        // Oracle 需要 SID,SERIAL#；这里只接受调用方传入的 "sid,serial" 组合。
        if (!sessionId.matches("\\d+,\\d+")) {
            throw new IllegalArgumentException("Oracle 会话标识需要 SID,SERIAL# 形式。");
        }
        return "ALTER SYSTEM KILL SESSION '" + sessionId + "' IMMEDIATE";
    }
}
