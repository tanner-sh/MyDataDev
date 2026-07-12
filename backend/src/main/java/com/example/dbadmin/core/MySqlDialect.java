package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class MySqlDialect extends DefaultDialect {
    private static final String IDENTIFIER_QUOTE = String.valueOf((char) 96);

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of("MYSQLDUMP"));
    }

    @Override
    public void configureStreamingStatement(Connection connection, Statement statement, int fetchSize, int timeoutSeconds) throws java.sql.SQLException {
        if (timeoutSeconds > 0) statement.setQueryTimeout(timeoutSeconds);
        // Connector/J uses this sentinel for a forward-only streaming result set
        // unless cursor fetching has explicitly been enabled in the JDBC URL.
        statement.setFetchSize(Integer.MIN_VALUE);
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("mysql")
                || type.equals("mariadb")
                || url.startsWith("jdbc:mysql:")
                || url.startsWith("jdbc:mariadb:");
    }

    @Override
    public NamespaceKind namespaceKind() {
        return NamespaceKind.CATALOG;
    }

    @Override
    public String currentSchema(Connection connection) throws Exception {
        String catalog = connection.getCatalog();
        if (catalog != null && !catalog.isBlank()) {
            return catalog;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DATABASE()")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return IDENTIFIER_QUOTE + identifier.replace(IDENTIFIER_QUOTE, IDENTIFIER_QUOTE + IDENTIFIER_QUOTE) + IDENTIFIER_QUOTE;
    }

    @Override
    public Optional<String> nativeDdl(Connection connection, String schemaName, String objectName, String objectType) throws Exception {
        String sql = "SHOW CREATE " + (objectType != null && objectType.toUpperCase(Locale.ROOT).contains("VIEW") ? "VIEW " : "TABLE ")
                + qualifiedName(schemaName, objectName);
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? Optional.ofNullable(rs.getString(2)) : Optional.empty();
        }
    }

    @Override
    protected List<String> alterColumnSql(String table, String columnName, ColumnInfo original, ColumnDesign column) {
        boolean changed = !sameType(original, column)
                || original.nullable() != column.nullable()
                || !java.util.Objects.equals(normalizeDefault(original.defaultValue()), normalizeDefault(column.defaultValue()));
        return changed
                ? List.of("ALTER TABLE " + table + " MODIFY COLUMN " + columnDefinition(column))
                : List.of();
    }

    @Override
    protected String renameColumnSql(String table, String originalName, ColumnDesign column) {
        return "ALTER TABLE " + table + " CHANGE COLUMN " + quoteIdentifier(originalName) + " " + columnDefinition(column);
    }

    @Override
    protected boolean renameIncludesDefinition() {
        return true;
    }

    @Override
    protected String dropIndexSql(String table, String indexName) {
        return "DROP INDEX " + quoteIdentifier(indexName) + " ON " + table;
    }

    @Override
    public String literal(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        return super.literal(value);
    }
}
