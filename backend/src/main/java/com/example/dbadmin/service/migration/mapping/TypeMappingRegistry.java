package com.example.dbadmin.service.migration.mapping;

import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalTable;
import com.example.dbadmin.service.migration.model.CanonicalSchema.LengthUnit;
import com.example.dbadmin.service.migration.model.CanonicalTypeKind;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 11 种数据库 × 25 种中立类型的核心映射引擎。
 *
 * <p>集中守护所有高危数据保真边界：
 * <ul>
 *   <li>Oracle {@code DATE} → 非 Oracle 统一落 {@code DATETIME/TIMESTAMP(0)}（绝不丢时分秒）；</li>
 *   <li>Oracle 裸 {@code NUMBER} → PG 无损 {@code NUMERIC}，MySQL/MSSQL 落 {@code DECIMAL(p,s)} 并预警可覆写；</li>
 *   <li>Oracle {@code NUMBER(p,s)} 中 {@code s > p} 或 {@code s < 0} 自动换算合法 DECIMAL；</li>
 *   <li>{@code CLOB}/{@code BLOB} → MySQL 系强制 {@code LONGTEXT}/{@code LONGBLOB}（绝不用 64KB {@code TEXT/BLOB}）；</li>
 *   <li>{@code TIMESTAMP(9)} 纳秒截断预警；{@code TIMESTAMP_TZ} 在 MySQL 落 {@code DATETIME(6)} 避开 2038 上限；</li>
 *   <li>MySQL InnoDB 65535 字节单行宽预算自动平衡，超宽非主键 {@code VARCHAR} 自动升级为 {@code TEXT}/{@code MEDIUMTEXT}。</li>
 * </ul></p>
 */
@Component
public class TypeMappingRegistry {
    /** MySQL utf8mb4 4字节/字符下的保守单行宽上限（留余量给空值位图、头部与非字符列）。 */
    static final int MYSQL_SAFE_ROW_BYTES = 58_000;

    public record MappedColumn(
            CanonicalColumn source,
            String targetColumnName,
            boolean nameTruncated,
            String targetSqlType,
            boolean overridden,
            boolean nullable,
            int effectiveTemporalFsp,
            String severity,
            String findingCode,
            String note
    ) {
        public MappedColumn withTargetSqlType(String newType, String newSeverity, String newCode, String newNote) {
            return new MappedColumn(source, targetColumnName, nameTruncated, newType, overridden, nullable,
                    effectiveTemporalFsp, newSeverity, newCode, newNote);
        }
    }

    public List<MappedColumn> mapTableColumns(
            DbTargetDialect source,
            DbTargetDialect target,
            CanonicalTable table,
            MigrationOptions options
    ) {
        Set<String> pkSet = new HashSet<>();
        for (String pk : table.primaryKeyColumns()) {
            if (pk != null) pkSet.add(pk.toLowerCase(Locale.ROOT));
        }
        Set<String> indexedSet = new HashSet<>(pkSet);
        for (var idx : table.indexes()) {
            for (var col : idx.columns()) {
                if (col.name() != null) indexedSet.add(col.name().toLowerCase(Locale.ROOT));
            }
        }

        List<MappedColumn> mapped = new ArrayList<>(table.columns().size());
        for (CanonicalColumn col : table.columns()) {
            var mappedId = IdentifierMapper.map(col.name(), target, options.identifierCasePolicy());
            String override = findOverride(options.columnTypeOverrides(), table.name(), col.name());
            if (override != null) {
                int fsp = col.fractionalSeconds() == null ? target.maxTemporalFsp() : Math.min(col.fractionalSeconds(), target.maxTemporalFsp());
                mapped.add(new MappedColumn(
                        col, mappedId.targetName(), mappedId.truncated(), override, true,
                        resolveNullable(col, target, options), fsp, "OK", "USER_OVERRIDE", "已应用手工指定的目标列类型"
                ));
                continue;
            }
            boolean isIndexed = indexedSet.contains(col.name().toLowerCase(Locale.ROOT));
            mapped.add(mapSingleColumn(source, target, col, mappedId, options, isIndexed));
        }

        if (target.isMySqlFamily()) {
            adjustMySqlRowWidth(mapped, pkSet);
        }
        return List.copyOf(mapped);
    }

