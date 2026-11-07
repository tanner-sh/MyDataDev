package com.example.dbadmin.core;

/** SQL Server：sys 目录视图列对象，按 object_id 定位，OBJECT_DEFINITION 取定义，原地替换用 CREATE OR ALTER。 */
final class SqlServerObjectSql implements SchemaObjectSql {
    static final SqlServerObjectSql INSTANCE = new SqlServerObjectSql();

    private SqlServerObjectSql() {
    }

    @Override
    public Query listQuery(String schema, SchemaObjectKind kind) {
        return switch (kind) {
            case MATERIALIZED_VIEW -> null;
            case SEQUENCE -> Query.of("""
                    SELECT s.name AS schema_name, q.name AS object_name,
                           CONVERT(varchar(30), q.object_id) AS specific_name,
                           q.type_desc AS subtype, '' AS object_status
                    FROM sys.sequences q JOIN sys.schemas s ON s.schema_id = q.schema_id
                    WHERE s.name = ?
                    """, schema);
            case TRIGGER -> Query.of("""
                    SELECT s.name AS schema_name, o.name AS object_name,
                           CONVERT(varchar(30), o.object_id) AS specific_name,
                           OBJECT_NAME(t.parent_id) AS subtype,
                           CASE WHEN t.is_disabled = 1 THEN 'DISABLED' ELSE 'ENABLED' END AS object_status
                    FROM sys.triggers t
                    JOIN sys.objects o ON o.object_id = t.object_id
                    JOIN sys.schemas s ON s.schema_id = o.schema_id
                    WHERE s.name = ? AND t.parent_class = 1
                    """, schema);
            case VIEW, PROCEDURE, FUNCTION -> {
                String types = switch (kind) {
                    case VIEW -> "'V'";
                    case PROCEDURE -> "'P','PC'";
                    default -> "'FN','IF','TF','FS','FT'";
                };
                yield Query.of("SELECT s.name AS schema_name, o.name AS object_name, CONVERT(varchar(30), o.object_id) AS specific_name, '' AS subtype, '' AS object_status"
                        + " FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id WHERE s.name = ? AND o.type IN (" + types + ")", schema);
            }
        };
    }

    @Override
    public Source source(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        return Source.scalar("SELECT OBJECT_DEFINITION(?)", Integer.parseInt(object.specificName()));
    }

    @Override
    public Query dependenciesQuery(SchemaObjectKind kind, ObjectRef object) {
        return Query.of("""
                SELECT COALESCE(OBJECT_SCHEMA_NAME(d.referenced_id), d.referenced_schema_name),
                       COALESCE(OBJECT_NAME(d.referenced_id), d.referenced_entity_name),
                       COALESCE(o.type_desc, 'OBJECT')
                FROM sys.sql_expression_dependencies d
                LEFT JOIN sys.objects o ON o.object_id = d.referenced_id
                WHERE d.referencing_id = ?
                """, Integer.parseInt(object.specificName()));
    }

    @Override
    public String triggerTemplate(DatabaseDialect dialect, String schema, String name) {
        return "CREATE TRIGGER " + dialect.qualifiedName(schema, name) + " ON <table>\nAFTER INSERT AS\nBEGIN\n  SET NOCOUNT ON;\nEND;";
    }

    @Override
    public String procedureTemplate(String qualified) {
        return "CREATE PROCEDURE " + qualified + "\nAS\nBEGIN\n  SET NOCOUNT ON;\nEND;";
    }

    @Override
    public String functionTemplate(String qualified) {
        return "CREATE FUNCTION " + qualified + "() RETURNS int\nAS\nBEGIN\n  RETURN 1;\nEND;";
    }

    @Override
    public String triggerToggleSql(DatabaseDialect dialect, ObjectRef object, boolean enable) {
        return (enable ? "ENABLE" : "DISABLE") + " TRIGGER " + dialect.qualifiedName(object.schemaName(), object.name())
                + " ON " + SchemaObjectSql.triggerTable(dialect, object);
    }

    @Override
    public String replacePrefix() {
        return "CREATE OR ALTER ";
    }

    @Override
    public boolean proceduralBodiesEndWithEnd() {
        return true;
    }

    @Override
    public ScriptBlockStyle scriptBlockStyle() {
        return ScriptBlockStyle.SQL_SERVER;
    }
}
