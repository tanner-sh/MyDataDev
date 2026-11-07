package com.example.dbadmin.core;

/** Oracle 系（Oracle、达梦、OceanBase Oracle 模式）：ALL_OBJECTS 列对象，DBMS_METADATA 取定义，名字按大写存进字典。 */
final class OracleObjectSql implements SchemaObjectSql {
    static final OracleObjectSql INSTANCE = new OracleObjectSql();

    private OracleObjectSql() {
    }

    @Override
    public Query listQuery(String schema, SchemaObjectKind kind) {
        return Query.of("""
                SELECT OWNER AS schema_name, OBJECT_NAME AS object_name,
                       TO_CHAR(OBJECT_ID) AS specific_name, '' AS subtype, STATUS AS object_status
                FROM ALL_OBJECTS
                WHERE OWNER = ? AND OBJECT_TYPE = ?
                """, SchemaObjectSql.upper(schema), SchemaObjectSql.keyword(kind));
    }

    @Override
    public Source source(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        return Source.scalar("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual",
                SchemaObjectSql.keyword(kind), SchemaObjectSql.upper(object.name()), SchemaObjectSql.upper(object.schemaName()));
    }

    @Override
    public Query dependenciesQuery(SchemaObjectKind kind, ObjectRef object) {
        return Query.of("""
                SELECT REFERENCED_OWNER, REFERENCED_NAME, REFERENCED_TYPE
                FROM ALL_DEPENDENCIES
                WHERE OWNER = ? AND NAME = ? AND TYPE = ?
                ORDER BY REFERENCED_OWNER, REFERENCED_NAME
                """, SchemaObjectSql.upper(object.schemaName()), SchemaObjectSql.upper(object.name()), SchemaObjectSql.keyword(kind));
    }

    @Override
    public String triggerTemplate(DatabaseDialect dialect, String schema, String name) {
        return "CREATE TRIGGER " + dialect.qualifiedName(schema, name) + "\nBEFORE INSERT ON <table>\nFOR EACH ROW\nBEGIN\n  NULL;\nEND;";
    }

    @Override
    public String procedureTemplate(String qualified) {
        return "CREATE PROCEDURE " + qualified + " AS\nBEGIN\n  NULL;\nEND;";
    }

    @Override
    public String functionTemplate(String qualified) {
        return "CREATE FUNCTION " + qualified + " RETURN NUMBER AS\nBEGIN\n  RETURN 1;\nEND;";
    }

    @Override
    public String refreshSql(DatabaseDialect dialect, ObjectRef object) {
        String name = object.schemaName() == null || object.schemaName().isBlank() ? object.name() : object.schemaName() + "." + object.name();
        return "BEGIN DBMS_MVIEW.REFRESH('" + name.replace("'", "''") + "'); END;";
    }

    @Override
    public boolean proceduralBodiesEndWithEnd() {
        return true;
    }

    @Override
    public ScriptBlockStyle scriptBlockStyle() {
        return ScriptBlockStyle.ORACLE;
    }
}
