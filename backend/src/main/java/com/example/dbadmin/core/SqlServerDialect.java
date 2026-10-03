package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;

import java.util.List;
import java.util.Locale;
import java.util.HexFormat;

public class SqlServerDialect extends DefaultDialect {


    /** 字段说明保存为 MS_Description 扩展属性。 */
    @Override
    public boolean supportsColumnComments() {
        return true;
    }

    @Override
    public String castToText(String expression) {
        return "CAST(" + expression + " AS NVARCHAR(MAX))";
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("sqlserver") || url.startsWith("jdbc:sqlserver:");
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of(), List.of(), SchemaObjectCapabilities.sqlServer(), true);
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        String ordered = hasTopLevelOrderBy(baseSql) ? baseSql : baseSql + " ORDER BY (SELECT NULL)";
        return ordered + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public boolean supportsCteResultPushdown() { return false; }

    @Override
    public String resultPushdownSource(String sql) {
        // executePage has already rejected TOP/OFFSET/FETCH. SQL Server otherwise rejects
        // ORDER BY in a derived table; OFFSET 0 keeps every source row and every bind position.
        return hasTopLevelOrderBy(sql) ? sql + " OFFSET 0 ROWS" : sql;
    }

    @Override
    public String scriptStatementSeparator() {
        return ";\nGO";
    }

    @Override
    public String scriptLiteral(Object value) {
        if (value instanceof byte[] bytes) return scriptBinaryLiteral(bytes);
        if (value instanceof Boolean bool) return bool ? "1" : "0";
        if (value instanceof CharSequence text) return "N'" + text.toString().replace("'", "''") + "'";
        return super.scriptLiteral(value);
    }

    @Override
    public String scriptBinaryLiteral(byte[] value) {
        return "0x" + HexFormat.of().formatHex(value);
    }