    private MappedColumn mapSingleColumn(
            DbTargetDialect source,
            DbTargetDialect target,
            CanonicalColumn col,
            IdentifierMapper.MappedIdentifier mappedId,
            MigrationOptions options,
            boolean isIndexed
    ) {
        boolean nullable = resolveNullable(col, target, options);
        int srcFsp = col.fractionalSeconds() == null ? 6 : col.fractionalSeconds();
        int effFsp = Math.min(srcFsp, target.maxTemporalFsp());

        // 同家族高保真捷径（如 Oracle <-> OB-Oracle <-> DM、MySQL <-> OB-MySQL <-> MariaDB）
        if (source != null && source.sameFamily(target) && canKeepNativeInSameFamily(source, target, col)) {
            String preserved = sanitizeSameFamilyNativeType(source, target, col);
            if (preserved != null) {
                String severity = "OK";
                String code = "SAME_FAMILY_PRESERVED";
                String note = "同家族高保真保留源类型";
                if (col.canonicalType().isTemporal() && srcFsp > target.maxTemporalFsp()) {
                    severity = "WARNING";
                    code = "TEMPORAL_PRECISION_CLAMPED";
                    note = "源时间精度(" + srcFsp + ")超过目标上限(" + target.maxTemporalFsp() + ")，将截断至 " + effFsp + " 位小数秒";
                }
                return new MappedColumn(col, mappedId.targetName(), mappedId.truncated(), preserved, false, nullable, effFsp, severity, code, note);
            }
        }

        return switch (target.family()) {
            case MYSQL -> mapToMySqlFamily(col, mappedId, target, options, nullable, effFsp, isIndexed);
            case ORACLE -> mapToOracleFamily(col, mappedId, target, nullable, effFsp, isIndexed);
            case POSTGRESQL -> mapToPostgres(col, mappedId, nullable, effFsp);
            case SQLSERVER -> mapToSqlServer(col, mappedId, options, nullable, effFsp, isIndexed);
            case SQLITE -> mapToSqlite(col, mappedId, nullable, effFsp);
            case H2 -> mapToH2(col, mappedId, options, nullable, effFsp, isIndexed);
            case CLICKHOUSE -> mapToClickHouse(col, mappedId, options, nullable, effFsp);
        };
    }

