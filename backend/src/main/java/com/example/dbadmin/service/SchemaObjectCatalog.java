package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.core.SchemaObjectOperation;
import com.example.dbadmin.core.SchemaObjectSql;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDependency;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectParameter;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 可编程对象目录：执行方言给出的目录查询（分页、按名查找、取定义、查依赖、读参数元数据）。
 *
 * <p>各数据库怎么写在 {@link SchemaObjectSql}（经 {@link DatabaseDialect#schemaObjects()} 取得）；这里只管执行 ——
 * 分页与过滤包在外层、查询超时、行数上限、整段读 CLOB。</p>
 */
@Component
public class SchemaObjectCatalog {
    private static final int MAX_QUERY_ROWS = 20_001;
    private static final long MAX_PAGE_OFFSET = 1_000_000;
    private static final String COLUMNS = "SELECT schema_name, object_name, specific_name, subtype, object_status FROM (";
    private final int defaultQueryTimeoutSeconds;

    public SchemaObjectCatalog(AppProperties properties) {
        this.defaultQueryTimeoutSeconds = Math.max(1, properties.getSql().getTimeoutSeconds());
    }

    public record CatalogObject(String schemaName, String name, String specificName, String subtype, String status) {
        SchemaObjectSql.ObjectRef ref() {
            return new SchemaObjectSql.ObjectRef(schemaName, name, specificName, subtype);
        }
    }

    public record CatalogPage(List<CatalogObject> items, int total, boolean totalExact, boolean hasMore) {
        public CatalogPage {
            items = List.copyOf(items);
        }
    }

    public CatalogPage page(
            Connection connection,
            DatabaseDialect dialect,
            String schema,
            SchemaObjectKind kind,
            String keyword,
            int page,
            int pageSize,
            int timeoutSeconds
    ) throws Exception {
        long offset = (long) page * pageSize;
        if (offset > MAX_PAGE_OFFSET) {
            throw new IllegalArgumentException("对象分页偏移过大，请使用搜索条件缩小范围。");
        }
        SchemaObjectSql objects = dialect.schemaObjects();
        SchemaObjectSql.Query query = objects.listQuery(schema, kind);
        if (query == null) return new CatalogPage(List.of(), 0, true, false);

        List<Object> parameters = new ArrayList<>(query.parameters());
        StringBuilder filtered = new StringBuilder(COLUMNS).append(query.sql()).append(") dbadmin_schema_objects");
        if (keyword != null && !keyword.isBlank()) {
            SchemaObjectSql.Query filter = objects.keywordFilter(keyword);
            filtered.append(" WHERE ").append(filter.sql());
            parameters.addAll(filter.parameters());
        }
        filtered.append(" ORDER BY LOWER(object_name), object_name, specific_name");

        int limit = pageSize + 1;
        List<CatalogObject> loaded = boundedQuery(connection, dialect, dialect.pageQuery(filtered.toString(), limit, Math.toIntExact(offset)),
                parameters, limit, timeoutSeconds, Math.min(limit, 200));
        boolean hasMore = loaded.size() > pageSize;
        if (hasMore) loaded = new ArrayList<>(loaded.subList(0, pageSize));
        int total = (int) Math.min(Integer.MAX_VALUE, offset + loaded.size() + (hasMore ? 1L : 0L));
        return new CatalogPage(loaded, total, !hasMore, hasMore);
    }

    public List<CatalogObject> find(
            Connection connection,
            DatabaseDialect dialect,
            String schema,
            SchemaObjectKind kind,
            String name,
            String specificName,
            int timeoutSeconds
    ) throws Exception {
        SchemaObjectSql.Query query = dialect.schemaObjects().listQuery(schema, kind);
        if (query == null) return List.of();
        List<Object> parameters = new ArrayList<>(query.parameters());
        parameters.add(name);
        parameters.add(specificName == null ? "" : specificName);
        String sql = COLUMNS + query.sql() + ") dbadmin_schema_objects WHERE object_name = ? AND COALESCE(specific_name, '') = ?";
        return boundedQuery(connection, dialect, sql, parameters, 2, timeoutSeconds, 2);
    }

    public boolean existsByName(
            Connection connection,
            DatabaseDialect dialect,
            String schema,
            SchemaObjectKind kind,
            String name,
            int timeoutSeconds
    ) throws Exception {
        SchemaObjectSql.Query query = dialect.schemaObjects().listQuery(schema, kind);
        if (query == null) return false;
        List<Object> parameters = new ArrayList<>(query.parameters());
        parameters.add(name.toLowerCase(Locale.ROOT));
        String sql = COLUMNS + query.sql() + ") dbadmin_schema_objects WHERE LOWER(object_name) = ?";
        return !boundedQuery(connection, dialect, sql, parameters, 1, timeoutSeconds, 1).isEmpty();
    }

    public String source(Connection connection, DatabaseDialect dialect, SchemaObjectKind kind, CatalogObject object) throws Exception {
        SchemaObjectSql.Source lookup = dialect.schemaObjects().source(dialect, kind, object.ref());
        String source = null;
        if (lookup != null) {
            String loaded = lookup.showCreate()
                    ? showCreate(connection, lookup.sql())
                    : scalar(connection, lookup.sql(), lookup.parameters().toArray());
            // 取不到就是取不到：不能拼出一个「CREATE VIEW v AS null;」的空壳当成定义。
            if (loaded != null) source = lookup.prefix() + loaded + lookup.suffix();
        }
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("数据库未返回对象源码，可能缺少查看定义的权限。");
        }
        return source.trim();
    }

    public List<SchemaObjectParameter> parameters(Connection connection, DatabaseDialect dialect, SchemaObjectKind kind, CatalogObject object) throws Exception {
        if (kind != SchemaObjectKind.PROCEDURE && kind != SchemaObjectKind.FUNCTION) return List.of();
        SchemaObjectSql objects = dialect.schemaObjects();
        DatabaseMetaData metadata = connection.getMetaData();
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, object.schemaName());
        boolean procedureMetadata = kind == SchemaObjectKind.PROCEDURE
                || (kind == SchemaObjectKind.FUNCTION && objects.functionParametersFromProcedureMetadata());
        List<SchemaObjectParameter> parameters = new ArrayList<>();
        try (ResultSet rs = procedureMetadata
                ? metadata.getProcedureColumns(scope.catalog(), scope.schemaPattern(), object.name(), "%")
                : metadata.getFunctionColumns(scope.catalog(), scope.schemaPattern(), object.name(), "%")) {
            while (rs.next()) {
                String specificName = optionalString(rs, "SPECIFIC_NAME");
                if (objects.specificNameIdentifiesOverload() && !matchesSpecific(object.specificName(), specificName)) continue;
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

    public List<SchemaObjectDependency> dependencies(Connection connection, DatabaseDialect dialect, SchemaObjectKind kind, CatalogObject object) throws Exception {
        SchemaObjectSql.Query query = dialect.schemaObjects().dependenciesQuery(kind, object.ref());
        if (query == null) return List.of();
        return query(connection, query.sql(),
                rs -> new SchemaObjectDependency(rs.getString(1), rs.getString(2), normalizedKind(rs.getString(3)), "USES"),
                query.parameters().toArray());
    }

    public String template(DatabaseDialect dialect, SchemaObjectKind kind, String schema, String name) {
        return dialect.schemaObjects().template(dialect, kind, schema, name);
    }

    public String dropSql(DatabaseDialect dialect, SchemaObjectKind kind, CatalogObject object) {
        return dialect.schemaObjects().dropSql(dialect, kind, object.ref());
    }

    public String specialOperationSql(DatabaseDialect dialect, SchemaObjectOperation operation, SchemaObjectKind kind, CatalogObject object) {
        SchemaObjectSql objects = dialect.schemaObjects();
        if (operation == SchemaObjectOperation.REFRESH) return objects.refreshSql(dialect, object.ref());
        if (kind != SchemaObjectKind.TRIGGER) throw new IllegalArgumentException("该对象不支持启停操作。");
        return objects.triggerToggleSql(dialect, object.ref(), operation == SchemaObjectOperation.ENABLE);
    }

    private List<CatalogObject> boundedQuery(
            Connection connection,
            DatabaseDialect dialect,
            String sql,
            List<Object> parameters,
            int limit,
            int timeoutSeconds,
            int fetchSize
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.configureReadStatement(connection, statement, fetchSize, timeoutSeconds);
            statement.setMaxRows(limit);
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            List<CatalogObject> objects = new ArrayList<>(limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next() && objects.size() < limit) objects.add(catalogObject(rs));
            }
            return objects;
        }
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

    /** 参数元数据里的 SPECIFIC_NAME 有时带着驱动加的前缀（schema_name_123），按后缀认。 */
    private static boolean matchesSpecific(String expected, String actual) {
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

    private String normalizedKind(String value) {
        return value == null ? "OBJECT" : value.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private CatalogObject catalogObject(ResultSet rs) throws Exception {
        return new CatalogObject(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
    }

    private <T> List<T> query(Connection connection, String sql, RowReader<T> reader, Object... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(MAX_QUERY_ROWS);
            try {
                // Every list/dependency/source lookup ends up here; without a
                // timeout a stuck catalog query (e.g. pg_get_functiondef on a
                // huge function, or the pg_depend join in dependencies()) could
                // hold the request thread and a pool connection indefinitely.
                statement.setQueryTimeout(defaultQueryTimeoutSeconds);
            } catch (SQLFeatureNotSupportedException ignored) {
                // Some otherwise usable JDBC drivers do not implement timeouts.
            }
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
            if (value instanceof java.sql.Clob clob) {
                long length = clob.length();
                // 不能把前缀冒充完整定义，否则 MCP 分片会报告错误的总长度，界面替换还可能丢掉尾部。
                if (length > 2_000_000) throw new IllegalStateException("对象源码超过 200 万字符读取上限，请按字符分片查询数据库源码。");
                return clob.getSubString(1, (int) length);
            }
            return value == null ? null : value.toString();
        }, parameters);
        return values.isEmpty() ? null : values.get(0);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet rs) throws Exception;
    }
}
