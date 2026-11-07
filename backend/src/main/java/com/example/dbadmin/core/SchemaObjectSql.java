package com.example.dbadmin.core;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 可编程对象（视图、物化视图、序列、触发器、存储过程、函数）在一种数据库上的写法：
 * 怎么列、怎么取定义、怎么查依赖、模板长什么样、删除 / 刷新 / 启停的语句，以及几处语法差异。
 *
 * <p>由 {@link DatabaseDialect#schemaObjects()} 给出。原来这些全在 {@code SchemaObjectCatalog} 和
 * {@code SchemaObjectService} 里按数据库类型串分支（五十多处 {@code switch (family)}），而能力矩阵
 * （{@link SchemaObjectCapabilities}）早就挂在方言上 —— 「能做什么」和「怎么做」分在两处，按的还是两套判定
 * （一个按方言、一个按类型串），新增一种数据库要两边都改。现在两者都从方言出，{@code SchemaObjectCatalog}
 * 只负责执行、分页和读 CLOB。</p>
 *
 * <p>默认实现就是「不支持对象目录」（{@link #NONE}），各项写法的默认值取最常见的那一种（多为 MySQL 系），
 * 只有和它不同的数据库才覆盖。</p>
 */
public interface SchemaObjectSql {
    SchemaObjectSql NONE = new SchemaObjectSql() {
    };

    /** 一条带绑定参数的查询。 */
    record Query(String sql, List<Object> parameters) {
        public Query {
            sql = sql.strip();
            parameters = List.copyOf(parameters);
        }

        public static Query of(String sql, Object... parameters) {
            return new Query(sql, Arrays.asList(parameters));
        }
    }

    /** 目录里的一个对象：限定名、能唯一定位它的 specificName（OID、OBJECT_ID、重载签名……）、子类型（触发器所属的表等）。 */
    record ObjectRef(String schemaName, String name, String specificName, String subtype) {
    }

    /**
     * 取对象定义的方式。{@code showCreate} 为真时执行 {@code sql}（无参数）并在结果里找以 CREATE 开头的那一列，
     * 否则取第一行第一列。取到值以后才拼上 prefix / suffix —— 取不到就是取不到，不能拼出一个空壳定义。
     */
    record Source(String sql, List<Object> parameters, boolean showCreate, String prefix, String suffix) {
        public Source {
            parameters = List.copyOf(parameters);
            prefix = prefix == null ? "" : prefix;
            suffix = suffix == null ? "" : suffix;
        }

        public static Source scalar(String sql, Object... parameters) {
            return new Source(sql.strip(), Arrays.asList(parameters), false, "", "");
        }

        public static Source showCreate(String sql) {
            return new Source(sql, List.of(), true, "", "");
        }

        public Source wrap(String prefix, String suffix) {
            return new Source(sql, parameters, showCreate, prefix, suffix);
        }
    }

    /** 导出脚本里过程体的收尾写法（{@code SchemaExportScript.statement}）。 */
    enum ScriptBlockStyle {
        /** 每条以分号结束。 */
        PLAIN,
        /** PL/SQL 块：END …; 后面独占一行的 /。 */
        ORACLE,
        /** 过程体内有分号：外面包 DELIMITER ;;。 */
        MYSQL,
        /** 每条后面一行 GO。 */
        SQL_SERVER
    }

    // ---- 目录查询 ----

    /**
     * 列出一个 Schema 下某类对象的查询；结果五列依次为 schema_name、object_name、specific_name、subtype、
     * object_status（列名必须是这几个，外面会再包一层分页与过滤）。返回 null 表示这种数据库没有这类对象。
     */
    default Query listQuery(String schema, SchemaObjectKind kind) {
        return null;
    }

    /** 按名称关键字过滤的条件（拼在外层 WHERE 后面），默认大小写不敏感的 LIKE。 */
    default Query keywordFilter(String keyword) {
        String escaped = keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return Query.of("LOWER(object_name) LIKE ? ESCAPE '!'", "%" + escaped + "%");
    }

    /** 取定义；返回 null 表示没有办法取（外面会报「数据库未返回对象源码」）。 */
    default Source source(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        return null;
    }

    /** 查依赖：三列依次为被依赖对象的 Schema、名称、类型。返回 null 表示不支持。 */
    default Query dependenciesQuery(SchemaObjectKind kind, ObjectRef object) {
        return null;
    }

    // ---- 生成的语句 ----

    default String template(DatabaseDialect dialect, SchemaObjectKind kind, String schema, String name) {
        String qualified = qualifiedObjectName(dialect, kind, schema, name);
        return switch (kind) {
            case VIEW -> "CREATE VIEW " + qualified + " AS\nSELECT 1 AS example_value;";
            case MATERIALIZED_VIEW -> "CREATE MATERIALIZED VIEW " + qualified + " AS\nSELECT 1 AS example_value;";
            case SEQUENCE -> "CREATE SEQUENCE " + qualified + "\n  START WITH 1\n  INCREMENT BY 1;";
            case TRIGGER -> triggerTemplate(dialect, schema, name);
            case PROCEDURE -> procedureTemplate(qualified);
            case FUNCTION -> functionTemplate(qualified);
        };
    }

    default String triggerTemplate(DatabaseDialect dialect, String schema, String name) {
        return "CREATE TRIGGER " + dialect.qualifiedName(schema, name) + " BEFORE INSERT ON <table>\nFOR EACH ROW\nSET @schema_object_trigger = 1;";
    }

    default String procedureTemplate(String qualified) {
        return "CREATE PROCEDURE " + qualified + "()\nBEGIN\n  SELECT 1;\nEND;";
    }

    default String functionTemplate(String qualified) {
        return "CREATE FUNCTION " + qualified + "() RETURNS INTEGER DETERMINISTIC\nRETURN 1;";
    }

    default String dropSql(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        return "DROP " + keyword(kind) + " " + dialect.qualifiedName(object.schemaName(), object.name());
    }

    default String refreshSql(DatabaseDialect dialect, ObjectRef object) {
        return "REFRESH MATERIALIZED VIEW " + dialect.qualifiedName(object.schemaName(), object.name());
    }

    default String triggerToggleSql(DatabaseDialect dialect, ObjectRef object, boolean enable) {
        return "ALTER TRIGGER " + dialect.qualifiedName(object.schemaName(), object.name()) + (enable ? " ENABLE" : " DISABLE");
    }

    /** 把「取到的 CREATE …」改成能原地替换的写法时用的前缀。 */
    default String replacePrefix() {
        return "CREATE OR REPLACE ";
    }

    // ---- 语法差异 ----

    /** 函数不属于任何 Schema（ClickHouse 的 SQL UDF）：名字不加限定，列表里 Schema 为空。 */
    default boolean functionsAreGlobal() {
        return false;
    }

    /** 函数的参数要从 getProcedureColumns 读（H2 的函数在 JDBC 元数据里登记成过程）。 */
    default boolean functionParametersFromProcedureMetadata() {
        return false;
    }

    /** 参数元数据里的 SPECIFIC_NAME 能区分重载，要按它挑出属于这个对象的那几行。 */
    default boolean specificNameIdentifiesOverload() {
        return false;
    }

    /** 例程按查询调用（SELECT * FROM f(?) / CALL p(?)），而不是 JDBC 的 {call …} 转义语法。 */
    default boolean invokesRoutinesAsQuery() {
        return false;
    }

    /** 执行对象 DDL 前要先切到对象所在的库：源码里的名字可以不带库名（MySQL 系），按当前库解析。 */
    default boolean definitionsResolveInCurrentNamespace() {
        return false;
    }

    /** 函数可以用 CREATE ALIAS 定义（H2）。 */
    default boolean functionsMayBeAliases() {
        return false;
    }

    /** 过程体以 BEGIN … END 包住、内部带分号：整段以 END 收尾时算一条定义。 */
    default boolean proceduralBodiesEndWithEnd() {
        return false;
    }

    /** 物化视图只有定义里写了 REFRESH 子句才能手动刷新（ClickHouse 的可刷新物化视图）。 */
    default boolean refreshRequiresRefreshClause() {
        return false;
    }

    /** SHOW CREATE 出来的定义带着 DEFINER=账号（MySQL 系），跨环境导出时默认去掉。 */
    default boolean definitionsCarryDefiner() {
        return false;
    }

    default ScriptBlockStyle scriptBlockStyle() {
        return ScriptBlockStyle.PLAIN;
    }

    // ---- 共用的小工具 ----

    default String qualifiedObjectName(DatabaseDialect dialect, SchemaObjectKind kind, String schema, String name) {
        return functionsAreGlobal() && kind == SchemaObjectKind.FUNCTION ? dialect.quoteIdentifier(name) : dialect.qualifiedName(schema, name);
    }

    static String keyword(SchemaObjectKind kind) {
        return kind == SchemaObjectKind.MATERIALIZED_VIEW ? "MATERIALIZED VIEW" : kind.name();
    }

    /** 触发器所属的表：目录查询把它放在 subtype 里（MySQL 系是「BEFORE INSERT ON t」，取 ON 后面那段）。 */
    static String triggerTable(DatabaseDialect dialect, ObjectRef object) {
        String table = object.subtype() == null ? "" : object.subtype();
        int on = table.toUpperCase(Locale.ROOT).lastIndexOf(" ON ");
        if (on >= 0) table = table.substring(on + 4);
        if (table.isBlank()) throw new IllegalStateException("触发器缺少所属表信息，无法生成安全操作 SQL。");
        return dialect.qualifiedName(object.schemaName(), table);
    }

    static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
