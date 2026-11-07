package com.example.dbadmin.core;

/** PostgreSQL：对象按 OID 定位，定义由 pg_get_*def 现生成，函数按参数签名区分重载。 */
final class PostgreSqlObjectSql implements SchemaObjectSql {
    static final PostgreSqlObjectSql INSTANCE = new PostgreSqlObjectSql();

    private PostgreSqlObjectSql() {
    }

    @Override
    public Query listQuery(String schema, SchemaObjectKind kind) {
        return switch (kind) {
            case VIEW, MATERIALIZED_VIEW, SEQUENCE -> Query.of("""
                    SELECT n.nspname AS schema_name, c.relname AS object_name,
                           c.oid::text AS specific_name, '' AS subtype, '' AS object_status
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relkind = ?
                    """, schema, kind == SchemaObjectKind.VIEW ? "v" : kind == SchemaObjectKind.MATERIALIZED_VIEW ? "m" : "S");
            case TRIGGER -> Query.of("""
                    SELECT n.nspname AS schema_name, t.tgname AS object_name,
                           t.oid::text AS specific_name, c.relname AS subtype,
                           CASE t.tgenabled WHEN 'D' THEN 'DISABLED' ELSE 'ENABLED' END AS object_status
                    FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND NOT t.tgisinternal
                    """, schema);
            case PROCEDURE, FUNCTION -> Query.of("""
                    SELECT n.nspname AS schema_name, p.proname AS object_name,
                           p.oid::text AS specific_name,
                           pg_get_function_identity_arguments(p.oid) AS subtype, '' AS object_status
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ? AND p.prokind = ?
                    """, schema, kind == SchemaObjectKind.PROCEDURE ? "p" : "f");
        };
    }

    @Override
    public Source source(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        long oid = Long.parseLong(object.specificName());
        String qualified = dialect.qualifiedName(object.schemaName(), object.name());
        return switch (kind) {
            case VIEW -> Source.scalar("SELECT pg_get_viewdef(?::oid, true)", oid).wrap("CREATE OR REPLACE VIEW " + qualified + " AS\n", ";");
            case MATERIALIZED_VIEW -> Source.scalar("SELECT pg_get_viewdef(?::oid, true)", oid).wrap("CREATE MATERIALIZED VIEW " + qualified + " AS\n", ";");
            case TRIGGER -> Source.scalar("SELECT pg_get_triggerdef(?::oid, true)", oid).wrap("", ";");
            case PROCEDURE, FUNCTION -> Source.scalar("SELECT pg_get_functiondef(?::oid)", oid);
            case SEQUENCE -> Source.scalar("""
                    SELECT 'CREATE SEQUENCE ' || quote_ident(schemaname) || '.' || quote_ident(sequencename)
                           || ' AS ' || data_type || ' START WITH ' || start_value
                           || ' INCREMENT BY ' || increment_by || ' MINVALUE ' || min_value
                           || ' MAXVALUE ' || max_value || ' CACHE ' || cache_size
                           || CASE WHEN cycle THEN ' CYCLE' ELSE ' NO CYCLE' END || ';'
                    FROM pg_sequences WHERE schemaname = ? AND sequencename = ?
                    """, object.schemaName(), object.name());
        };
    }

    @Override
    public Query dependenciesQuery(SchemaObjectKind kind, ObjectRef object) {
        return Query.of("""
                SELECT COALESCE(n.nspname, ''), COALESCE(c.relname, p.proname, ''),
                       CASE WHEN c.relkind = 'v' THEN 'VIEW'
                            WHEN c.relkind = 'm' THEN 'MATERIALIZED_VIEW'
                            WHEN c.relkind = 'S' THEN 'SEQUENCE'
                            WHEN p.prokind = 'p' THEN 'PROCEDURE'
                            WHEN p.oid IS NOT NULL THEN 'FUNCTION' ELSE 'OBJECT' END
                FROM pg_depend d
                LEFT JOIN pg_class c ON c.oid = d.refobjid
                LEFT JOIN pg_proc p ON p.oid = d.refobjid
                LEFT JOIN pg_namespace n ON n.oid = COALESCE(c.relnamespace, p.pronamespace)
                WHERE d.objid = ? AND d.deptype = 'n' AND (c.oid IS NOT NULL OR p.oid IS NOT NULL)
                """, Long.parseLong(object.specificName()));
    }

    /** PostgreSQL 的触发器挂在表上，名字不带 Schema。 */
    @Override
    public String triggerTemplate(DatabaseDialect dialect, String schema, String name) {
        return "CREATE TRIGGER " + dialect.quoteIdentifier(name) + "\nBEFORE INSERT ON <table>\nFOR EACH ROW EXECUTE FUNCTION <function>();";
    }

    @Override
    public String procedureTemplate(String qualified) {
        return "CREATE PROCEDURE " + qualified + "()\nLANGUAGE plpgsql\nAS $$\nBEGIN\n  NULL;\nEND;\n$$;";
    }

    @Override
    public String functionTemplate(String qualified) {
        return "CREATE FUNCTION " + qualified + "() RETURNS integer\nLANGUAGE sql\nAS $$ SELECT 1 $$;";
    }

    /** 触发器要带表名删；函数与过程要带参数签名，否则有重载时删不掉（或删错）。 */
    @Override
    public String dropSql(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        if (kind == SchemaObjectKind.TRIGGER) {
            return "DROP TRIGGER " + dialect.quoteIdentifier(object.name()) + " ON " + SchemaObjectSql.triggerTable(dialect, object);
        }
        if (kind == SchemaObjectKind.FUNCTION || kind == SchemaObjectKind.PROCEDURE) {
            return "DROP " + SchemaObjectSql.keyword(kind) + " " + dialect.qualifiedName(object.schemaName(), object.name())
                    + "(" + (object.subtype() == null ? "" : object.subtype()) + ")";
        }
        return SchemaObjectSql.super.dropSql(dialect, kind, object);
    }

    @Override
    public String triggerToggleSql(DatabaseDialect dialect, ObjectRef object, boolean enable) {
        return "ALTER TABLE " + SchemaObjectSql.triggerTable(dialect, object) + " " + (enable ? "ENABLE" : "DISABLE")
                + " TRIGGER " + dialect.quoteIdentifier(object.name());
    }

    @Override
    public boolean specificNameIdentifiesOverload() {
        return true;
    }

    @Override
    public boolean invokesRoutinesAsQuery() {
        return true;
    }
}
