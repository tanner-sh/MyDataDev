package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public interface DatabaseDialect {
    enum NamespaceKind {
        SCHEMA,
        CATALOG
    }

    record MetadataScope(String catalog, String schemaPattern) {
    }

    boolean supports(String dbType, String jdbcUrl);

    default DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, false, false, List.of());
    }

    String pageQuery(String baseSql, int limit, int offset);

    SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception;

    /**
     * 对一条候选查询做「只解析、不取数」的校验：能编译就正常返回，不能编译就抛出驱动的原始异常。
     *
     * <p>实现必须保证不会真正取业务行 —— AI 会拿模型刚生成、没人看过的 SQL 反复调用它，一条
     * 笛卡尔积在生产库上跑到底的代价，比校验本身大得多。</p>
     *
     * <p>默认实现只 prepare 再读结果列元数据。PostgreSQL、Oracle、SQL Server 的驱动会把它翻译
     * 成一次服务端 Describe，确实不执行；但 Connector/J 在默认的客户端预编译下，
     * {@code getMetaData()} 会另建一个语句把查询真跑一遍，所以 MySQL 系必须覆盖这个方法。新增
     * 方言时先确认驱动的行为，别默认继承。</p>
     */
    default void compileQuery(Connection connection, String sql, int timeoutSeconds) throws Exception {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            configureReadStatement(connection, statement, 0, timeoutSeconds);
            statement.getMetaData();
        }
    }

    default NamespaceKind namespaceKind() {
        return NamespaceKind.SCHEMA;
    }

    default String currentSchema(Connection connection) throws Exception {
        try {
            String schema = connection.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema;
            }
        } catch (SQLException | AbstractMethodError ignored) {
            // Some JDBC drivers do not implement Connection#getSchema.
        }
        try {
            String catalog = connection.getCatalog();
            return catalog == null || catalog.isBlank() ? null : catalog;
        } catch (SQLException ignored) {
            return null;
        }
    }

    default Optional<String> nativeDdl(Connection connection, String schemaName, String objectName, String objectType) throws Exception {
        return Optional.empty();
    }

    List<String> alterTableSql(String schemaName, String tableName, ObjectDetail original, TableDesignRequest design);

    List<String> createTableSql(String schemaName, String tableName, TableDesignRequest design);

    String renameTableSql(String schemaName, String tableName, String newTableName);

    String dropTableSql(String schemaName, String tableName);

    /**
     * 导入时遇到主键冲突怎么办：把一条批量 INSERT 改写成「跳过重复」或「更新已存在」。
     *
     * <p>导入最常见的场景恰恰是「上次导错了，改完再来一次」，而各家的写法完全不同 ——
     * MySQL 改的是语句前缀（{@code INSERT IGNORE}），PostgreSQL 加的是后缀
     * （{@code ON CONFLICT}），Oracle 要整条换成 {@code MERGE}。所以这里只声明形状，
     * 具体写法留给方言；不支持的方言返回 {@code null}，由上层给出一句说得清楚的错误，
     * 而不是生成一条跑不通的脚本。</p>
     *
     * @param keyColumns 冲突判定依据的列（目标表主键）；为空表示判断不了
     */
    default ImportConflictStyle importConflictStyle(String mode, List<String> columns, List<String> keyColumns) {
        return "INSERT".equalsIgnoreCase(mode) ? ImportConflictStyle.plain() : null;
    }

    /**
     * 一条批量 INSERT 的前后缀。
     *
     * @param insertKeyword 语句开头，通常是 {@code INSERT INTO}
     * @param conflictClause 值列表之后的子句，可能为空
     */
    record ImportConflictStyle(String insertKeyword, String conflictClause) {
        public static ImportConflictStyle plain() {
            return new ImportConflictStyle("INSERT INTO", "");
        }
    }

    /**
     * 这个方言能不能改列注释。
     *
     * <p>注释是这个产品最依赖的元数据（资源树、结构对比、AI 的结构搜索都在读它），却长期
     * 只读不可写。默认认为支持 —— 绝大多数关系库都有 {@code COMMENT ON} 或等价写法；
     * SQLite 根本没有注释，SQL Server 要走扩展属性，这两家显式关掉。</p>
     */
    default boolean supportsColumnComments() {
        return true;
    }

    /**
     * 把一个表达式转成文本，供大小写无关的模糊筛选使用。
     *
     * <p>结果网格的列筛选按文本语义工作（包含、等于、为空），而列本身可能是数字、时间或布尔。
     * 各家的转换写法差别不小 —— Oracle 是 {@code TO_CHAR}、SQL Server 要 {@code NVARCHAR(MAX)}、
     * ClickHouse 是 {@code toString} —— 所以收敛到方言里，而不是在筛选那边写一串 if。</p>
     */
    default String castToText(String expression) {
        return "CAST(" + expression + " AS VARCHAR(4000))";
    }

    /**
     * 把 SQL 里未加引号的标识符折成数据库实际存的那个形态。
     *
     * <p>只在「从 SQL 文本认出表名、再拿去查元数据」这条路上用得着：用户写
     * {@code select * from bd_accasoa}，Oracle 存的却是 {@code BD_ACCASOA}，按原样去
     * {@code DatabaseMetaData} 里查主键会一无所获 —— 于是一张有主键的表被报成「没有主键」。
     * 加了引号的标识符不能折，那是用户明确写下的大小写。</p>
     *
     * <p>默认按原样返回（MySQL 系存的就是建表时写的那个大小写，SQL Server 的元数据查询
     * 本身大小写不敏感）。</p>
     */
    default String foldUnquotedIdentifier(String identifier) {
        return identifier;
    }

    default String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    default String qualifiedName(String namespace, String objectName) {
        return namespace == null || namespace.isBlank()
                ? quoteIdentifier(objectName)
                : quoteIdentifier(namespace) + "." + quoteIdentifier(objectName);
    }

    default MetadataScope metadataScope(Connection connection, String namespace) throws SQLException {
        if (namespaceKind() == NamespaceKind.CATALOG) {
            String catalog = namespace == null || namespace.isBlank() ? connection.getCatalog() : namespace;
            return new MetadataScope(catalog, null);
        }
        return new MetadataScope(connection.getCatalog(), namespace == null || namespace.isBlank() ? null : namespace);
    }

    default void activateNamespace(Connection connection, String namespace) throws SQLException {
        if (namespace == null || namespace.isBlank()) return;
        if (namespaceKind() == NamespaceKind.CATALOG) {
            connection.setCatalog(namespace);
        } else {
            String current = null;
            try {
                current = currentSchema(connection);
            } catch (Exception ignored) {
                // Let setSchema provide the authoritative support/error result.
            }
            if (namespace.equals(current)) return;
            connection.setSchema(namespace);
        }
    }

    default String resultNamespace(ResultSet resultSet) throws SQLException {
        return resultSet.getString(namespaceKind() == NamespaceKind.CATALOG ? "TABLE_CAT" : "TABLE_SCHEM");
    }

    default String paginationHelperColumn() {
        return null;
    }

    /**
     * 目标库上的活动会话。
     *
     * <p>返回 {@code null} 表示该方言不支持 —— 界面据此隐藏会话面板，而不是报一个看不懂的
     * SQL 错误。语句必须返回 sessionId / user / host / database / state / command /
     * durationSeconds / sql 这几列（缺的用 NULL 占位），由 SessionService 统一读取。</p>
     */
    /**
     * 对象创建后的编译错误。
     *
     * <p>返回 {@code null} 表示这个数据库不需要这一步：绝大多数数据库的 CREATE 语法有问题时
     * 直接抛异常。Oracle 是例外 —— {@code CREATE OR REPLACE PROCEDURE} 会「带编译错误创建
     * 成功」，语句本身不报错，对象留在库里但状态是 INVALID。不查这张字典表的话，用户看到的
     * 是一句干净的「创建成功」，直到某天有人调用它才发现是坏的。</p>
     *
     * <p>语句按顺序接三个参数：owner/schema（可为 null，表示当前 schema）、对象名、对象类型
     * （PROCEDURE / FUNCTION / TRIGGER / VIEW 这样的字面量）。返回列：line、position、text。</p>
     */
    default String compilationErrorsSql() {
        return null;
    }

    /**
     * 调用例程前要执行的一条语句，用来打开服务端的输出缓冲。
     *
     * <p>只有 Oracle 需要：{@code DBMS_OUTPUT} 不显式 ENABLE 就什么都收不到，而 PL/SQL 里
     * 排查问题基本全靠它。其余数据库的 {@code RAISE NOTICE} / 警告走 JDBC 的 SQLWarning，
     * 不需要这一步。</p>
     */
    default String routineOutputEnableSql() {
        return null;
    }

    /**
     * 读回一行例程输出的调用语句：第一个输出参数是文本，第二个是状态码（非 0 表示读完了）。
     *
     * <p>这个形状是照 Oracle 的 {@code DBMS_OUTPUT.GET_LINE} 定的，因为只有它用「攒在服务端
     * 缓冲区、事后一行行取」这种机制。为它抽一层 Provider 比问题本身还重。</p>
     */
    default String routineOutputFetchCall() {
        return null;
    }

    /**
     * 会话之间的阻塞关系：谁在等谁。
     *
     * <p>会话列表能让人杀掉一个会话，却说不出该杀哪一个 —— 中间这一步才是排查锁等待时真正
     * 要的东西。返回 {@code null} 表示该方言暂不支持，界面据此说明原因而不是报 SQL 错误。</p>
     *
     * <p>语句必须返回 blocked_session_id / blocking_session_id / wait_object / wait_seconds /
     * blocked_sql 这几列（缺的用 NULL 占位）。<b>两个 session id 必须与
     * {@link #activeSessionsSql()} 的 session_id 是同一套编号</b>，否则阻塞关系和会话列表拼
     * 不到一起，界面上会出现一堆连用户名都显示不出来的孤立节点。</p>
     */
    default String blockingSessionsSql() {
        return null;
    }

    default String activeSessionsSql() {
        return null;
    }

    /**
     * 终止一个会话的语句。会话 id 由调用方校验成纯数字或原样透传，实现负责拼装。
     *
     * <p>返回 {@code null} 表示该方言不支持终止。</p>
     */
    /**
     * 是否支持终止会话。
     *
     * <p>单独给一个能力位，而不是拿一个假的会话号去调 {@link #killSessionSql} 试探 —— Oracle
     * 的会话标识是 {@code SID,SERIAL#} 形式，任何不合法的输入都会抛异常，试探会把整个活动
     * 会话列表带崩。</p>
     */
    default boolean supportsKillSession() {
        return false;
    }

    default String killSessionSql(String sessionId) {
        return null;
    }

    default void configureReadStatement(Connection connection, Statement statement, int fetchSize, int timeoutSeconds) throws SQLException {
        if (timeoutSeconds > 0) {
            try {
                statement.setQueryTimeout(timeoutSeconds);
            } catch (SQLFeatureNotSupportedException ignored) {
                // Some otherwise usable JDBC drivers do not implement timeouts.
            }
        }
        if (fetchSize > 0) {
            try {
                statement.setFetchSize(fetchSize);
            } catch (SQLFeatureNotSupportedException ignored) {
                // Fetch size is a hint and may be unsupported by a driver.
            }
        }
    }

    default void configureStreamingStatement(Connection connection, Statement statement, int fetchSize, int timeoutSeconds) throws SQLException {
        configureReadStatement(connection, statement, fetchSize, timeoutSeconds);
    }

    default String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    /**
     * 写进「将来会被真正执行的脚本」里的字面量。
     *
     * <p>与 {@link #literal} 的区别只在于取舍：{@code literal} 服务于预览，可读性优先；这个
     * 方法服务于生成的导入脚本，正确性优先 —— 脚本可能在另一台机器、另一个会话设置下执行，
     * 所以它必须选一种不依赖会话状态的写法，哪怕不好看。默认两者相同，只有转义规则会随会话
     * 变化的方言（MySQL 系）才需要区分。</p>
     */
    default String scriptLiteral(Object value) {
        if (value instanceof byte[] bytes) return scriptBinaryLiteral(bytes);
        return literal(value);
    }

    /** Binary literal used in generated scripts. The default is the SQL-standard form. */
    default String scriptBinaryLiteral(byte[] value) {
        return "X'" + HexFormat.of().formatHex(value) + "'";
    }
}
