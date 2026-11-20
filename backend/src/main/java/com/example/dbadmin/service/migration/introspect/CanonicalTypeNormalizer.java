package com.example.dbadmin.service.migration.introspect;

import com.example.dbadmin.service.migration.model.CanonicalSchema.DefaultExprKind;
import com.example.dbadmin.service.migration.model.CanonicalSchema.LengthUnit;
import com.example.dbadmin.service.migration.model.CanonicalTypeKind;
import com.example.dbadmin.service.migration.model.DbTargetDialect;

import java.sql.Types;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 11 种数据库的原生列类型与默认值表达式归一到 {@link CanonicalTypeKind} 中立体系。
 *
 * <p>纯函数、无 JDBC 连接依赖，覆盖所有高危边界：Oracle {@code DATE} 含时分秒、裸 {@code NUMBER}、
 * {@code NUMBER(10,0)} 超出 32 位整、{@code TIMESTAMP(p)} 精度、MySQL {@code UNSIGNED}、
 * ClickHouse {@code Nullable(...)} / {@code LowCardinality(...)} 解包等。</p>
 */
public final class CanonicalTypeNormalizer {
    private static final Pattern PAREN_PARAMS = Pattern.compile("\\(([^)]+)\\)");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("^[+-]?\\d+(?:\\.\\d+)?$");

    private CanonicalTypeNormalizer() {
    }

    public record NormalizedType(
            CanonicalTypeKind kind,
            Integer length,
            LengthUnit lengthUnit,
            Integer precision,
            Integer scale,
            boolean unboundedDecimal,
            boolean unsigned,
            boolean national,
            Integer fractionalSeconds
    ) {
    }

    public record NormalizedDefault(
            DefaultExprKind kind,
            String raw
    ) {
    }