    private MappedColumn mapToMySqlFamily(
            CanonicalColumn col,
            IdentifierMapper.MappedIdentifier id,
            DbTargetDialect target,
            MigrationOptions options,
            boolean nullable,
            int effFsp,
            boolean isIndexed
    ) {
        String severity = "OK";
        String code = "MAPPED";
        String note = null;
        String sqlType = switch (col.canonicalType()) {
            case BOOLEAN -> "TINYINT(1)";
            case INT8 -> "TINYINT";
            case INT16 -> col.unsigned() ? "SMALLINT UNSIGNED" : "SMALLINT";
            case INT32 -> col.unsigned() ? "INT UNSIGNED" : "INT";
            case INT64 -> col.unsigned() ? "BIGINT UNSIGNED" : "BIGINT";
            case UINT64 -> "BIGINT UNSIGNED";
            case DECIMAL -> {
                if (col.unboundedDecimal()) {
                    int p = Math.min(options.unboundedNumberPrecision(), 65);
                    int s = Math.min(options.unboundedNumberScale(), Math.min(p, 30));
                    severity = "WARNING";
                    code = "UNBOUNDED_NUMBER";
                    note = "源列为无精度 NUMBER/NUMERIC，默认映射为 DECIMAL(" + p + "," + s + ")，如存在更大精度小数请手工覆写";
                    yield "DECIMAL(" + p + "," + s + ")";
                }
                int[] ps = normalizeDecimalPrecisionScale(col.precision(), col.scale(), 65, 30);
                if (col.scale() != null && (col.scale() < 0 || (col.precision() != null && col.scale() > col.precision()))) {
                    severity = "WARNING";
                    code = "DECIMAL_SCALE_ADJUSTED";
                    note = "原精度(" + col.precision() + "," + col.scale() + ")已换算为 MySQL 合法的 DECIMAL(" + ps[0] + "," + ps[1] + ")";
                }
                yield "DECIMAL(" + ps[0] + "," + ps[1] + ")";
            }
            case FLOAT32 -> "FLOAT";
            case FLOAT64 -> "DOUBLE";
            case CHAR -> {
                int len = col.length() == null || col.length() <= 0 ? 1 : col.length();
                if (len <= 255) yield "CHAR(" + len + ")";
                yield "VARCHAR(" + Math.min(len, 16000) + ")";
            }
            case VARCHAR -> {
                int len = col.length() == null || col.length() <= 0 ? 255 : col.length();
                if (len > 16000) {
                    severity = "WARNING";
                    code = "VARCHAR_PROMOTED_TO_TEXT";
                    note = "源 VARCHAR(" + len + ") 超过 utf8mb4 行宽安全上限，升级为 MEDIUMTEXT";
                    yield "MEDIUMTEXT";
                }
                yield "VARCHAR(" + len + ")";
            }
            case TEXT, CLOB -> "LONGTEXT";
            case BINARY -> {
                int len = col.length() == null || col.length() <= 0 ? 1 : col.length();
                yield len <= 255 ? "BINARY(" + len + ")" : "VARBINARY(" + Math.min(len, 65000) + ")";
            }
            case VARBINARY -> {
                int len = col.length() == null || col.length() <= 0 ? 255 : col.length();
                yield len <= 16000 ? "VARBINARY(" + len + ")" : "LONGBLOB";
            }
            case BLOB -> "LONGBLOB";
            case DATE -> "DATE";
            case TIME -> effFsp > 0 ? "TIME(" + effFsp + ")" : "TIME";
            case DATETIME -> {
                int srcFsp = col.fractionalSeconds() == null ? 0 : col.fractionalSeconds();
                if (srcFsp > 6) {
                    severity = "WARNING";
                    code = "TEMPORAL_PRECISION_CLAMPED";
                    note = "源时间精度(" + srcFsp + ")超过 MySQL 最大 6 位微秒，第 7~9 位小数秒将被截断";
                } else if ("DATE".equalsIgnoreCase(col.sourceNativeType())) {
                    note = "Oracle DATE 含时分秒，已安全映射为 DATETIME 保留完整时间";
                }
                yield effFsp > 0 ? "DATETIME(" + effFsp + ")" : "DATETIME";
            }
            case TIMESTAMP_TZ -> {
                severity = "WARNING";
                code = "TIMESTAMP_TZ_DOWNGRADED";
                note = "MySQL DATETIME 不存储时区偏移（并避开 TIMESTAMP 2038 年上限），数据将按「" + options.timezonePolicy() + "」策略归一化写入 DATETIME(" + effFsp + ")";
                yield effFsp > 0 ? "DATETIME(" + effFsp + ")" : "DATETIME";
            }
            case INTERVAL -> {
                severity = "WARNING";
                code = "INTERVAL_TO_VARCHAR";
                note = "MySQL 不支持原生 INTERVAL 列，转为 VARCHAR(128) 保存 ISO/原始文本";
                yield "VARCHAR(128)";
            }
            case JSON -> "JSON";
            case XML -> {
                severity = "WARNING";
                code = "XML_TO_LONGTEXT";
                note = "MySQL 无原生 XML 列类型，以 LONGTEXT 原样保留 XML 内容";
                yield "LONGTEXT";
            }
            case UUID -> "CHAR(36)";
            case RAW_UNSUPPORTED -> {
                severity = "WARNING";
                code = "UNSUPPORTED_TYPE_FALLBACK";
                note = "源类型「" + col.sourceNativeType() + "」在 MySQL 无直接等价类型，降级为 LONGTEXT，请确认是否需手工改造";
                yield "LONGTEXT";
            }
        };
        return new MappedColumn(col, id.targetName(), id.truncated(), sqlType, false, nullable, effFsp, severity, code, note);
    }