    @Override
    public com.example.dbadmin.dto.ApiDtos.SqlResult explain(java.sql.Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception {
        long started = System.nanoTime();
        Exception failure = null;
        // Each SET must be its own batch. Always reset, including failures while enabling it.
        try {
            try (var control = connection.createStatement()) {
                control.setQueryTimeout(timeoutSeconds);
                control.execute("SET SHOWPLAN_XML ON");
            }
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var rs = statement.executeQuery(sql)) {
                    if (!rs.next()) throw new java.sql.SQLException("SQL Server 未返回估算执行计划。");
                    try (var reader = rs.getCharacterStream(1)) {
                        return SqlServerShowplan.read(reader, maxRows, (System.nanoTime() - started) / 1_000_000);
                    }
                }
            }
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            try (var control = connection.createStatement()) {
                control.setQueryTimeout(timeoutSeconds);
                control.execute("SET SHOWPLAN_XML OFF");
            } catch (Exception resetError) {
                // A pooled session still in SHOWPLAN mode must never be reused for writes.
                try { connection.abort(Runnable::run); } catch (Exception abortError) { resetError.addSuppressed(abortError); }
                if (failure != null) failure.addSuppressed(resetError);
                else throw resetError;
            }
        }
    }

    @Override
    public List<String> createTableSql(String schema, String name, TableDesignRequest design) {
        if (design.columns().stream().filter(c -> !c.deleted() && Boolean.TRUE.equals(c.identity())).count() > 1)
            throw new IllegalArgumentException("SQL Server 一张表最多一个 IDENTITY 字段。");
        return super.createTableSql(schema, name, design);
    }

    @Override
    protected String columnDefinition(ColumnDesign column) {
        boolean identity = Boolean.TRUE.equals(column.identity());
        if (identity && (column.nullable() || blankToNull(column.defaultValue()) != null
                || !java.util.Set.of("TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT").contains(column.type().trim().toUpperCase(Locale.ROOT))))
            throw new IllegalArgumentException("IDENTITY 字段请使用非空整数类型，且不能设置默认值。");
        return quoteIdentifier(column.name()) + " " + type(column.type(), column.size())
                + (identity ? " IDENTITY(1,1)" : "")
                + (blankToNull(column.defaultValue()) == null ? "" : " DEFAULT " + column.defaultValue().trim())
                + (column.nullable() ? " NULL" : " NOT NULL");
    }

    @Override
    protected String addColumnSql(String table, ColumnDesign column) {
        return "ALTER TABLE " + table + " ADD " + columnDefinition(column);
    }

    @Override
    protected String dropColumnSql(String table, String column) {
        return dropDefault(table, column) + "; ALTER TABLE " + table + " DROP COLUMN " + quoteIdentifier(column);
    }

    @Override
    protected List<String> alterColumnSql(String table, String name, ColumnInfo original, ColumnDesign column) {
        boolean changedType = !sameType(original, column) || original.nullable() != column.nullable();
        boolean changedDefault = !java.util.Objects.equals(normalizeDefault(original.defaultValue()), normalizeDefault(column.defaultValue()));
        if (column.identity() != null && original.identity() != column.identity())
            throw new IllegalArgumentException("不能直接增删已有字段的 IDENTITY 属性，请通过新列迁移数据。");
        if (original.generated() && (changedType || changedDefault))
            throw new IllegalArgumentException("计算列或系统生成列暂不支持修改类型、可空性或默认值。");
        if (original.identity() && (changedType || changedDefault))
            throw new IllegalArgumentException("已有 IDENTITY 字段暂不支持修改类型、可空性或默认值。");
        List<String> sql = new java.util.ArrayList<>();
        if (changedDefault || changedType && blankToNull(original.defaultValue()) != null) sql.add(dropDefault(table, name));
        if (changedType) sql.add("ALTER TABLE " + table + " ALTER COLUMN " + quoteIdentifier(name) + " "
                + type(column.type(), column.size()) + (column.nullable() ? " NULL" : " NOT NULL"));
        if ((changedDefault || changedType) && blankToNull(column.defaultValue()) != null)
            sql.add("ALTER TABLE " + table + " ADD DEFAULT " + column.defaultValue().trim() + " FOR " + quoteIdentifier(name));
        if (commentChanged(original.remarks(), column.remarks())) sql.addAll(columnCommentSql(table, name, column.remarks()));
        return sql;
    }

    private String dropDefault(String table, String column) {
        return "DECLARE @df sysname; SELECT @df = d.name FROM sys.default_constraints d JOIN sys.columns c"
                + " ON c.object_id = d.parent_object_id AND c.column_id = d.parent_column_id"
                + " WHERE d.parent_object_id = OBJECT_ID(" + scriptLiteral(table) + ") AND c.name = " + scriptLiteral(column)
                + "; IF @df IS NOT NULL BEGIN DECLARE @ddl nvarchar(max) = " + scriptLiteral("ALTER TABLE " + table + " DROP CONSTRAINT ") + " + QUOTENAME(@df); EXEC sys.sp_executesql @ddl; END";
    }

    @Override
    protected List<String> primaryKeySql(String table, ObjectDetail original, List<String> requested) {
        if (requested == null) requested = List.of();
        if (sameNames(original.primaryKeys(), requested)) return List.of();
        List<String> sql = new java.util.ArrayList<>();
        if (!original.primaryKeys().isEmpty()) {
            if (blankToNull(original.primaryKeyName()) == null) throw new IllegalArgumentException("无法确定原主键约束名，请刷新结构。");
            sql.add("ALTER TABLE " + table + " DROP CONSTRAINT " + quoteIdentifier(original.primaryKeyName()));
        }
        if (!requested.isEmpty()) sql.add("ALTER TABLE " + table + " ADD PRIMARY KEY ("
                + requested.stream().map(this::quoteIdentifier).collect(java.util.stream.Collectors.joining(", ")) + ")");
        return sql;
    }

    @Override
    protected String dropIndexSql(String table, String name) { return "DROP INDEX " + quoteIdentifier(name) + " ON " + table; }

    @Override
    public String renameTableSql(String schema, String table, String newName) {
        return "EXEC sys.sp_rename " + scriptLiteral(qualifiedName(schema, table)) + ", " + scriptLiteral(newName) + ", N'OBJECT'";
    }

    @Override
    protected String renameColumnSql(String table, String original, ColumnDesign column) {
        return "EXEC sys.sp_rename " + scriptLiteral(table + "." + quoteIdentifier(original)) + ", " + scriptLiteral(column.name()) + ", N'COLUMN'";
    }

    @Override
    protected List<String> columnCommentSql(String table, String column, String remarks) {
        if (remarks == null) return List.of();
        String object = "OBJECT_ID(" + scriptLiteral(table) + ")";
        String args = " @name=N'MS_Description', @value=" + scriptLiteral(remarks)
                + ", @level0type=N'SCHEMA', @level0name=@schema, @level1type=N'TABLE', @level1name=@table, @level2type=N'COLUMN', @level2name=" + scriptLiteral(column);
        return List.of("DECLARE @schema sysname = OBJECT_SCHEMA_NAME(" + object + "), @table sysname = OBJECT_NAME(" + object + "); "
                + "IF EXISTS (SELECT 1 FROM sys.extended_properties WHERE class=1 AND major_id=" + object
                + " AND minor_id=COLUMNPROPERTY(" + object + ", " + scriptLiteral(column) + ", 'ColumnId') AND name=N'MS_Description') "
                + "EXEC sys.sp_updateextendedproperty" + args + "; ELSE EXEC sys.sp_addextendedproperty" + args);
    }

    @Override
    protected String normalizeDefault(String value) {
        String result = super.normalizeDefault(value);
        // SQL Server wraps literals in redundant parentheses in COLUMN_DEF.
        if (result != null && result.matches("\\(+[-+]?\\d+(?:\\.\\d+)?\\)+")) return result.replace("(", "").replace(")", "");
        return result;
    }

    @Override
    public List<ColumnInfo> enrichColumns(java.sql.Connection connection, String schema, String table, List<ColumnInfo> columns) throws Exception {
        var details = new java.util.HashMap<String, ColumnInfo>();
        try (var statement = connection.prepareStatement("""
                SELECT c.name, c.is_identity, c.is_computed, c.generated_always_type,
                       c.precision, c.scale, c.max_length, c.user_type_id, c.system_type_id, TYPE_NAME(c.system_type_id) AS base_type,
                       CAST(ep.value AS nvarchar(4000)) AS remarks
                FROM sys.columns c LEFT JOIN sys.extended_properties ep
                  ON ep.class=1 AND ep.major_id=c.object_id AND ep.minor_id=c.column_id AND ep.name=N'MS_Description'
                WHERE c.object_id = OBJECT_ID(?)
                """)) {
            statement.setQueryTimeout(30);
            statement.setString(1, qualifiedName(schema, table));
            try (var rs = statement.executeQuery()) {
                var originals = columns.stream().collect(java.util.stream.Collectors.toMap(ColumnInfo::name, c -> c));
                while (rs.next()) {
                    ColumnInfo old = originals.get(rs.getString("name"));
                    if (old == null) continue;
                    String base = rs.getString("base_type").toLowerCase(Locale.ROOT);
                    String type = old.type();
                    if (rs.getInt("user_type_id") != rs.getInt("system_type_id")) type = old.type();
                    else if (java.util.Set.of("decimal", "numeric").contains(base)) type = base + "(" + rs.getInt("precision") + "," + rs.getInt("scale") + ")";
                    else if (java.util.Set.of("datetime2", "datetimeoffset", "time").contains(base)) type = base + "(" + rs.getInt("scale") + ")";
                    else if (rs.getInt("max_length") == -1 && java.util.Set.of("varchar", "nvarchar", "varbinary").contains(base)) type = base + "(max)";
                    else if (rs.getBoolean("is_identity")) type = base;
                    details.put(old.name(), new ColumnInfo(old.name(), type, old.size(), old.nullable(), rs.getString("remarks"),
                            old.ordinalPosition(), old.defaultValue(), rs.getBoolean("is_identity"), rs.getBoolean("is_computed") || rs.getInt("generated_always_type") != 0 || base.equals("timestamp") || base.equals("rowversion")));
                }
            }
        }
        return columns.stream().map(c -> details.getOrDefault(c.name(), c)).toList();
    }

    @Override
    public void validateTableDesign(java.sql.Connection connection, ObjectDetail original, TableDesignRequest request) throws Exception {
        // The generic index editor cannot preserve filtered/included/clustered/constraint index definitions.
        // Refuse changes to those indexes instead of rebuilding them with lost attributes.
        try (var statement = connection.prepareStatement("""
                SELECT i.name FROM sys.indexes i WHERE i.object_id=OBJECT_ID(?) AND i.is_primary_key=0 AND i.name IS NOT NULL
                AND (i.type<>2 OR i.has_filter=1 OR i.is_unique_constraint=1 OR EXISTS
                  (SELECT 1 FROM sys.index_columns ic WHERE ic.object_id=i.object_id AND ic.index_id=i.index_id
                   AND (ic.is_included_column=1 OR ic.is_descending_key=1)))
                """)) {
            statement.setQueryTimeout(30);
            statement.setString(1, qualifiedName(original.schemaName(), original.name()));
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    var design = request.indexes() == null ? null : request.indexes().stream().filter(i -> name.equals(i.originalName())).findFirst().orElse(null);
                    var before = original.indexes().stream().filter(i -> name.equals(i.name())).sorted(java.util.Comparator.comparingInt(com.example.dbadmin.dto.ApiDtos.IndexInfo::ordinalPosition)).toList();
                    if (design == null || design.deleted() || !name.equals(design.name()) || before.isEmpty()
                            || before.get(0).unique() != design.unique() || !sameNames(before.stream().map(com.example.dbadmin.dto.ApiDtos.IndexInfo::columnName).toList(), design.columns()))
                        throw new IllegalArgumentException("索引 " + name + " 含高级属性或属于约束，请使用原生 DDL 修改。");
                }
            }
        }
    }

    private boolean hasTopLevelOrderBy(String sql) {
        int depth = 0;
        String previousWord = null;
        for (int index = 0; index < sql.length();) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (current == '\'' || current == '"') {
                char quote = current;
                index++;
                while (index < sql.length()) {
                    if (sql.charAt(index) == quote) {
                        if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) index += 2;
                        else { index++; break; }
                    } else index++;
                }
                previousWord = null;
                continue;
            }
            if (current == '[') {
                index++;
                while (index < sql.length()) {
                    if (sql.charAt(index) == ']') {
                        if (index + 1 < sql.length() && sql.charAt(index + 1) == ']') index += 2;
                        else { index++; break; }
                    } else index++;
                }
                previousWord = null;
                continue;
            }
            if (current == '-' && next == '-') {
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') index++;
                continue;
            }
            if (current == '/' && next == '*') {
                index += 2;
                while (index + 1 < sql.length() && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) index++;
                index = Math.min(sql.length(), index + 2);
                continue;
            }
            if (current == '(') {
                depth++;
                previousWord = null;
                index++;
                continue;
            }
            if (current == ')') {
                depth = Math.max(0, depth - 1);
                previousWord = null;
                index++;
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                int start = index++;
                while (index < sql.length() && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) index++;
                if (depth == 0) {
                    String word = sql.substring(start, index).toLowerCase(Locale.ROOT);
                    if ("by".equals(word) && "order".equals(previousWord)) return true;
                    previousWord = word;
                }
                continue;
            }
            if (!Character.isWhitespace(current)) previousWord = null;
            index++;
        }
        return false;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier.replace("]", "]]" ) + "]";
    }
}
