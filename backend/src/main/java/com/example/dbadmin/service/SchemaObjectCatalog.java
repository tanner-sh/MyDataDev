package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.core.SchemaObjectOperation;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDependency;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectParameter;
import com.example.dbadmin.model.DbConnection;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class SchemaObjectCatalog {
    private static final int MAX_QUERY_ROWS = 20_001;

    public record CatalogObject(String schemaName, String name, String specificName, String subtype, String status) {
    }

    public List<CatalogObject> list(Connection connection, DbConnection configured, String schema, SchemaObjectKind kind) throws Exception {
        String family = family(configured);
        List<CatalogObject> objects = switch (family) {
            case "postgresql" -> listPostgreSql(connection, schema, kind);
            case "oracle" -> listOracle(connection, schema, kind);
            case "sqlserver" -> listSqlServer(connection, schema, kind);
            case "sqlite" -> listSqlite(connection, schema, kind);
            case "h2" -> listH2(connection, schema, kind);
            case "clickhouse" -> listClickHouse(connection, schema, kind);
            case "mysql", "mariadb" -> listMySql(connection, schema, kind, family.equals("mariadb"));
            default -> List.of();
        };
        return objects.stream()
                .sorted(Comparator.comparing(CatalogObject::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(object -> value(object.specificName())))
                .toList();
    }

    public String source(Connection connection, DbConnection configured, DatabaseDialect dialect, SchemaObjectKind kind, CatalogObject object) throws Exception {
        String family = family(configured);
        String source = switch (family) {
            case "postgresql" -> postgreSqlSource(connection, dialect, kind, object);
            case "oracle" -> oracleSource(connection, kind, object);
            case "sqlserver" -> scalar(connection, "SELECT OBJECT_DEFINITION(?)", integerIdentity(object));
            case "sqlite" -> scalar(connection, "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?", sqliteType(kind), object.name());
            case "h2" -> h2Source(connection, dialect, kind, object);
            case "clickhouse" -> showCreate(connection, "SHOW CREATE " + clickHouseKeyword(kind) + " "
                    + (kind == SchemaObjectKind.FUNCTION ? dialect.quoteIdentifier(object.name()) : dialect.qualifiedName(object.schemaName(), object.name())));
            case "mysql", "mariadb" -> showCreate(connection, "SHOW CREATE " + mysqlKeyword(kind) + " " + dialect.qualifiedName(object.schemaName(), object.name()));
            default -> null;
        };
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("数据库未返回对象源码，可能缺少查看定义的权限。");
        }
        return source.trim();
    }

    public List<SchemaObjectParameter> parameters(Connection connection, DbConnection configured, DatabaseDialect dialect, SchemaObjectKind kind, CatalogObject object) throws Exception {
        if (kind != SchemaObjectKind.PROCEDURE && kind != SchemaObjectKind.FUNCTION) return List.of();
        DatabaseMetaData metadata = connection.getMetaData();
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, object.schemaName());
        boolean procedureMetadata = kind == SchemaObjectKind.PROCEDURE
                || (kind == SchemaObjectKind.FUNCTION && family(configured).equals("h2"));
        List<SchemaObjectParameter> parameters = new ArrayList<>();
        try (ResultSet rs = procedureMetadata
                ? metadata.getProcedureColumns(scope.catalog(), scope.schemaPattern(), object.name(), "%")
                : metadata.getFunctionColumns(scope.catalog(), scope.schemaPattern(), object.name(), "%")) {
            while (rs.next()) {
                String specificName = optionalString(rs, "SPECIFIC_NAME");
                if (!matchesSpecific(configured, object.specificName(), specificName)) continue;
                int columnType = rs.getInt("COLUMN_TYPE");
                String mode = parameterMode(procedureMetadata ? SchemaObjectKind.PROCEDURE : kind, columnType);
                if (mode == null) continue;
                int position = optionalInt(rs, "ORDINAL_POSITION", parameters.size() + 1);
                int jdbcType = optionalInt(rs, "DATA_TYPE", Types.VARCHAR);
                int nullableCode = optionalInt(rs, "NULLABLE", DatabaseMetaData.procedureNullableUnknown);
                parameters.add(new SchemaObjectParameter(
                        position,
                        optionalString(rs, "COLUMN_NAME"),
                        mode,
                        optionalString(rs, "TYPE_NAME"),
                        jdbcType,
                        nullableCode != DatabaseMetaData.procedureNoNulls
                ));
            }
        }
        return parameters.stream()
                .sorted(Comparator.comparingInt(SchemaObjectParameter::position)
                        .thenComparing(parameter -> value(parameter.name())))
                .toList();
    }

    public List<SchemaObjectDependency> dependencies(Connection connection, DbConnection configured, SchemaObjectKind kind, CatalogObject object) throws Exception {
        return switch (family(configured)) {
            case "oracle" -> query(connection, """
                    SELECT REFERENCED_OWNER, REFERENCED_NAME, REFERENCED_TYPE
                    FROM ALL_DEPENDENCIES
                    WHERE OWNER = ? AND NAME = ? AND TYPE = ?
                    ORDER BY REFERENCED_OWNER, REFERENCED_NAME
                    """, rs -> new SchemaObjectDependency(rs.getString(1), rs.getString(2), normalizedKind(rs.getString(3)), "USES"),
                    upper(object.schemaName()), upper(object.name()), oracleObjectType(kind));
            case "sqlserver" -> query(connection, """
                    SELECT COALESCE(OBJECT_SCHEMA_NAME(d.referenced_id), d.referenced_schema_name),
                           COALESCE(OBJECT_NAME(d.referenced_id), d.referenced_entity_name),
                           COALESCE(o.type_desc, 'OBJECT')
                    FROM sys.sql_expression_dependencies d
                    LEFT JOIN sys.objects o ON o.object_id = d.referenced_id
                    WHERE d.referencing_id = ?
                    """, rs -> new SchemaObjectDependency(rs.getString(1), rs.getString(2), normalizedKind(rs.getString(3)), "USES"),
                    integerIdentity(object));
            case "postgresql" -> query(connection, """
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
                    """, rs -> new SchemaObjectDependency(rs.getString(1), rs.getString(2), rs.getString(3), "USES"),
                    longIdentity(object));
            default -> List.of();
        };
    }

    public String template(DbConnection configured, DatabaseDialect dialect, SchemaObjectKind kind, String schema, String name) {
        String family = family(configured);
        String qualified = family.equals("clickhouse") && kind == SchemaObjectKind.FUNCTION
                ? dialect.quoteIdentifier(name)
                : dialect.qualifiedName(schema, name);
        return switch (kind) {
            case VIEW -> "CREATE VIEW " + qualified + " AS\nSELECT 1 AS example_value;";
            case MATERIALIZED_VIEW -> "CREATE MATERIALIZED VIEW " + qualified + " AS\nSELECT 1 AS example_value;";
            case SEQUENCE -> "CREATE SEQUENCE " + qualified + "\n  START WITH 1\n  INCREMENT BY 1;";
            case TRIGGER -> triggerTemplate(family, family.equals("postgresql") ? dialect.quoteIdentifier(name) : qualified);
            case PROCEDURE -> procedureTemplate(family, qualified);
            case FUNCTION -> functionTemplate(family, qualified);
        };
    }

    public String dropSql(DbConnection configured, DatabaseDialect dialect, SchemaObjectKind kind, CatalogObject object) {
        String family = family(configured);
        String qualified = dialect.qualifiedName(object.schemaName(), object.name());
        if (kind == SchemaObjectKind.TRIGGER && family.equals("postgresql")) {
            return "DROP TRIGGER " + dialect.quoteIdentifier(object.name()) + " ON " + qualifiedTable(dialect, object);
        }
        if ((kind == SchemaObjectKind.FUNCTION || kind == SchemaObjectKind.PROCEDURE) && family.equals("postgresql")) {
            return "DROP " + keyword(kind) + " " + qualified + "(" + value(object.subtype()) + ")";
        }
        return "DROP " + keyword(kind) + " " + qualified;
    }

    public String specialOperationSql(DbConnection configured, DatabaseDialect dialect, SchemaObjectOperation operation, SchemaObjectKind kind, CatalogObject object) {
        String family = family(configured);
        String qualified = dialect.qualifiedName(object.schemaName(), object.name());
        if (operation == SchemaObjectOperation.REFRESH) {
            if (family.equals("oracle")) {
                return "BEGIN DBMS_MVIEW.REFRESH('" + qualifiedLiteral(object.schemaName(), object.name()) + "'); END;";
            }
            if (family.equals("clickhouse")) return "SYSTEM REFRESH VIEW " + qualified;
            return "REFRESH MATERIALIZED VIEW " + qualified;
        }
        if (kind != SchemaObjectKind.TRIGGER) throw new IllegalArgumentException("该对象不支持启停操作。");
        boolean enable = operation == SchemaObjectOperation.ENABLE;
        if (family.equals("postgresql")) {
            return "ALTER TABLE " + qualifiedTable(dialect, object) + " " + (enable ? "ENABLE" : "DISABLE") + " TRIGGER " + dialect.quoteIdentifier(object.name());
        }
        if (family.equals("sqlserver")) {
            return (enable ? "ENABLE" : "DISABLE") + " TRIGGER " + qualified + " ON " + qualifiedTable(dialect, object);
        }
        return "ALTER TRIGGER " + qualified + (enable ? " ENABLE" : " DISABLE");
    }

    public String family(DbConnection connection) {
        String type = connection.dbType() == null ? "" : connection.dbType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "postgres", "postgresql" -> "postgresql";
            case "oracle", "dm", "dameng", "oceanbase-oracle" -> "oracle";
            case "sqlserver", "mssql" -> "sqlserver";
            case "sqlite" -> "sqlite";
            case "h2" -> "h2";
            case "clickhouse" -> "clickhouse";
            case "mariadb" -> "mariadb";
            case "mysql", "oceanbase-mysql" -> "mysql";
            default -> type;
        };
    }

    private List<CatalogObject> listPostgreSql(Connection connection, String schema, SchemaObjectKind kind) throws Exception {
        return switch (kind) {
            case VIEW, MATERIALIZED_VIEW, SEQUENCE -> query(connection, """
                    SELECT n.nspname, c.relname, c.oid::text, '', ''
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relkind = ?
                    """, this::catalogObject, schema, kind == SchemaObjectKind.VIEW ? "v" : kind == SchemaObjectKind.MATERIALIZED_VIEW ? "m" : "S");
            case TRIGGER -> query(connection, """
                    SELECT n.nspname, t.tgname, t.oid::text, c.relname,
                           CASE t.tgenabled WHEN 'D' THEN 'DISABLED' ELSE 'ENABLED' END
                    FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND NOT t.tgisinternal
                    """, this::catalogObject, schema);
            case PROCEDURE, FUNCTION -> query(connection, """
                    SELECT n.nspname, p.proname, p.oid::text,
                           pg_get_function_identity_arguments(p.oid), ''
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ? AND p.prokind = ?
                    """, this::catalogObject, schema, kind == SchemaObjectKind.PROCEDURE ? "p" : "f");
        };
    }

    private List<CatalogObject> listOracle(Connection connection, String schema, SchemaObjectKind kind) throws Exception {
        return query(connection, """
                SELECT OWNER, OBJECT_NAME, TO_CHAR(OBJECT_ID), '', STATUS
                FROM ALL_OBJECTS
                WHERE OWNER = ? AND OBJECT_TYPE = ?
                """, this::catalogObject, upper(schema), oracleObjectType(kind));
    }

    private List<CatalogObject> listSqlServer(Connection connection, String schema, SchemaObjectKind kind) throws Exception {
        if (kind == SchemaObjectKind.MATERIALIZED_VIEW) return List.of();
        if (kind == SchemaObjectKind.SEQUENCE) {
            return query(connection, """
                    SELECT s.name, q.name, CONVERT(varchar(30), q.object_id), q.type_desc, ''
                    FROM sys.sequences q JOIN sys.schemas s ON s.schema_id = q.schema_id
                    WHERE s.name = ?
                    """, this::catalogObject, schema);
        }
        if (kind == SchemaObjectKind.TRIGGER) {
            return query(connection, """
                    SELECT s.name, o.name, CONVERT(varchar(30), o.object_id), OBJECT_NAME(t.parent_id),
                           CASE WHEN t.is_disabled = 1 THEN 'DISABLED' ELSE 'ENABLED' END
                    FROM sys.triggers t
                    JOIN sys.objects o ON o.object_id = t.object_id
                    JOIN sys.schemas s ON s.schema_id = o.schema_id
                    WHERE s.name = ? AND t.parent_class = 1
                    """, this::catalogObject, schema);
        }
        String types = switch (kind) {
            case VIEW -> "'V'";
            case PROCEDURE -> "'P','PC'";
            case FUNCTION -> "'FN','IF','TF','FS','FT'";
            default -> "''";
        };
        return query(connection, "SELECT s.name, o.name, CONVERT(varchar(30), o.object_id), '', ''"
                        + " FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id WHERE s.name = ? AND o.type IN (" + types + ")",
                this::catalogObject, schema);
    }

    private List<CatalogObject> listMySql(Connection connection, String schema, SchemaObjectKind kind, boolean mariaDb) throws Exception {
        return switch (kind) {
            case VIEW -> query(connection, "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_NAME, CHECK_OPTION, '' FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ?", this::catalogObject, schema);
            case MATERIALIZED_VIEW -> List.of();
            case SEQUENCE -> mariaDb
                    ? query(connection, "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_NAME, TABLE_TYPE, '' FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'SEQUENCE'", this::catalogObject, schema)
                    : List.of();
            case TRIGGER -> query(connection, "SELECT TRIGGER_SCHEMA, TRIGGER_NAME, TRIGGER_NAME, CONCAT(ACTION_TIMING, ' ', EVENT_MANIPULATION, ' ON ', EVENT_OBJECT_TABLE), '' FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = ?", this::catalogObject, schema);
            case PROCEDURE, FUNCTION -> query(connection, "SELECT ROUTINE_SCHEMA, ROUTINE_NAME, SPECIFIC_NAME, '', ROUTINE_TYPE FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = ?", this::catalogObject, schema, kind.name());
        };
    }

    private List<CatalogObject> listSqlite(Connection connection, String schema, SchemaObjectKind kind) throws Exception {
        if (kind != SchemaObjectKind.VIEW && kind != SchemaObjectKind.TRIGGER) return List.of();
        return query(connection, "SELECT ?, name, name, type, '' FROM sqlite_master WHERE type = ?", this::catalogObject,
                schema == null || schema.isBlank() ? "main" : schema, sqliteType(kind));
    }

    private List<CatalogObject> listH2(Connection connection, String schema, SchemaObjectKind kind) throws Exception {
        return switch (kind) {
            case VIEW -> query(connection, "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_NAME, '', '' FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ?", this::catalogObject, upper(schema));
            case SEQUENCE -> query(connection, "SELECT SEQUENCE_SCHEMA, SEQUENCE_NAME, SEQUENCE_NAME, DATA_TYPE, '' FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ?", this::catalogObject, upper(schema));
            case TRIGGER -> query(connection, "SELECT TRIGGER_SCHEMA, TRIGGER_NAME, TRIGGER_NAME, EVENT_OBJECT_TABLE, '' FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = ?", this::catalogObject, upper(schema));
            case FUNCTION -> query(connection, "SELECT ROUTINE_SCHEMA, ROUTINE_NAME, SPECIFIC_NAME, '', '' FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'FUNCTION'", this::catalogObject, upper(schema));
            default -> List.of();
        };
    }

    private List<CatalogObject> listClickHouse(Connection connection, String schema, SchemaObjectKind kind) throws Exception {
        if (kind == SchemaObjectKind.VIEW || kind == SchemaObjectKind.MATERIALIZED_VIEW) {
            String engine = kind == SchemaObjectKind.VIEW ? "View" : "MaterializedView";
            return query(connection, "SELECT database, name, uuid::String, engine, '' FROM system.tables WHERE database = ? AND engine = ?", this::catalogObject, schema, engine);
        }
        if (kind == SchemaObjectKind.FUNCTION) {
            return query(connection, "SELECT '', name, name, origin, '' FROM system.functions WHERE origin = 'SQLUserDefined'", this::catalogObject);
        }
        return List.of();
    }

    private String postgreSqlSource(Connection connection, DatabaseDialect dialect, SchemaObjectKind kind, CatalogObject object) throws Exception {
        long oid = longIdentity(object);
        return switch (kind) {
            case VIEW -> "CREATE OR REPLACE VIEW " + dialect.qualifiedName(object.schemaName(), object.name()) + " AS\n" + scalar(connection, "SELECT pg_get_viewdef(?::oid, true)", oid) + ";";
            case MATERIALIZED_VIEW -> "CREATE MATERIALIZED VIEW " + dialect.qualifiedName(object.schemaName(), object.name()) + " AS\n" + scalar(connection, "SELECT pg_get_viewdef(?::oid, true)", oid) + ";";
            case TRIGGER -> scalar(connection, "SELECT pg_get_triggerdef(?::oid, true)", oid) + ";";
            case PROCEDURE, FUNCTION -> scalar(connection, "SELECT pg_get_functiondef(?::oid)", oid);
            case SEQUENCE -> {
                List<String> rows = query(connection, """
                        SELECT 'CREATE SEQUENCE ' || quote_ident(schemaname) || '.' || quote_ident(sequencename)
                               || ' AS ' || data_type || ' START WITH ' || start_value
                               || ' INCREMENT BY ' || increment_by || ' MINVALUE ' || min_value
                               || ' MAXVALUE ' || max_value || ' CACHE ' || cache_size
                               || CASE WHEN cycle THEN ' CYCLE' ELSE ' NO CYCLE' END || ';'
                        FROM pg_sequences WHERE schemaname = ? AND sequencename = ?
                        """, rs -> rs.getString(1), object.schemaName(), object.name());
                yield rows.isEmpty() ? null : rows.get(0);
            }
        };
    }

    private String oracleSource(Connection connection, SchemaObjectKind kind, CatalogObject object) throws Exception {
        return scalar(connection, "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual", oracleObjectType(kind), upper(object.name()), upper(object.schemaName()));
    }

    private String h2Source(Connection connection, DatabaseDialect dialect, SchemaObjectKind kind, CatalogObject object) throws Exception {
        return switch (kind) {
            case VIEW -> "CREATE OR REPLACE VIEW " + dialect.qualifiedName(object.schemaName(), object.name()) + " AS\n"
                    + scalar(connection, "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", upper(object.schemaName()), upper(object.name())) + ";";
            case SEQUENCE -> {
                List<String> rows = query(connection, """
                        SELECT 'CREATE SEQUENCE ' || SEQUENCE_SCHEMA || '.' || SEQUENCE_NAME
                               || ' START WITH ' || START_VALUE || ' INCREMENT BY ' || INCREMENT || ';'
                        FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ? AND SEQUENCE_NAME = ?
                        """, rs -> rs.getString(1), upper(object.schemaName()), upper(object.name()));
                yield rows.isEmpty() ? null : rows.get(0);
            }
            case TRIGGER -> scalar(connection, "SELECT ACTION_STATEMENT FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = ? AND TRIGGER_NAME = ?", upper(object.schemaName()), upper(object.name()));
            case FUNCTION -> {
                String definition = scalar(connection, "SELECT ROUTINE_DEFINITION FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ? AND SPECIFIC_NAME = ?", upper(object.schemaName()), object.specificName());
                yield definition == null ? null : "CREATE ALIAS " + dialect.qualifiedName(object.schemaName(), object.name()) + " AS $$\n" + definition + "\n$$;";
            }
            default -> null;
        };
    }

    private String showCreate(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) return null;
            ResultSetMetaData metadata = rs.getMetaData();
            String fallback = null;
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                String value = rs.getString(index);
                if (value == null) continue;
                if (fallback == null || value.length() > fallback.length()) fallback = value;
                if (value.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE")) return value;
            }
            return fallback;
        }
    }

    private String triggerTemplate(String family, String qualified) {
        return switch (family) {
            case "postgresql" -> "CREATE TRIGGER " + qualified + "\nBEFORE INSERT ON <table>\nFOR EACH ROW EXECUTE FUNCTION <function>();";
            case "oracle" -> "CREATE TRIGGER " + qualified + "\nBEFORE INSERT ON <table>\nFOR EACH ROW\nBEGIN\n  NULL;\nEND;";
            case "sqlserver" -> "CREATE TRIGGER " + qualified + " ON <table>\nAFTER INSERT AS\nBEGIN\n  SET NOCOUNT ON;\nEND;";
            case "sqlite" -> "CREATE TRIGGER " + qualified + " AFTER INSERT ON <table>\nBEGIN\n  SELECT 1;\nEND;";
            case "h2" -> "CREATE TRIGGER " + qualified + " BEFORE INSERT ON <table>\nFOR EACH ROW CALL '<java.class.Name>';";
            default -> "CREATE TRIGGER " + qualified + " BEFORE INSERT ON <table>\nFOR EACH ROW\nSET @schema_object_trigger = 1;";
        };
    }

    private String procedureTemplate(String family, String qualified) {
        return switch (family) {
            case "postgresql" -> "CREATE PROCEDURE " + qualified + "()\nLANGUAGE plpgsql\nAS $$\nBEGIN\n  NULL;\nEND;\n$$;";
            case "oracle" -> "CREATE PROCEDURE " + qualified + " AS\nBEGIN\n  NULL;\nEND;";
            case "sqlserver" -> "CREATE PROCEDURE " + qualified + "\nAS\nBEGIN\n  SET NOCOUNT ON;\nEND;";
            default -> "CREATE PROCEDURE " + qualified + "()\nBEGIN\n  SELECT 1;\nEND;";
        };
    }

    private String functionTemplate(String family, String qualified) {
        return switch (family) {
            case "postgresql" -> "CREATE FUNCTION " + qualified + "() RETURNS integer\nLANGUAGE sql\nAS $$ SELECT 1 $$;";
            case "oracle" -> "CREATE FUNCTION " + qualified + " RETURN NUMBER AS\nBEGIN\n  RETURN 1;\nEND;";
            case "sqlserver" -> "CREATE FUNCTION " + qualified + "() RETURNS int\nAS\nBEGIN\n  RETURN 1;\nEND;";
            case "clickhouse" -> "CREATE FUNCTION " + qualified + " AS () -> 1;";
            case "h2" -> "CREATE ALIAS " + qualified + " AS $$\nint value() { return 1; }\n$$;";
            default -> "CREATE FUNCTION " + qualified + "() RETURNS INTEGER DETERMINISTIC\nRETURN 1;";
        };
    }

    private String qualifiedTable(DatabaseDialect dialect, CatalogObject object) {
        String table = value(object.subtype());
        int on = table.toUpperCase(Locale.ROOT).lastIndexOf(" ON ");
        if (on >= 0) table = table.substring(on + 4);
        if (table.isBlank()) throw new IllegalStateException("触发器缺少所属表信息，无法生成安全操作 SQL。");
        return dialect.qualifiedName(object.schemaName(), table);
    }

    private String mysqlKeyword(SchemaObjectKind kind) {
        return switch (kind) {
            case MATERIALIZED_VIEW -> "VIEW";
            default -> keyword(kind);
        };
    }

    private String clickHouseKeyword(SchemaObjectKind kind) {
        return kind == SchemaObjectKind.FUNCTION ? "FUNCTION" : "TABLE";
    }

    private String keyword(SchemaObjectKind kind) {
        return kind == SchemaObjectKind.MATERIALIZED_VIEW ? "MATERIALIZED VIEW" : kind.name();
    }

    private String oracleObjectType(SchemaObjectKind kind) {
        return kind == SchemaObjectKind.MATERIALIZED_VIEW ? "MATERIALIZED VIEW" : kind.name();
    }

    private String sqliteType(SchemaObjectKind kind) {
        return kind == SchemaObjectKind.TRIGGER ? "trigger" : "view";
    }

    private String normalizedKind(String value) {
        return value == null ? "OBJECT" : value.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private String qualifiedLiteral(String schema, String name) {
        return (schema == null || schema.isBlank() ? name : schema + "." + name).replace("'", "''");
    }

    private CatalogObject catalogObject(ResultSet rs) throws Exception {
        return new CatalogObject(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
    }

    private <T> List<T> query(Connection connection, String sql, RowReader<T> reader, Object... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(MAX_QUERY_ROWS);
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> values = new ArrayList<>();
                while (rs.next() && values.size() < MAX_QUERY_ROWS) values.add(reader.read(rs));
                return values;
            }
        }
    }

    private String scalar(Connection connection, String sql, Object... parameters) throws Exception {
        List<String> values = query(connection, sql, rs -> {
            Object value = rs.getObject(1);
            if (value instanceof java.sql.Clob clob) return clob.getSubString(1, (int) Math.min(clob.length(), 2_000_000));
            return value == null ? null : value.toString();
        }, parameters);
        return values.isEmpty() ? null : values.get(0);
    }

    private String parameterMode(SchemaObjectKind kind, int columnType) {
        if (kind == SchemaObjectKind.PROCEDURE) {
            return switch (columnType) {
                case DatabaseMetaData.procedureColumnIn -> "IN";
                case DatabaseMetaData.procedureColumnOut -> "OUT";
                case DatabaseMetaData.procedureColumnInOut -> "INOUT";
                case DatabaseMetaData.procedureColumnReturn -> "RETURN";
                case DatabaseMetaData.procedureColumnResult -> null;
                default -> null;
            };
        }
        return switch (columnType) {
            case DatabaseMetaData.functionColumnIn -> "IN";
            case DatabaseMetaData.functionColumnOut -> "OUT";
            case DatabaseMetaData.functionColumnInOut -> "INOUT";
            case DatabaseMetaData.functionReturn -> "RETURN";
            case DatabaseMetaData.functionColumnResult -> null;
            default -> null;
        };
    }

    private boolean matchesSpecific(DbConnection configured, String expected, String actual) {
        String family = family(configured);
        if (!family.equals("postgresql") && !family.equals("h2") && !family.equals("mysql") && !family.equals("mariadb")) return true;
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return true;
        return actual.equals(expected) || actual.endsWith("_" + expected);
    }

    private String optionalString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int optionalInt(ResultSet rs, String column, int fallback) {
        try {
            int value = rs.getInt(column);
            return rs.wasNull() ? fallback : value;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int integerIdentity(CatalogObject object) {
        return Integer.parseInt(object.specificName());
    }

    private long longIdentity(CatalogObject object) {
        return Long.parseLong(object.specificName());
    }

    private String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet rs) throws Exception;
    }
}
