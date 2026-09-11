package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.util.List;
import java.util.Locale;
import java.util.HexFormat;

public class DamengDialect extends DefaultDialect {
    /** 达梦沿用 Oracle 那套折叠规则。 */
    @Override
    public String foldUnquotedIdentifier(String identifier) {
        return identifier == null ? null : identifier.toUpperCase(Locale.ROOT);
    }

    /** 与备份一直以来的判断一致，达梦逐行写。 */
    @Override
    public boolean supportsMultiRowValues() {
        return false;
    }


    @Override
    public String castToText(String expression) {
        return "TO_CHAR(" + expression + ")";
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, false, List.of(), List.of(), SchemaObjectCapabilities.oracleFamily());
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("dm") || type.equals("dameng") || url.startsWith("jdbc:dm:");
    }

    @Override
    protected List<String> alterColumnSql(String table, String columnName, ColumnInfo original, ColumnDesign column) {
        boolean changed = !sameType(original, column)
                || original.nullable() != column.nullable()
                || !java.util.Objects.equals(normalizeDefault(original.defaultValue()), normalizeDefault(column.defaultValue()));
        return changed
                ? List.of("ALTER TABLE " + table + " MODIFY " + columnDefinition(column))
                : List.of();
    }

    @Override
    public String literal(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        return super.literal(value);
    }

    @Override
    public String scriptBinaryLiteral(byte[] value) {
        return "hextoraw('" + HexFormat.of().formatHex(value) + "')";
    }
}