    private MappedColumn mapToOracleFamily(
            CanonicalColumn col,
            IdentifierMapper.MappedIdentifier id,
            DbTargetDialect target,
            boolean nullable,
            int effFsp,
            boolean isIndexed
    ) {
        String severity = "OK";
        String code = "MAPPED";
        String note = null;
        String sqlType = switch (col.canonicalType()) {
            case BOOLEAN -> "NUMBER(1)";
            case INT8 -> "NUMBER(3)";
            case INT16 -> "NUMBER(5)";
            case INT32 -> "NUMBER(10)";
            case INT64 -> "NUMBER(19)";
            case UINT64 -> "NUMBER(20)";
            case DECIMAL -> {
                if (col.unboundedDecimal()) yield "NUMBER";
                int[] ps = normalizeDecimalPrecisionScale(col.precision(), col.scale(), 38, 38);
                if (col.precision() != null && col.precision() > 38) {
                    severity = "WARNING";
                    code = "DECIMAL_PRECISION_CLAMPED";
                    note = "源精度 " + col.precision() + " 超过 Oracle NUMBER 最大 38 位，已映射为无限制 NUMBER";
                    yield "NUMBER";
                }
                yield "NUMBER(" + ps[0] + "," + ps[1] + ")";
            }
            case FLOAT32 -> "BINARY_FLOAT";
            case FLOAT64 -> "BINARY_DOUBLE";
            case CHAR -> {
                int len = col.length() == null || col.length() <= 0 ? 1 : Math.min(col.length(), 2000);
                yield target == DbTargetDialect.DAMENG ? "CHAR(" + len + ")" : "CHAR(" + len + " CHAR)";
            }
            case VARCHAR -> {
                int len = col.length() == null || col.length() <= 0 ? 255 : col.length();
                if (len <= 4000) {
                    yield target == DbTargetDialect.DAMENG
                            ? "VARCHAR(" + len + ")"
                            : "VARCHAR2(" + Math.min(len, 1000) + " CHAR)"; // 1000 CHAR * 4 <= 4000 BYTE 安全线
                }
                severity = "WARNING";
                code = "VARCHAR_PROMOTED_TO_CLOB";
                note = "源 VARCHAR(" + len + ") 超过 Oracle VARCHAR2(4000 BYTE) 限制，自动升级为 CLOB";
                yield "CLOB";
            }
            case TEXT -> {
                if (isIndexed) {
                    severity = "WARNING";
                    code = "INDEXED_TEXT_BOUNDED";
                    note = "索引/主键涉及无长度 TEXT 列，Oracle 不支持直接索引 CLOB，已自动映射为 VARCHAR2(1000 CHAR)";
                    yield target == DbTargetDialect.DAMENG ? "VARCHAR(1000)" : "VARCHAR2(1000 CHAR)";
                }
                yield "CLOB";
            }
            case CLOB -> "CLOB";
            case BINARY, VARBINARY -> {
                int len = col.length() == null || col.length() <= 0 ? 255 : col.length();
                yield len <= 2000 ? "RAW(" + len + ")" : "BLOB";
            }
            case BLOB -> "BLOB";
            case DATE -> "DATE";
            case TIME -> {
                severity = "WARNING";
                code = "TIME_TO_VARCHAR";
                note = "Oracle 无独立 TIME 类型，转为 VARCHAR2(32) 保留完整时间字符串";
                yield "VARCHAR2(32)";
            }
            case DATETIME -> effFsp == 0 ? "DATE" : "TIMESTAMP(" + effFsp + ")";
            case TIMESTAMP_TZ -> "TIMESTAMP(" + effFsp + ") WITH TIME ZONE";
            case INTERVAL -> "VARCHAR2(128)";
            case JSON -> "CLOB";
            case XML -> "CLOB";
            case UUID -> "VARCHAR2(36)";
            case RAW_UNSUPPORTED -> {
                severity = "WARNING";
                code = "UNSUPPORTED_TYPE_FALLBACK";
                note = "源类型「" + col.sourceNativeType() + "」在 Oracle 系降级为 CLOB";
                yield "CLOB";
            }
        };
        // 对 VARCHAR2(len CHAR) 且 len 在 1001..4000 时，若源本身按 CHAR 计数需防 4000 字节溢出
        if (col.canonicalType() == CanonicalTypeKind.VARCHAR && col.length() != null && col.length() > 1000 && col.length() <= 4000 && target != DbTargetDialect.DAMENG) {
            sqlType = "CLOB";
            severity = "WARNING";
            code = "VARCHAR_UTF8_BYTE_GUARD";
            note = "源 VARCHAR(" + col.length() + ") 在 AL32UTF8 下最多占 " + (col.length() * 4) + " 字节，可能超出标准 Oracle 4000 字节上限，已安全升级为 CLOB（可按需手工改回 VARCHAR2）";
        }
        return new MappedColumn(col, id.targetName(), id.truncated(), sqlType, false, nullable, effFsp, severity, code, note);
    }

