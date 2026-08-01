package com.example.dbadmin.core;

import java.util.Locale;

public enum SchemaObjectOperation {
    LIST,
    DETAIL,
    SOURCE,
    CREATE,
    REPLACE,
    DROP,
    INVOKE,
    REFRESH,
    ENABLE,
    DISABLE,
    DEPENDENCIES;

    public static SchemaObjectOperation parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("对象操作不能为空。");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("不支持的对象操作：" + value);
        }
    }
}