    public static NormalizedType normalize(
            DbTargetDialect sourceDialect,
            String rawTypeName,
            Integer jdbcType,
            Integer length,
            LengthUnit lengthUnit,
            Integer precision,
            Integer scale,
            boolean unboundedDecimalHint,
            boolean unsignedHint
    ) {
        String raw = rawTypeName == null ? "" : rawTypeName.trim();
        // 解包 ClickHouse Nullable(...) / LowCardinality(...)
        String unwrapped = unwrapClickHouse(raw);
        String upper = unwrapped.toUpperCase(Locale.ROOT);
        boolean unsigned = unsignedHint || upper.contains("UNSIGNED") || upper.startsWith("UINT");
        boolean national = upper.startsWith("NCHAR") || upper.startsWith("NVARCHAR") || upper.startsWith("NCLOB") || upper.startsWith("NTEXT");

        // 从类型名括号中补提取参数（如 VARCHAR2(200 CHAR)、TIMESTAMP(6)、DECIMAL(18,4)、DateTime64(3)）
        Integer parsedFirst = null;
        Integer parsedSecond = null;
        LengthUnit parsedUnit = lengthUnit == null ? LengthUnit.NONE : lengthUnit;
        Matcher m = PAREN_PARAMS.matcher(upper);
        if (m.find()) {
            String inside = m.group(1).trim();
            if (inside.endsWith(" CHAR")) {
                parsedUnit = LengthUnit.CHAR;
                inside = inside.substring(0, inside.length() - 5).trim();
            } else if (inside.endsWith(" BYTE")) {
                parsedUnit = LengthUnit.BYTE;
                inside = inside.substring(0, inside.length() - 5).trim();
            }
            String[] parts = inside.split(",");
            parsedFirst = parseIntOrNull(parts[0]);
            if (parts.length > 1) {
                parsedSecond = parseIntOrNull(parts[1]);
            }
        }

        String base = baseTypeName(upper);
        Integer effLen = length != null && length > 0 ? length : parsedFirst;
        Integer effPrec = precision != null && precision > 0 ? precision : parsedFirst;
        Integer effScale = scale != null ? scale : parsedSecond;

        // 1. Oracle 家族专项高保真规则
        if (sourceDialect != null && sourceDialect.isOracleFamily()) {
            // Oracle DATE 包含年月日时分秒，必须归为 DATETIME(0)，绝不能归为 DATE！
            if (base.equals("DATE")) {
                return new NormalizedType(CanonicalTypeKind.DATETIME, null, LengthUnit.NONE, null, null, false, false, false, 0);
            }
            if (base.equals("NUMBER") || base.equals("NUMERIC") || base.equals("DECIMAL") || base.equals("DEC")) {
                boolean isUnbounded = unboundedDecimalHint
                        || (effPrec == null || effPrec <= 0)
                        || (effScale != null && effScale == -127);
                if (isUnbounded) {
                    return new NormalizedType(CanonicalTypeKind.DECIMAL, null, LengthUnit.NONE, null, null, true, false, false, null);
                }
                int p = effPrec;
                int s = effScale == null ? 0 : effScale;
                if (s == 0) {
                    if (p <= 2) return new NormalizedType(CanonicalTypeKind.INT8, null, LengthUnit.NONE, p, 0, false, false, false, null);
                    if (p <= 4) return new NormalizedType(CanonicalTypeKind.INT16, null, LengthUnit.NONE, p, 0, false, false, false, null);
                    if (p <= 9) return new NormalizedType(CanonicalTypeKind.INT32, null, LengthUnit.NONE, p, 0, false, false, false, null);
                    if (p <= 18) return new NormalizedType(CanonicalTypeKind.INT64, null, LengthUnit.NONE, p, 0, false, false, false, null);
                    return new NormalizedType(CanonicalTypeKind.DECIMAL, null, LengthUnit.NONE, p, 0, false, false, false, null);
                }
                return new NormalizedType(CanonicalTypeKind.DECIMAL, null, LengthUnit.NONE, p, s, false, false, false, null);
            }
            if (base.equals("INTEGER") || base.equals("INT") || base.equals("SMALLINT")) {
                // Oracle INTEGER 实为 NUMBER(38,0)
                return new NormalizedType(CanonicalTypeKind.DECIMAL, null, LengthUnit.NONE, 38, 0, false, false, false, null);
            }
            if (base.equals("FLOAT") || base.equals("BINARY_DOUBLE")) {
                return new NormalizedType(CanonicalTypeKind.FLOAT64, null, LengthUnit.NONE, effPrec, null, false, false, false, null);
            }
            if (base.equals("BINARY_FLOAT")) {
                return new NormalizedType(CanonicalTypeKind.FLOAT32, null, LengthUnit.NONE, null, null, false, false, false, null);
            }
            if (base.equals("VARCHAR2") || base.equals("NVARCHAR2") || base.equals("VARCHAR")) {
                return new NormalizedType(CanonicalTypeKind.VARCHAR, effLen, parsedUnit == LengthUnit.NONE ? LengthUnit.BYTE : parsedUnit,
                        null, null, false, false, national || base.equals("NVARCHAR2"), null);
            }
            if (base.equals("CHAR") || base.equals("NCHAR") || base.equals("CHARACTER")) {
                return new NormalizedType(CanonicalTypeKind.CHAR, effLen == null ? 1 : effLen,
                        parsedUnit == LengthUnit.NONE ? LengthUnit.BYTE : parsedUnit, null, null, false, false, national || base.equals("NCHAR"), null);
            }
            if (base.equals("CLOB") || base.equals("NCLOB") || base.equals("LONG")) {
                return new NormalizedType(CanonicalTypeKind.CLOB, null, LengthUnit.NONE, null, null, false, false, national || base.equals("NCLOB"), null);
            }
            if (base.equals("BLOB") || base.equals("LONG RAW") || base.equals("IMAGE")) {
                return new NormalizedType(CanonicalTypeKind.BLOB, null, LengthUnit.NONE, null, null, false, false, false, null);
            }
            if (base.equals("RAW")) {
                return new NormalizedType(CanonicalTypeKind.VARBINARY, effLen == null ? 2000 : effLen, LengthUnit.BYTE, null, null, false, false, false, null);
            }
            if (upper.startsWith("TIMESTAMP") && upper.contains("TIME ZONE")) {
                int fsp = parsedFirst != null ? parsedFirst : (effScale != null && effScale >= 0 ? effScale : 6);
                return new NormalizedType(CanonicalTypeKind.TIMESTAMP_TZ, null, LengthUnit.NONE, null, null, false, false, false, fsp);
            }
            if (upper.startsWith("TIMESTAMP")) {
                int fsp = parsedFirst != null ? parsedFirst : (effScale != null && effScale >= 0 ? effScale : 6);
                return new NormalizedType(CanonicalTypeKind.DATETIME, null, LengthUnit.NONE, null, null, false, false, false, fsp);
            }
            if (upper.startsWith("INTERVAL")) {
                return new NormalizedType(CanonicalTypeKind.INTERVAL, null, LengthUnit.NONE, null, null, false, false, false, null);
            }
            if (base.equals("XMLTYPE")) {
                return new NormalizedType(CanonicalTypeKind.XML, null, LengthUnit.NONE, null, null, false, false, false, null);
            }
            if (base.equals("ROWID") || base.equals("UROWID")) {
                return new NormalizedType(CanonicalTypeKind.VARCHAR, effLen == null ? 64 : effLen, LengthUnit.CHAR, null, null, false, false, false, null);
            }
            if (base.equals("BFILE") || base.contains("GEOMETRY") || base.equals("ANYDATA")) {
                return new NormalizedType(CanonicalTypeKind.RAW_UNSUPPORTED, null, LengthUnit.NONE, null, null, false, false, false, null);
            }
        }

        // 2. 通用多方言类型归一化（MySQL / OB-MySQL / MariaDB / PG / SQL Server / SQLite / H2 / ClickHouse）
        if (base.equals("BOOL") || base.equals("BOOLEAN")) {
            return new NormalizedType(CanonicalTypeKind.BOOLEAN, null, LengthUnit.NONE, null, null, false, false, false, null);
        }
        if (base.equals("BIT")) {
            if (effLen == null || effLen <= 1) {
                return new NormalizedType(CanonicalTypeKind.BOOLEAN, null, LengthUnit.NONE, null, null, false, false, false, null);
            }
            return new NormalizedType(CanonicalTypeKind.VARBINARY, (effLen + 7) / 8, LengthUnit.BYTE, null, null, false, false, false, null);
        }
        if (base.equals("TINYINT") || base.equals("INT8")) {
            if (sourceDialect != null && sourceDialect.isMySqlFamily() && Integer.valueOf(1).equals(parsedFirst) && !unsigned) {
                return new NormalizedType(CanonicalTypeKind.BOOLEAN, null, LengthUnit.NONE, null, null, false, false, false, null);
            }
            return new NormalizedType(unsigned ? CanonicalTypeKind.INT16 : CanonicalTypeKind.INT8, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
        }
        if (base.equals("UINT8")) {
            return new NormalizedType(CanonicalTypeKind.INT16, null, LengthUnit.NONE, null, null, false, true, false, null);
        }
        if (base.equals("SMALLINT") || base.equals("INT2") || base.equals("INT16") || base.equals("YEAR")) {
            return new NormalizedType(unsigned ? CanonicalTypeKind.INT32 : CanonicalTypeKind.INT16, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
        }
        if (base.equals("UINT16")) {
            return new NormalizedType(CanonicalTypeKind.INT32, null, LengthUnit.NONE, null, null, false, true, false, null);
        }
        if (base.equals("MEDIUMINT") || base.equals("INT") || base.equals("INTEGER") || base.equals("INT4")
                || base.equals("INT32") || base.equals("SERIAL") || base.equals("SERIAL4")) {
            return new NormalizedType(unsigned ? CanonicalTypeKind.INT64 : CanonicalTypeKind.INT32, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
        }
        if (base.equals("UINT32")) {
            return new NormalizedType(CanonicalTypeKind.INT64, null, LengthUnit.NONE, null, null, false, true, false, null);
        }
        if (base.equals("BIGINT") || base.equals("INT8_PG") || base.equals("INT64") || base.equals("BIGSERIAL") || base.equals("SERIAL8")) {
            return new NormalizedType(unsigned ? CanonicalTypeKind.UINT64 : CanonicalTypeKind.INT64, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
        }
        if (base.equals("UINT64")) {
            return new NormalizedType(CanonicalTypeKind.UINT64, null, LengthUnit.NONE, null, null, false, true, false, null);
        }
        if (base.equals("INT128") || base.equals("UINT128") || base.equals("INT256") || base.equals("UINT256")) {
            return new NormalizedType(CanonicalTypeKind.DECIMAL, null, LengthUnit.NONE, 38, 0, false, unsigned, false, null);
        }
        if (base.equals("DECIMAL") || base.equals("NUMERIC") || base.equals("DEC") || base.equals("FIXED")
                || base.equals("NUMBER") || base.equals("MONEY") || base.equals("SMALLMONEY")
                || base.startsWith("DECIMAL32") || base.startsWith("DECIMAL64") || base.startsWith("DECIMAL128") || base.startsWith("DECIMAL256")) {
            if (base.equals("MONEY")) {
                return new NormalizedType(CanonicalTypeKind.DECIMAL, null, LengthUnit.NONE, 19, 4, false, false, false, null);
            }
            if (base.equals("SMALLMONEY")) {
                return new NormalizedType(CanonicalTypeKind.DECIMAL, null, LengthUnit.NONE, 10, 4, false, false, false, null);
            }
            boolean isUnbounded = unboundedDecimalHint || effPrec == null || effPrec <= 0 || effPrec > 1000;
            return new NormalizedType(CanonicalTypeKind.DECIMAL, null, LengthUnit.NONE,
                    isUnbounded ? null : effPrec, isUnbounded ? null : (effScale == null ? 0 : effScale),
                    isUnbounded, unsigned, false, null);
        }
        if (base.equals("FLOAT") || base.equals("REAL") || base.equals("FLOAT4") || base.equals("FLOAT32")) {
            return new NormalizedType(CanonicalTypeKind.FLOAT32, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
        }
        if (base.equals("DOUBLE") || base.equals("DOUBLE PRECISION") || base.equals("FLOAT8") || base.equals("FLOAT64")) {
            return new NormalizedType(CanonicalTypeKind.FLOAT64, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
        }
        if (base.equals("JSON") || base.equals("JSONB")) {
            return new NormalizedType(CanonicalTypeKind.JSON, null, LengthUnit.NONE, null, null, false, false, false, null);
        }
        if (base.equals("XML") || base.equals("XMLTYPE")) {
            return new NormalizedType(CanonicalTypeKind.XML, null, LengthUnit.NONE, null, null, false, false, false, null);
        }
        if (base.equals("UUID") || base.equals("UNIQUEIDENTIFIER")) {
            return new NormalizedType(CanonicalTypeKind.UUID, 36, LengthUnit.CHAR, null, null, false, false, false, null);
        }
        if (base.equals("CHAR") || base.equals("NCHAR") || base.equals("CHARACTER") || base.equals("BPCHAR") || base.equals("FIXEDSTRING")) {
            return new NormalizedType(CanonicalTypeKind.CHAR, effLen == null ? 1 : effLen,
                    parsedUnit == LengthUnit.NONE ? LengthUnit.CHAR : parsedUnit, null, null, false, false, national, null);
        }
        if (base.equals("VARCHAR") || base.equals("NVARCHAR") || base.equals("VARCHAR2") || base.equals("NVARCHAR2")
                || base.equals("CHARACTER VARYING") || base.equals("SYSNAME")) {
            // SQL Server NVARCHAR(MAX) / VARCHAR(MAX) 在元数据中表现为 length == -1 或 >= 1_000_000_000
            if (upper.contains("(MAX)") || (effLen != null && (effLen < 0 || effLen >= 100_000_000))) {
                return new NormalizedType(CanonicalTypeKind.CLOB, null, LengthUnit.NONE, null, null, false, false, national, null);
            }
            if (effLen == null || effLen <= 0) {
                return new NormalizedType(CanonicalTypeKind.TEXT, null, LengthUnit.NONE, null, null, false, false, national, null);
            }
            return new NormalizedType(CanonicalTypeKind.VARCHAR, effLen,
                    parsedUnit == LengthUnit.NONE ? LengthUnit.CHAR : parsedUnit, null, null, false, false, national, null);
        }
        if (base.equals("ENUM") || base.equals("SET")) {
            return new NormalizedType(CanonicalTypeKind.VARCHAR, effLen == null ? 255 : Math.max(effLen, 255),
                    LengthUnit.CHAR, null, null, false, false, false, null);
        }
        if (base.equals("TINYTEXT") || base.equals("TEXT") || base.equals("NTEXT") || base.equals("STRING")) {
            return new NormalizedType(CanonicalTypeKind.TEXT, null, LengthUnit.NONE, null, null, false, false, national, null);
        }
        if (base.equals("MEDIUMTEXT") || base.equals("LONGTEXT") || base.equals("CLOB") || base.equals("NCLOB")
                || base.equals("CHARACTER LARGE OBJECT")) {
            return new NormalizedType(CanonicalTypeKind.CLOB, null, LengthUnit.NONE, null, null, false, false, national, null);
        }
        if (base.equals("BINARY")) {
            return new NormalizedType(CanonicalTypeKind.BINARY, effLen == null ? 1 : effLen, LengthUnit.BYTE, null, null, false, false, false, null);
        }
        if (base.equals("VARBINARY") || base.equals("RAW")) {
            if (upper.contains("(MAX)") || (effLen != null && (effLen < 0 || effLen >= 100_000_000))) {
                return new NormalizedType(CanonicalTypeKind.BLOB, null, LengthUnit.NONE, null, null, false, false, false, null);
            }
            return new NormalizedType(CanonicalTypeKind.VARBINARY, effLen == null ? 255 : effLen, LengthUnit.BYTE, null, null, false, false, false, null);
        }
        if (base.equals("BLOB") || base.equals("TINYBLOB") || base.equals("MEDIUMBLOB") || base.equals("LONGBLOB")
                || base.equals("BYTEA") || base.equals("IMAGE") || base.equals("BINARY LARGE OBJECT") || base.equals("LONGVARBINARY")) {
            return new NormalizedType(CanonicalTypeKind.BLOB, null, LengthUnit.NONE, null, null, false, false, false, null);
        }
        if (base.equals("DATE") || base.equals("DATE32")) {
            return new NormalizedType(CanonicalTypeKind.DATE, null, LengthUnit.NONE, null, null, false, false, false, null);
        }
        if (base.equals("TIME") || base.equals("TIME WITHOUT TIME ZONE") || base.equals("TIME WITH TIME ZONE") || base.equals("TIMETZ")) {
            int fsp = parsedFirst != null ? parsedFirst : (effScale != null && effScale >= 0 ? effScale : 0);
            return new NormalizedType(CanonicalTypeKind.TIME, null, LengthUnit.NONE, null, null, false, false, false, fsp);
        }
        if (base.equals("TIMESTAMPTZ") || base.equals("TIMESTAMP WITH TIME ZONE") || base.equals("DATETIMEOFFSET")) {
            int fsp = parsedFirst != null ? parsedFirst : (effScale != null && effScale >= 0 ? effScale : 6);
            return new NormalizedType(CanonicalTypeKind.TIMESTAMP_TZ, null, LengthUnit.NONE, null, null, false, false, false, fsp);
        }
        if (base.equals("DATETIME") || base.equals("DATETIME2") || base.equals("SMALLDATETIME")
                || base.equals("TIMESTAMP") || base.equals("TIMESTAMP WITHOUT TIME ZONE")
                || base.equals("DATETIME64")) {
            int fsp = parsedFirst != null ? parsedFirst : (effScale != null && effScale >= 0 ? effScale : (base.equals("DATETIME64") ? 3 : 6));
            return new NormalizedType(CanonicalTypeKind.DATETIME, null, LengthUnit.NONE, null, null, false, false, false, fsp);
        }
        if (base.startsWith("INTERVAL")) {
            return new NormalizedType(CanonicalTypeKind.INTERVAL, null, LengthUnit.NONE, null, null, false, false, false, null);
        }

        // 3. JDBC Types 兜底（处理 SQLite 无类型声明列或冷门自定义类型）
        if (jdbcType != null) {
            return switch (jdbcType) {
                case Types.BOOLEAN, Types.BIT -> new NormalizedType(CanonicalTypeKind.BOOLEAN, null, LengthUnit.NONE, null, null, false, false, false, null);
                case Types.TINYINT -> new NormalizedType(CanonicalTypeKind.INT8, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
                case Types.SMALLINT -> new NormalizedType(CanonicalTypeKind.INT16, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
                case Types.INTEGER -> new NormalizedType(CanonicalTypeKind.INT32, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
                case Types.BIGINT -> new NormalizedType(CanonicalTypeKind.INT64, null, LengthUnit.NONE, null, null, false, unsigned, false, null);
                case Types.NUMERIC, Types.DECIMAL -> new NormalizedType(CanonicalTypeKind.DECIMAL, null, LengthUnit.NONE,
                        effPrec, effScale, effPrec == null || effPrec <= 0, unsigned, false, null);
                case Types.REAL, Types.FLOAT -> new NormalizedType(CanonicalTypeKind.FLOAT32, null, LengthUnit.NONE, null, null, false, false, false, null);
                case Types.DOUBLE -> new NormalizedType(CanonicalTypeKind.FLOAT64, null, LengthUnit.NONE, null, null, false, false, false, null);
                case Types.CHAR, Types.NCHAR -> new NormalizedType(CanonicalTypeKind.CHAR, effLen == null ? 1 : effLen, LengthUnit.CHAR, null, null, false, false, national, null);
                case Types.VARCHAR, Types.NVARCHAR -> effLen != null && effLen > 0 && effLen < 65536
                        ? new NormalizedType(CanonicalTypeKind.VARCHAR, effLen, LengthUnit.CHAR, null, null, false, false, national, null)
                        : new NormalizedType(CanonicalTypeKind.TEXT, null, LengthUnit.NONE, null, null, false, false, national, null);
                case Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB ->
                        new NormalizedType(CanonicalTypeKind.CLOB, null, LengthUnit.NONE, null, null, false, false, national, null);
                case Types.BINARY -> new NormalizedType(CanonicalTypeKind.BINARY, effLen == null ? 1 : effLen, LengthUnit.BYTE, null, null, false, false, false, null);
                case Types.VARBINARY -> new NormalizedType(CanonicalTypeKind.VARBINARY, effLen == null ? 255 : effLen, LengthUnit.BYTE, null, null, false, false, false, null);
                case Types.LONGVARBINARY, Types.BLOB -> new NormalizedType(CanonicalTypeKind.BLOB, null, LengthUnit.NONE, null, null, false, false, false, null);
                case Types.DATE -> new NormalizedType(CanonicalTypeKind.DATE, null, LengthUnit.NONE, null, null, false, false, false, null);
                case Types.TIME, Types.TIME_WITH_TIMEZONE -> new NormalizedType(CanonicalTypeKind.TIME, null, LengthUnit.NONE, null, null, false, false, false, 0);
                case Types.TIMESTAMP -> new NormalizedType(CanonicalTypeKind.DATETIME, null, LengthUnit.NONE, null, null, false, false, false, 6);
                case Types.TIMESTAMP_WITH_TIMEZONE -> new NormalizedType(CanonicalTypeKind.TIMESTAMP_TZ, null, LengthUnit.NONE, null, null, false, false, false, 6);
                case Types.SQLXML -> new NormalizedType(CanonicalTypeKind.XML, null, LengthUnit.NONE, null, null, false, false, false, null);
                default -> new NormalizedType(CanonicalTypeKind.TEXT, null, LengthUnit.NONE, null, null, false, false, false, null);
            };
        }
        return new NormalizedType(CanonicalTypeKind.RAW_UNSUPPORTED, null, LengthUnit.NONE, null, null, false, false, false, null);
    }

    /** 将各库默认值表达式归一化，便于跨库无损重建常见默认值。 */
    public static NormalizedDefault normalizeDefault(String rawDefault) {
        if (rawDefault == null || rawDefault.isBlank()) {
            return new NormalizedDefault(DefaultExprKind.NONE, null);
        }
        String trimmed = stripOuterParens(rawDefault.trim());
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("NULL")) {
            return new NormalizedDefault(DefaultExprKind.NONE, null);
        }
        // 去除 PostgreSQL 的类型转换后缀（如 'abc'::character varying 或 0::numeric）
        String withoutPgCast = trimmed.replaceAll("::[a-zA-Z0-9_\\s\"()]+$", "").trim();
        String upper = withoutPgCast.toUpperCase(Locale.ROOT);

        if (upper.contains("NEXTVAL(") || upper.endsWith(".NEXTVAL")) {
            return new NormalizedDefault(DefaultExprKind.SEQUENCE_NEXT, trimmed);
        }
        if (upper.equals("SYSDATE") || upper.equals("SYSTIMESTAMP")
                || upper.equals("CURRENT_TIMESTAMP") || upper.equals("CURRENT_TIMESTAMP()")
                || upper.startsWith("CURRENT_TIMESTAMP(")
                || upper.equals("NOW()") || upper.equals("GETDATE()") || upper.equals("GETUTCDATE()")
                || upper.equals("SYSDATETIME()") || upper.equals("LOCALTIMESTAMP")) {
            return new NormalizedDefault(DefaultExprKind.CURRENT_TIMESTAMP, trimmed);
        }
        if (upper.equals("CURRENT_DATE") || upper.equals("CURRENT_DATE()") || upper.equals("CURDATE()")) {
            return new NormalizedDefault(DefaultExprKind.CURRENT_DATE, trimmed);
        }
        if (upper.equals("USER") || upper.equals("CURRENT_USER") || upper.equals("CURRENT_USER()") || upper.equals("SUSER_SNAME()")) {
            return new NormalizedDefault(DefaultExprKind.CURRENT_USER, trimmed);
        }
        if (upper.equals("SYS_GUID()") || upper.equals("UUID()") || upper.equals("NEWID()")
                || upper.equals("GEN_RANDOM_UUID()") || upper.equals("UUID_GENERATE_V4()")) {
            return new NormalizedDefault(DefaultExprKind.UUID, trimmed);
        }
        if (upper.equals("TRUE") || upper.equals("FALSE") || upper.equals("B'1'") || upper.equals("B'0'")) {
            String boolNorm = (upper.equals("TRUE") || upper.equals("B'1'")) ? "TRUE" : "FALSE";
            return new NormalizedDefault(DefaultExprKind.LITERAL_BOOLEAN, boolNorm);
        }
        if (NUMBER_LITERAL.matcher(withoutPgCast).matches()) {
            return new NormalizedDefault(DefaultExprKind.LITERAL_NUMBER, withoutPgCast);
        }
        if ((withoutPgCast.startsWith("'") && withoutPgCast.endsWith("'") && withoutPgCast.length() >= 2)
                || (withoutPgCast.startsWith("N'") && withoutPgCast.endsWith("'") && withoutPgCast.length() >= 3)) {
            String unquoted = withoutPgCast.startsWith("N'")
                    ? withoutPgCast.substring(2, withoutPgCast.length() - 1)
                    : withoutPgCast.substring(1, withoutPgCast.length() - 1);
            return new NormalizedDefault(DefaultExprKind.LITERAL_STRING, unquoted.replace("''", "'"));
        }
        return new NormalizedDefault(DefaultExprKind.EXPRESSION_RAW, trimmed);
    }

    public static String unwrapClickHouse(String typeName) {
        String current = typeName == null ? "" : typeName.trim();
        while (true) {
            String lower = current.toLowerCase(Locale.ROOT);
            if (lower.startsWith("nullable(") && current.endsWith(")")) {
                current = current.substring("nullable(".length(), current.length() - 1).trim();
            } else if (lower.startsWith("lowcardinality(") && current.endsWith(")")) {
                current = current.substring("lowcardinality(".length(), current.length() - 1).trim();
            } else {
                return current;
            }
        }
    }

    private static String baseTypeName(String upper) {
        String cleaned = upper.replace("UNSIGNED", "").replace("ZEROFILL", "").trim();
        int paren = cleaned.indexOf('(');
        if (paren > 0) {
            String prefix = cleaned.substring(0, paren).trim();
            String suffix = cleaned.substring(cleaned.indexOf(')', paren) + 1).trim();
            cleaned = suffix.isEmpty() ? prefix : prefix + " " + suffix;
        }
        // 处理 PG / 驱动常见的 int8(bigint) 冲突：保留 pg int8 与 clickhouse Int8 的区分
        if (cleaned.equals("INT8") && upper.equals("INT8")) {
            return "INT8";
        }
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private static String stripOuterParens(String text) {
        String current = text;
        while (current.startsWith("(") && current.endsWith(")") && current.length() >= 2) {
            int depth = 0;
            boolean wrapsWhole = true;
            for (int i = 0; i < current.length(); i++) {
                char c = current.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0 && i < current.length() - 1) {
                        wrapsWhole = false;
                        break;
                    }
                }
            }
            if (!wrapsWhole) break;
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