    private MappedColumn mapToPostgres(
            CanonicalColumn col,
            IdentifierMapper.MappedIdentifier id,
            boolean nullable,
            int effFsp
    ) {
        String severity = "OK";
        String code = "MAPPED";
        String note = null;
        String sqlType = switch (col.canonicalType()) {
            case BOOLEAN -> "BOOLEAN";
            case INT8, INT16 -> "SMALLINT";
            case INT32 -> "INTEGER";
            case INT64 -> "BIGINT";
            case UINT64 -> "NUMERIC(20,0)";
            case DECIMAL -> {
                if (col.unboundedDecimal()) yield "NUMERIC";
                int p = col.precision() == null || col.precision() <= 0 ? 38 : col.precision();
                int s = col.scale() == null ? 0 : col.scale();
                if (s < 0 || s > p) {
                    int[] ps = normalizeDecimalPrecisionScale(p, s, 1000, 1000);
                    yield "NUMERIC(" + ps[0] + "," + ps[1] + ")";
                }
                yield "NUMERIC(" + p + "," + s + ")";
            }
            case FLOAT32 -> "REAL";
            case FLOAT64 -> "DOUBLE PRECISION";
            case CHAR -> "CHAR(" + (col.length() == null || col.length() <= 0 ? 1 : col.length()) + ")";
            case VARCHAR -> col.length() == null || col.length() <= 0 ? "TEXT" : "VARCHAR(" + col.length() + ")";
            case TEXT, CLOB -> "TEXT";
            case BINARY, VARBINARY, BLOB -> "BYTEA";
            case DATE -> "DATE";
            case TIME -> effFsp > 0 ? "TIME(" + effFsp + ")" : "TIME";
            case DATETIME -> {
                int srcFsp = col.fractionalSeconds() == null ? 0 : col.fractionalSeconds();
                if (srcFsp > 6) {
                    severity = "WARNING";
                    code = "TEMPORAL_PRECISION_CLAMPED";
                    note = "源时间精度(" + srcFsp + ")超过 PostgreSQL 最大 6 位微秒，第 7~9 位将被截断";
                }
                yield "TIMESTAMP(" + effFsp + ")";
            }
            case TIMESTAMP_TZ -> "TIMESTAMPTZ(" + effFsp + ")";
            case INTERVAL -> "INTERVAL";
            case JSON -> "JSONB";
            case XML -> "XML";
            case UUID -> "UUID";
            case RAW_UNSUPPORTED -> {
                severity = "WARNING";
                code = "UNSUPPORTED_TYPE_FALLBACK";
                note = "源类型「" + col.sourceNativeType() + "」在 PostgreSQL 降级为 TEXT";
                yield "TEXT";
            }
        };
        return new MappedColumn(col, id.targetName(), id.truncated(), sqlType, false, nullable, effFsp, severity, code, note);
    }

    private MappedColumn mapToSqlServer(
            CanonicalColumn col,
            IdentifierMapper.MappedIdentifier id,
            MigrationOptions options,
            boolean nullable,
            int effFsp,
            boolean isIndexed
    ) {
        String severity = "OK";
        String code = "MAPPED";
        String note = null;
        String sqlType = switch (col.canonicalType()) {
            case BOOLEAN -> "BIT";
            case INT8, INT16 -> "SMALLINT";
            case INT32 -> "INT";
            case INT64 -> "BIGINT";
            case UINT64 -> "DECIMAL(20,0)";
            case DECIMAL -> {
                if (col.unboundedDecimal()) {
                    int p = Math.min(options.unboundedNumberPrecision(), 38);
                    int s = Math.min(options.unboundedNumberScale(), p);
                    severity = "WARNING";
                    code = "UNBOUNDED_NUMBER";
                    note = "无精度 NUMBER 在 SQL Server 默认映射为 DECIMAL(" + p + "," + s + ")";
                    yield "DECIMAL(" + p + "," + s + ")";
                }
                int[] ps = normalizeDecimalPrecisionScale(col.precision(), col.scale(), 38, 38);
                yield "DECIMAL(" + ps[0] + "," + ps[1] + ")";
            }
            case FLOAT32 -> "REAL";
            case FLOAT64 -> "FLOAT";
            case CHAR -> {
                int len = col.length() == null || col.length() <= 0 ? 1 : col.length();
                yield len <= 4000 ? "NCHAR(" + len + ")" : "NVARCHAR(MAX)";
            }
            case VARCHAR -> {
                int len = col.length() == null || col.length() <= 0 ? 255 : col.length();
                yield len <= 4000 ? "NVARCHAR(" + len + ")" : "NVARCHAR(MAX)";
            }
            case TEXT -> {
                if (isIndexed) {
                    severity = "WARNING";
                    code = "INDEXED_TEXT_BOUNDED";
                    note = "索引/主键涉及无长度 TEXT 列，SQL Server 不支持索引 NVARCHAR(MAX)，已映射为 NVARCHAR(450)";
                    yield "NVARCHAR(450)";
                }
                yield "NVARCHAR(MAX)";
            }
            case CLOB, JSON -> "NVARCHAR(MAX)";
            case XML -> "XML";
            case BINARY -> {
                int len = col.length() == null || col.length() <= 0 ? 1 : col.length();
                yield len <= 8000 ? "BINARY(" + len + ")" : "VARBINARY(MAX)";
            }
            case VARBINARY -> {
                int len = col.length() == null || col.length() <= 0 ? 255 : col.length();
                yield len <= 8000 ? "VARBINARY(" + len + ")" : "VARBINARY(MAX)";
            }
            case BLOB -> "VARBINARY(MAX)";
            case DATE -> "DATE";
            case TIME -> "TIME(" + effFsp + ")";
            case DATETIME -> {
                int srcFsp = col.fractionalSeconds() == null ? 0 : col.fractionalSeconds();
                if (srcFsp > 7) {
                    severity = "WARNING";
                    code = "TEMPORAL_PRECISION_CLAMPED";
                    note = "源时间精度(" + srcFsp + ")超过 SQL Server 最大 7 位，已截断至 DATETIME2(7)";
                }
                yield "DATETIME2(" + effFsp + ")";
            }
            case TIMESTAMP_TZ -> "DATETIMEOFFSET(" + effFsp + ")";
            case INTERVAL -> "NVARCHAR(128)";
            case UUID -> "UNIQUEIDENTIFIER";
            case RAW_UNSUPPORTED -> {
                severity = "WARNING";
                code = "UNSUPPORTED_TYPE_FALLBACK";
                note = "源类型「" + col.sourceNativeType() + "」在 SQL Server 降级为 NVARCHAR(MAX)";
                yield "NVARCHAR(MAX)";
            }
        };
        return new MappedColumn(col, id.targetName(), id.truncated(), sqlType, false, nullable, effFsp, severity, code, note);
    }

