package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;

import java.util.List;
import java.util.Locale;

public class DamengDialect extends DefaultDialect {
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
}
