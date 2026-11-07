package com.example.dbadmin.core;

/** H2：INFORMATION_SCHEMA 列对象；函数是 Java 别名（CREATE ALIAS），参数登记在过程元数据里。 */
final class H2ObjectSql implements SchemaObjectSql {
    static final H2ObjectSql INSTANCE = new H2ObjectSql();

    private H2ObjectSql() {
    }

    @Override
    public Query listQuery(String schema, SchemaObjectKind kind) {
        String owner = SchemaObjectSql.upper(schema);
        return switch (kind) {
            case VIEW -> Query.of("SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME AS object_name, TABLE_NAME AS specific_name, '' AS subtype, '' AS object_status FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ?", owner);
            case SEQUENCE -> Query.of("SELECT SEQUENCE_SCHEMA AS schema_name, SEQUENCE_NAME AS object_name, SEQUENCE_NAME AS specific_name, DATA_TYPE AS subtype, '' AS object_status FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ?", owner);
            case TRIGGER -> Query.of("SELECT TRIGGER_SCHEMA AS schema_name, TRIGGER_NAME AS object_name, TRIGGER_NAME AS specific_name, EVENT_OBJECT_TABLE AS subtype, '' AS object_status FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = ?", owner);
            case FUNCTION -> Query.of("SELECT ROUTINE_SCHEMA AS schema_name, ROUTINE_NAME AS object_name, SPECIFIC_NAME AS specific_name, '' AS subtype, '' AS object_status FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'FUNCTION'", owner);
            default -> null;
        };
    }

    @Override
    public Source source(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        String schema = SchemaObjectSql.upper(object.schemaName());
        String name = SchemaObjectSql.upper(object.name());
        String qualified = dialect.qualifiedName(object.schemaName(), object.name());
        return switch (kind) {
            case VIEW -> Source.scalar("SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", schema, name)
                    .wrap("CREATE OR REPLACE VIEW " + qualified + " AS\n", ";");
            case SEQUENCE -> Source.scalar("""
                    SELECT 'CREATE SEQUENCE ' || SEQUENCE_SCHEMA || '.' || SEQUENCE_NAME
                           || ' START WITH ' || START_VALUE || ' INCREMENT BY ' || INCREMENT || ';'
                    FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ? AND SEQUENCE_NAME = ?
                    """, schema, name);
            case TRIGGER -> Source.scalar("SELECT ACTION_STATEMENT FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = ? AND TRIGGER_NAME = ?", schema, name);
            case FUNCTION -> Source.scalar("SELECT ROUTINE_DEFINITION FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ? AND SPECIFIC_NAME = ?", schema, object.specificName())
                    .wrap("CREATE ALIAS " + qualified + " AS $$\n", "\n$$;");
            default -> null;
        };
    }

    @Override
    public String triggerTemplate(DatabaseDialect dialect, String schema, String name) {
        return "CREATE TRIGGER " + dialect.qualifiedName(schema, name) + " BEFORE INSERT ON <table>\nFOR EACH ROW CALL '<java.class.Name>';";
    }

    @Override
    public String functionTemplate(String qualified) {
        return "CREATE ALIAS " + qualified + " AS $$\nint value() { return 1; }\n$$;";
    }

    @Override
    public boolean functionParametersFromProcedureMetadata() {
        return true;
    }

    @Override
    public boolean specificNameIdentifiesOverload() {
        return true;
    }

    @Override
    public boolean functionsMayBeAliases() {
        return true;
    }
}