    private MappedColumn mapToSqlite(
            CanonicalColumn col,
            IdentifierMapper.MappedIdentifier id,
            boolean nullable,
            int effFsp
    ) {
        String severity = "OK";
        String code = "MAPPED";
        String note = null;
        String sqlType = switch (col.canonicalType()) {
            case BOOLEAN, INT8, INT16, INT32, INT64 -> "INTEGER";
            case UINT64, DECIMAL -> {
                severity = "WARNING";
                code = "SQLITE_DECIMAL_TEXT";
                note = "SQLite 无原生定点 DECIMAL，为避免浮点精度丢失，高精度数值按 TEXT/NUMERIC 精确文本保留";
                yield "TEXT";
            }
            case FLOAT32, FLOAT64 -> "REAL";
            case CHAR, VARCHAR, TEXT, CLOB, DATE, TIME, DATETIME, TIMESTAMP_TZ, INTERVAL, JSON, XML, UUID -> "TEXT";
            case BINARY, VARBINARY, BLOB -> "BLOB";
            case RAW_UNSUPPORTED -> {
                severity = "WARNING";
                code = "UNSUPPORTED_TYPE_FALLBACK";
                note = "源类型降级为 TEXT";
                yield "TEXT";
            }
        };
        return new MappedColumn(col, id.targetName(), id.truncated(), sqlType, false, nullable, effFsp, severity, code, note);
    }

    private MappedColumn mapToH2(
            CanonicalColumn col,
            IdentifierMapper.MappedIdentifier id,
            MigrationOptions options,
            boolean nullable,
            int effFsp,
            boolean isIndexed
    ) {
        String severity = "OK";
        String code = "MAPPED";
        String note = null;
        String sqlType = switch (col.canonicalType()) {
            case BOOLEAN -> "BOOLEAN";
            case INT8 -> "TINYINT";
            case INT16 -> "SMALLINT";
            case INT32 -> "INTEGER";
            case INT64 -> "BIGINT";
            case UINT64 -> "NUMERIC(20,0)";
            case DECIMAL -> {
                if (col.unboundedDecimal()) {
                    yield "NUMERIC(" + options.unboundedNumberPrecision() + "," + options.unboundedNumberScale() + ")";
                }
                int[] ps = normalizeDecimalPrecisionScale(col.precision(), col.scale(), 65, 65);
                yield "NUMERIC(" + ps[0] + "," + ps[1] + ")";
            }
            case FLOAT32 -> "REAL";
            case FLOAT64 -> "DOUBLE PRECISION";
            case CHAR -> "CHAR(" + (col.length() == null || col.length() <= 0 ? 1 : col.length()) + ")";
            case VARCHAR -> col.length() == null || col.length() <= 0 ? "CLOB" : "VARCHAR(" + col.length() + ")";
            case TEXT -> {
                if (isIndexed) {
                    severity = "WARNING";
                    code = "INDEXED_TEXT_BOUNDED";
                    note = "索引/主键涉及无长度 TEXT 列，H2 不支持索引 CLOB，已自动映射为 VARCHAR(1000)";
                    yield "VARCHAR(1000)";
                }
                yield "CLOB";
            }
            case CLOB, XML -> "CLOB";
            case BINARY -> "BINARY(" + (col.length() == null || col.length() <= 0 ? 1 : col.length()) + ")";
            case VARBINARY -> "VARBINARY(" + (col.length() == null || col.length() <= 0 ? 255 : col.length()) + ")";
            case BLOB -> "BLOB";
            case DATE -> "DATE";
            case TIME -> "TIME(" + effFsp + ")";
            case DATETIME -> "TIMESTAMP(" + effFsp + ")";
            case TIMESTAMP_TZ -> "TIMESTAMP(" + effFsp + ") WITH TIME ZONE";
            case INTERVAL -> "VARCHAR(128)";
            case JSON -> "CLOB";
            case UUID -> "UUID";
            case RAW_UNSUPPORTED -> {
                severity = "WARNING";
                code = "UNSUPPORTED_TYPE_FALLBACK";
                note = "源类型降级为 CLOB";
                yield "CLOB";
            }
        };
        return new MappedColumn(col, id.targetName(), id.truncated(), sqlType, false, nullable, effFsp, severity, code, note);
    }

