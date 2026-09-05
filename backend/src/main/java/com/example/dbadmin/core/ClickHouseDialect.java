package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.util.List;
import java.util.Locale;
import java.util.HexFormat;

public class ClickHouseDialect extends DefaultDialect {

    @Override
    public String castToText(String expression) {
        return "toString(" + expression + ")";
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("clickhouse") || url.startsWith("jdbc:clickhouse:");
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, false, false, true, List.of(), List.of(), SchemaObjectCapabilities.clickHouse());
    }

    /**
     * ClickHouse 与 MySQL 一样在字符串字面量里解释反斜杠转义，理由见 {@link MySqlDialect#literal}。
     *
     * <p>不需要像 MySQL 那样再分出 scriptLiteral：ClickHouse 没有可以关掉反斜杠转义的会话开关，
     * 翻倍在任何会话下都还原成同一个值。</p>
     */
    @Override
    public String literal(Object value) {
        if (value instanceof CharSequence text) {
            return "'" + text.toString().replace("\\", "\\\\").replace("'", "''") + "'";
        }
        return super.literal(value);
    }

    @Override
    public String scriptBinaryLiteral(byte[] value) {
        return "unhex('" + HexFormat.of().formatHex(value) + "')";
    }
}
