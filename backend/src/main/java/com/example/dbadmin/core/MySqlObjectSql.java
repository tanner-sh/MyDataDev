package com.example.dbadmin.core;

/** MySQL 系（MySQL、MariaDB、OceanBase MySQL 模式）：INFORMATION_SCHEMA 列对象，SHOW CREATE 取定义。只有 MariaDB 有序列。 */
final class MySqlObjectSql implements SchemaObjectSql {
    static final MySqlObjectSql MYSQL = new MySqlObjectSql(false);
    static final MySqlObjectSql MARIADB = new MySqlObjectSql(true);

    private final boolean sequences;

    private MySqlObjectSql(boolean sequences) {
        this.sequences = sequences;
    }

    @Override
    public Query listQuery(String schema, SchemaObjectKind kind) {
        return switch (kind) {
            case VIEW -> Query.of("SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME AS object_name, TABLE_NAME AS specific_name, CHECK_OPTION AS subtype, '' AS object_status FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ?", schema);
            case MATERIALIZED_VIEW -> null;
            case SEQUENCE -> sequences
                    ? Query.of("SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME AS object_name, TABLE_NAME AS specific_name, TABLE_TYPE AS subtype, '' AS object_status FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'SEQUENCE'", schema)
                    : null;
            case TRIGGER -> Query.of("SELECT TRIGGER_SCHEMA AS schema_name, TRIGGER_NAME AS object_name, TRIGGER_NAME AS specific_name, CONCAT(ACTION_TIMING, ' ', EVENT_MANIPULATION, ' ON ', EVENT_OBJECT_TABLE) AS subtype, '' AS object_status FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = ?", schema);
            case PROCEDURE, FUNCTION -> Query.of("SELECT ROUTINE_SCHEMA AS schema_name, ROUTINE_NAME AS object_name, SPECIFIC_NAME AS specific_name, '' AS subtype, ROUTINE_TYPE AS object_status FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = ?", schema, kind.name());
        };
    }

    @Override
    public Source source(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        String keyword = kind == SchemaObjectKind.MATERIALIZED_VIEW ? "VIEW" : SchemaObjectSql.keyword(kind);
        return Source.showCreate("SHOW CREATE " + keyword + " " + dialect.qualifiedName(object.schemaName(), object.name()));
    }

    @Override
    public boolean specificNameIdentifiesOverload() {
        return true;
    }

    @Override
    public boolean definitionsResolveInCurrentNamespace() {
        return true;
    }

    @Override
    public boolean proceduralBodiesEndWithEnd() {
        return true;
    }

    @Override
    public boolean definitionsCarryDefiner() {
        return true;
    }

    @Override
    public ScriptBlockStyle scriptBlockStyle() {
        return ScriptBlockStyle.MYSQL;
    }
}