    private MappedColumn mapToClickHouse(
            CanonicalColumn col,
            IdentifierMapper.MappedIdentifier id,
            MigrationOptions options,
            boolean nullable,
            int effFsp
    ) {
        String severity = "OK";
        String code = "MAPPED";
        String note = null;
        String baseType = switch (col.canonicalType()) {
            case BOOLEAN -> "Bool";
            case INT8 -> "Int8";
            case INT16 -> "Int16";
            case INT32 -> "Int32";
            case INT64 -> "Int64";
            case UINT64 -> "UInt64";
            case DECIMAL -> {
                int p = col.unboundedDecimal() ? Math.min(options.unboundedNumberPrecision(), 38)
                        : (col.precision() == null || col.precision() <= 0 ? 38 : Math.min(col.precision(), 38));
                int s = col.unboundedDecimal() ? Math.min(options.unboundedNumberScale(), p)
                        : Math.max(0, Math.min(col.scale() == null ? 0 : col.scale(), p));
                if (col.unboundedDecimal()) {
                    severity = "WARNING";
                    code = "UNBOUNDED_NUMBER";
                    note = "ClickHouse 不支持无精度小数，默认映射为 Decimal(" + p + "," + s + ")";
                }
                yield "Decimal(" + p + "," + s + ")";
            }
            case FLOAT32 -> "Float32";
            case FLOAT64 -> "Float64";
            case CHAR, VARCHAR, TEXT, CLOB, BINARY, VARBINARY, BLOB, TIME, INTERVAL, JSON, XML -> "String";
            case DATE -> "Date32";
            case DATETIME -> "DateTime64(" + Math.min(effFsp, 9) + ")";
            case TIMESTAMP_TZ -> "DateTime64(" + Math.min(effFsp, 9) + ", 'UTC')";
            case UUID -> "UUID";
            case RAW_UNSUPPORTED -> {
                severity = "WARNING";
                code = "UNSUPPORTED_TYPE_FALLBACK";
                note = "源类型在 ClickHouse 降级为 String";
                yield "String";
            }
        };
        String wrapped = nullable ? "Nullable(" + baseType + ")" : baseType;
        return new MappedColumn(col, id.targetName(), id.truncated(), wrapped, false, nullable, effFsp, severity, code, note);
    }

