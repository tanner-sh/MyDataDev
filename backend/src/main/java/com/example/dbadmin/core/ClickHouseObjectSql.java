package com.example.dbadmin.core;

/** ClickHouse：视图与物化视图在 system.tables 里按引擎区分；SQL UDF 不属于任何库。 */
final class ClickHouseObjectSql implements SchemaObjectSql {
    static final ClickHouseObjectSql INSTANCE = new ClickHouseObjectSql();

    private ClickHouseObjectSql() {
    }

    @Override
    public Query listQuery(String schema, SchemaObjectKind kind) {
        return switch (kind) {
            case VIEW, MATERIALIZED_VIEW -> Query.of("SELECT database AS schema_name, name AS object_name, toString(uuid) AS specific_name, engine AS subtype, '' AS object_status FROM system.tables WHERE database = ? AND engine = ?",
                    schema, kind == SchemaObjectKind.VIEW ? "View" : "MaterializedView");
            case FUNCTION -> Query.of("SELECT '' AS schema_name, name AS object_name, name AS specific_name, origin AS subtype, '' AS object_status FROM system.functions WHERE origin = 'SQLUserDefined'");
            default -> null;
        };
    }

    /** ClickHouse 没有 ESCAPE 子句；positionCaseInsensitive 本身就是不带通配符的子串匹配。 */
    @Override
    public Query keywordFilter(String keyword) {
        return Query.of("positionCaseInsensitive(object_name, ?) > 0", keyword);
    }

    @Override
    public Source source(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        return Source.showCreate("SHOW CREATE " + (kind == SchemaObjectKind.FUNCTION ? "FUNCTION" : "TABLE") + " "
                + qualifiedObjectName(dialect, kind, object.schemaName(), object.name()));
    }

    @Override
    public String functionTemplate(String qualified) {
        return "CREATE FUNCTION " + qualified + " AS () -> 1;";
    }

    @Override
    public String refreshSql(DatabaseDialect dialect, ObjectRef object) {
        return "SYSTEM REFRESH VIEW " + dialect.qualifiedName(object.schemaName(), object.name());
    }

    @Override
    public boolean functionsAreGlobal() {
        return true;
    }

    @Override
    public boolean invokesRoutinesAsQuery() {
        return true;
    }

    @Override
    public boolean refreshRequiresRefreshClause() {
        return true;
    }
}
