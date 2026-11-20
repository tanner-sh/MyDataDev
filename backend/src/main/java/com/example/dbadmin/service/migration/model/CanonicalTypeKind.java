package com.example.dbadmin.service.migration.model;

/**
 * 跨 11 种数据库共享的中立数据类型体系（Canonical IR Type）。
 *
 * <p>每个源方言抽取器把原生列定义归一到这组枚举，每个目标方言映射器再按目标库规则落成最安全、不丢精度的原生类型。</p>
 */
public enum CanonicalTypeKind {
    BOOLEAN,
    INT8,
    INT16,
    INT32,
    INT64,
    UINT64,
    DECIMAL,
    FLOAT32,
    FLOAT64,
    CHAR,
    VARCHAR,
    TEXT,
    CLOB,
    BINARY,
    VARBINARY,
    BLOB,
    DATE,
    TIME,
    DATETIME,
    TIMESTAMP_TZ,
    INTERVAL,
    JSON,
    XML,
    UUID,
    RAW_UNSUPPORTED;

    public boolean isInteger() {
        return this == INT8 || this == INT16 || this == INT32 || this == INT64 || this == UINT64;
    }

    public boolean isNumeric() {
        return isInteger() || this == DECIMAL || this == FLOAT32 || this == FLOAT64;
    }

    public boolean isText() {
        return this == CHAR || this == VARCHAR || this == TEXT || this == CLOB || this == JSON || this == XML || this == UUID;
    }

    public boolean isBinary() {
        return this == BINARY || this == VARBINARY || this == BLOB;
    }

    public boolean isLob() {
        return this == CLOB || this == BLOB || this == TEXT;
    }

    public boolean isTemporal() {
        return this == DATE || this == TIME || this == DATETIME || this == TIMESTAMP_TZ;
    }
}
