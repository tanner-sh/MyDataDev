package com.example.dbadmin.core;

/** SQLite：只有视图与触发器，都在 sqlite_master 里，定义原文就存在 sql 列。 */
final class SqliteObjectSql implements SchemaObjectSql {
    static final SqliteObjectSql INSTANCE = new SqliteObjectSql();

    private SqliteObjectSql() {
    }

    @Override
    public Query listQuery(String schema, SchemaObjectKind kind) {
        if (kind != SchemaObjectKind.VIEW && kind != SchemaObjectKind.TRIGGER) return null;
        return Query.of("SELECT ? AS schema_name, name AS object_name, name AS specific_name, type AS subtype, '' AS object_status FROM sqlite_master WHERE type = ?",
                schema == null || schema.isBlank() ? "main" : schema, masterType(kind));
    }

    @Override
    public Source source(DatabaseDialect dialect, SchemaObjectKind kind, ObjectRef object) {
        return Source.scalar("SELECT sql FROM sqlite_master WHERE type = ? AND name = ?", masterType(kind), object.name());
    }

    @Override
    public String triggerTemplate(DatabaseDialect dialect, String schema, String name) {
        return "CREATE TRIGGER " + dialect.qualifiedName(schema, name) + " AFTER INSERT ON <table>\nBEGIN\n  SELECT 1;\nEND;";
    }

    private static String masterType(SchemaObjectKind kind) {
        return kind == SchemaObjectKind.TRIGGER ? "trigger" : "view";
    }
}
