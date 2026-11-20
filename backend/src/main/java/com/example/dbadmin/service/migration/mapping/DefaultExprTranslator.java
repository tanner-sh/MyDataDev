package com.example.dbadmin.service.migration.mapping;

import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.DefaultExprKind;
import com.example.dbadmin.service.migration.model.CanonicalTypeKind;
import com.example.dbadmin.service.migration.model.DbTargetDialect;

import java.util.Optional;

/**
 * 将中立默认值表达式翻译成 11 种目标方言的合法 {@code DEFAULT} 子句。
 */
public final class DefaultExprTranslator {
    private DefaultExprTranslator() {
    }

    public record TranslatedDefault(Optional<String> sql, String warning) {
        public static TranslatedDefault none() {
            return new TranslatedDefault(Optional.empty(), null);
        }

        public static TranslatedDefault of(String sql) {
            return new TranslatedDefault(Optional.of(sql), null);
        }

        public static TranslatedDefault warned(String warning) {
            return new TranslatedDefault(Optional.empty(), warning);
        }
    }

    public static TranslatedDefault translate(
            DbTargetDialect source,
            DbTargetDialect target,
            CanonicalColumn column,
            String resolvedTargetType
    ) {
        if (column.defaultKind() == null || column.defaultKind() == DefaultExprKind.NONE) {
            return TranslatedDefault.none();
        }
        // MySQL 5.7/部分兼容库不允许 TEXT/BLOB/JSON 列带字面量默认值（MySQL 8.0.13+ 仅允许表达式默认值），保守忽略并告警
        if (target.isMySqlFamily() && resolvedTargetType != null) {
            String upperTarget = resolvedTargetType.toUpperCase(java.util.Locale.ROOT);
            if (upperTarget.contains("TEXT") || upperTarget.contains("BLOB") || upperTarget.equals("JSON")) {
                return TranslatedDefault.warned("MySQL/OceanBase-MySQL 的 TEXT/BLOB/JSON 列默认跳过默认值（原默认值：" + column.defaultRaw() + "）。");
            }
        }
        return switch (column.defaultKind()) {
            case NONE, SEQUENCE_NEXT -> TranslatedDefault.none();
            case LITERAL_NUMBER -> {
                if (column.canonicalType() == CanonicalTypeKind.BOOLEAN) {
                    yield TranslatedDefault.of(booleanDefault(column.defaultRaw(), target, column.canonicalType()));
                }
                yield TranslatedDefault.of(column.defaultRaw());
            }
            case LITERAL_BOOLEAN -> TranslatedDefault.of(booleanDefault(column.defaultRaw(), target, column.canonicalType()));
            case LITERAL_STRING -> {
                if (column.canonicalType() == CanonicalTypeKind.BOOLEAN
                        && ("0".equals(column.defaultRaw()) || "1".equals(column.defaultRaw())
                        || "true".equalsIgnoreCase(column.defaultRaw()) || "false".equalsIgnoreCase(column.defaultRaw()))) {
                    yield TranslatedDefault.of(booleanDefault(column.defaultRaw(), target, column.canonicalType()));
                }
                yield TranslatedDefault.of(stringDefault(column.defaultRaw(), target));
            }
            case CURRENT_TIMESTAMP -> translateCurrentTimestamp(target);
            case CURRENT_DATE -> translateCurrentDate(target);
            case CURRENT_USER -> translateCurrentUser(target);
            case UUID -> translateUuid(target);
            case EXPRESSION_RAW -> {
                if (source != null && source.sameFamily(target) && column.defaultRaw() != null) {
                    yield TranslatedDefault.of(column.defaultRaw());
                }
                yield TranslatedDefault.warned("列 " + column.name() + " 包含方言专有默认表达式「" + column.defaultRaw() + "」，跨家族迁移已跳过，请按需在目标库补设。");
            }
        };
    }

    private static String booleanDefault(String raw, DbTargetDialect target, CanonicalTypeKind kind) {
        boolean truthy = "TRUE".equalsIgnoreCase(raw) || "1".equals(raw);
        if (target == DbTargetDialect.POSTGRESQL || target == DbTargetDialect.H2) {
            return truthy ? "TRUE" : "FALSE";
        }
        return truthy ? "1" : "0";
    }

    private static String stringDefault(String raw, DbTargetDialect target) {
        String escaped = raw == null ? "" : raw.replace("'", "''");
        if (target.isMySqlFamily() || target == DbTargetDialect.CLICKHOUSE) {
            escaped = escaped.replace("\\", "\\\\");
        }
        if (target == DbTargetDialect.SQLSERVER) {
            return "N'" + escaped + "'";
        }
        return "'" + escaped + "'";
    }

    private static TranslatedDefault translateCurrentTimestamp(DbTargetDialect target) {
        return switch (target) {
            case ORACLE, OCEANBASE_ORACLE, DAMENG -> TranslatedDefault.of("CURRENT_TIMESTAMP");
            case MYSQL, OCEANBASE_MYSQL, MARIADB -> TranslatedDefault.of("CURRENT_TIMESTAMP");
            case POSTGRESQL, H2, SQLITE -> TranslatedDefault.of("CURRENT_TIMESTAMP");
            case SQLSERVER -> TranslatedDefault.of("SYSUTCDATETIME()");
            case CLICKHOUSE -> TranslatedDefault.of("now()");
        };
    }

    private static TranslatedDefault translateCurrentDate(DbTargetDialect target) {
        return switch (target) {
            case ORACLE, OCEANBASE_ORACLE, DAMENG -> TranslatedDefault.of("TRUNC(SYSDATE)");
            case POSTGRESQL, H2, SQLITE -> TranslatedDefault.of("CURRENT_DATE");
            case SQLSERVER -> TranslatedDefault.of("CAST(GETDATE() AS DATE)");
            case CLICKHOUSE -> TranslatedDefault.of("today()");
            case MYSQL, OCEANBASE_MYSQL, MARIADB -> TranslatedDefault.warned("MySQL 系 DATE 列不支持直接写 DEFAULT CURRENT_DATE，已跳过。");
        };
    }

    private static TranslatedDefault translateCurrentUser(DbTargetDialect target) {
        return switch (target) {
            case ORACLE, OCEANBASE_ORACLE, DAMENG -> TranslatedDefault.of("USER");
            case POSTGRESQL, H2 -> TranslatedDefault.of("CURRENT_USER");
            case SQLSERVER -> TranslatedDefault.of("SUSER_SNAME()");
            default -> TranslatedDefault.warned("目标库 " + target.code() + " 不支持列默认值 CURRENT_USER，已跳过。");
        };
    }

    private static TranslatedDefault translateUuid(DbTargetDialect target) {
        return switch (target) {
            case ORACLE, OCEANBASE_ORACLE, DAMENG -> TranslatedDefault.of("SYS_GUID()");
            case POSTGRESQL -> TranslatedDefault.of("gen_random_uuid()");
            case SQLSERVER -> TranslatedDefault.of("NEWID()");
            case H2 -> TranslatedDefault.of("RANDOM_UUID()");
            case CLICKHOUSE -> TranslatedDefault.of("generateUUIDv4()");
            default -> TranslatedDefault.warned("目标库 " + target.code() + " 不建议在列默认值中使用 UUID 函数，已跳过（数据本身会原样迁移）。");
        };
    }
}
