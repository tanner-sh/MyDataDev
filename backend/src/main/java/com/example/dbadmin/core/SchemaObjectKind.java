package com.example.dbadmin.core;

import java.util.Locale;

public enum SchemaObjectKind {
    VIEW,
    MATERIALIZED_VIEW,
    SEQUENCE,
    TRIGGER,
    PROCEDURE,
    FUNCTION;

    public static SchemaObjectKind parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据库对象类型不能为空。");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("不支持的数据库对象类型：" + value);
        }
    }

    public String label() {
        return switch (this) {
            case VIEW -> "视图";
            case MATERIALIZED_VIEW -> "物化视图";
            case SEQUENCE -> "序列";
            case TRIGGER -> "触发器";
            case PROCEDURE -> "存储过程";
            case FUNCTION -> "函数";
        };
    }
}