    /**
     * MySQL InnoDB 单行宽上限（65535 bytes）守护：若总估算行宽超过安全线，按宽度从大到小将非主键 VARCHAR 升级为 TEXT/MEDIUMTEXT。
     */
    private void adjustMySqlRowWidth(List<MappedColumn> columns, Set<String> pkSet) {
        int totalBytes = 0;
        List<Integer> varcharIndices = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            MappedColumn mc = columns.get(i);
            int est = estimateMySqlColumnRowBytes(mc.targetSqlType());
            totalBytes += est;
            if (!mc.overridden()
                    && !pkSet.contains(mc.source().name().toLowerCase(Locale.ROOT))
                    && mc.targetSqlType().toUpperCase(Locale.ROOT).startsWith("VARCHAR(")) {
                varcharIndices.add(i);
            }
        }
        if (totalBytes <= MYSQL_SAFE_ROW_BYTES) return;
        varcharIndices.sort(Comparator.comparingInt((Integer idx) -> estimateMySqlColumnRowBytes(columns.get(idx).targetSqlType())).reversed());
        for (int idx : varcharIndices) {
            if (totalBytes <= MYSQL_SAFE_ROW_BYTES) break;
            MappedColumn mc = columns.get(idx);
            int before = estimateMySqlColumnRowBytes(mc.targetSqlType());
            if (before <= 512) break;
            String promoted = before > 65000 ? "MEDIUMTEXT" : "TEXT";
            columns.set(idx, mc.withTargetSqlType(
                    promoted, "WARNING", "MYSQL_ROW_WIDTH_PROMOTED",
                    "表总行宽超 InnoDB 65535 字节限制，非主键列 " + mc.targetColumnName() + "（原 " + mc.targetSqlType() + "）自动升级为 " + promoted
            ));
            totalBytes = totalBytes - before + 20;
        }
    }

    static int estimateMySqlColumnRowBytes(String sqlType) {
        if (sqlType == null) return 64;
        String u = sqlType.toUpperCase(Locale.ROOT).trim();
        if (u.startsWith("VARCHAR(") || u.startsWith("CHAR(")) {
            int start = u.indexOf('(');
            int end = u.indexOf(')');
            if (start > 0 && end > start) {
                try {
                    int n = Integer.parseInt(u.substring(start + 1, end).trim());
                    return n * 4 + 2;
                } catch (NumberFormatException ignored) {
                }
            }
            return 1024;
        }
        if (u.startsWith("VARBINARY(") || u.startsWith("BINARY(")) {
            int start = u.indexOf('(');
            int end = u.indexOf(')');
            if (start > 0 && end > start) {
                try {
                    return Integer.parseInt(u.substring(start + 1, end).trim()) + 2;
                } catch (NumberFormatException ignored) {
                }
            }
            return 256;
        }
        if (u.contains("TEXT") || u.contains("BLOB") || u.equals("JSON")) return 20;
        return 16;
    }

    private static boolean canKeepNativeInSameFamily(DbTargetDialect source, DbTargetDialect target, CanonicalColumn col) {
        if (col.sourceNativeType() == null || col.sourceNativeType().isBlank()) return false;
        if (col.canonicalType() == CanonicalTypeKind.RAW_UNSUPPORTED) return false;
        String raw = col.sourceNativeType().trim();
        // 若源原生类型字符串缺失括号参数（如 JDBC DatabaseMetaData 仅返回裸 "NUMERIC"/"VARCHAR"/"BINARY"），
        // 直接透传会丢失精度/小数位或长度，必须交回方言映射器按 canonical 元数据重建完整类型声明。
        if (raw.indexOf('(') < 0) {
            if (col.canonicalType() == CanonicalTypeKind.DECIMAL && !col.unboundedDecimal()
                    && (col.precision() != null || (col.scale() != null && col.scale() != 0))) {
                return false;
            }
            if ((col.canonicalType() == CanonicalTypeKind.CHAR || col.canonicalType() == CanonicalTypeKind.VARCHAR
                    || col.canonicalType() == CanonicalTypeKind.BINARY || col.canonicalType() == CanonicalTypeKind.VARBINARY)
                    && col.length() != null && col.length() > 0 && target != DbTargetDialect.SQLITE) {
                return false;
            }
        }
        if (source.isOracleFamily() && target.isOracleFamily()) {
            // 达梦不认 CHAR/BYTE 语义关键字在部分版本上可能有差异，交给标准 Oracle 家族映射器处理
            if (target == DbTargetDialect.DAMENG && raw.toUpperCase(Locale.ROOT).contains(" CHAR")) {
                return false;
            }
            return true;
        }
        if (source.isMySqlFamily() && target.isMySqlFamily()) {
            return true;
        }
        return source == target;
    }

    private static String sanitizeSameFamilyNativeType(DbTargetDialect source, DbTargetDialect target, CanonicalColumn col) {
        String raw = col.sourceNativeType().trim();
        if (target == DbTargetDialect.DAMENG && raw.toUpperCase(Locale.ROOT).startsWith("TIMESTAMP(")) {
            int fsp = Math.min(col.fractionalSeconds() == null ? 6 : col.fractionalSeconds(), 6);
            return raw.replaceFirst("(?i)TIMESTAMP\\(\\d+\\)", "TIMESTAMP(" + fsp + ")");
        }
        return raw;
    }

    private static boolean resolveNullable(CanonicalColumn col, DbTargetDialect target, MigrationOptions options) {
        if (!col.nullable() && target.isOracleFamily() && col.canonicalType().isText()
                && "ALLOW_NULL".equalsIgnoreCase(options.emptyStringToOraclePolicy())) {
            return true;
        }
        return col.nullable();
    }

    static int[] normalizeDecimalPrecisionScale(Integer precision, Integer scale, int maxP, int maxS) {
        int p = precision == null || precision <= 0 ? 38 : precision;
        int s = scale == null ? 0 : scale;
        if (s < 0) {
            p = p + Math.abs(s);
            s = 0;
        } else if (s > p) {
            p = s;
        }
        p = Math.min(p, maxP);
        s = Math.min(s, Math.min(p, maxS));
        return new int[]{p, s};
    }

    private static String findOverride(Map<String, String> overrides, String tableName, String columnName) {
        if (overrides == null || overrides.isEmpty()) return null;
        String exactKey = tableName + "." + columnName;
        String val = overrides.get(exactKey);
        if (val != null && !val.isBlank()) return val.trim();
        for (var e : overrides.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(exactKey) && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue().trim();
            }
        }
        return null;
    }
}
