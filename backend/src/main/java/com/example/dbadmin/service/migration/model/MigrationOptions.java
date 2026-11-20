package com.example.dbadmin.service.migration.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 迁移评估、规划、导出与执行共用的可序列化选项集。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationOptions(
        String identifierCasePolicy,
        String conflictMode,
        Boolean includeData,
        Boolean includeIndexes,
        Boolean includeForeignKeys,
        Boolean includeCheckConstraints,
        Boolean includeComments,
        Boolean includeSequences,
        Boolean includeProgramObjects,
        Boolean createTargetSchemaIfMissing,
        Boolean caseSensitiveCollation,
        String timezonePolicy,
        String emptyStringToOraclePolicy,
        String pgNulBytePolicy,
        Integer unboundedNumberPrecision,
        Integer unboundedNumberScale,
        Boolean useOracleScnSnapshot,
        Boolean verifyAfterMigration,
        String verifyLevel,
        Integer concurrency,
        List<String> selectedTables,
        List<String> selectedProgramObjects,
        Map<String, String> columnTypeOverrides,
        Map<String, String> programObjectOverrides,
        Map<String, String> tableNameMapping,
        Long storageProfileId,
        String artifactName
) {
    public static MigrationOptions defaults() {
        return new MigrationOptions(
                "PRESERVE", "FAIL", true, true, true, true, true, true, false, true,
                true, "UTC", "FAIL", "FAIL", 38, 10, true, true, "L3_FULL_HASH",
                4, List.of(), List.of(), Map.of(), Map.of(), Map.of(), null, null
        );
    }

    public MigrationOptions normalized(int defaultConcurrency, int maxConcurrency) {
        String casePolicy = normalizeEnum(identifierCasePolicy, "PRESERVE", "PRESERVE", "LOWER", "UPPER");
        String conflict = normalizeEnum(conflictMode, "FAIL", "FAIL", "SKIP", "TRUNCATE_AND_LOAD", "DROP_AND_RECREATE");
        String tzPolicy = normalizeEnum(timezonePolicy, "UTC", "UTC", "LOCAL");
        String emptyPolicy = normalizeEnum(emptyStringToOraclePolicy, "FAIL", "FAIL", "ALLOW_NULL", "SPACE_PLACEHOLDER");
        String nulPolicy = normalizeEnum(pgNulBytePolicy, "FAIL", "FAIL", "STRIP");
        String vLevel = normalizeEnum(verifyLevel, "L3_FULL_HASH", "L1_COUNT", "L2_AGGREGATE", "L3_FULL_HASH");
        int prec = unboundedNumberPrecision == null ? 38 : Math.min(Math.max(unboundedNumberPrecision, 1), 65);
        int scale = unboundedNumberScale == null ? 10 : Math.min(Math.max(unboundedNumberScale, 0), prec);
        int conc = concurrency == null ? defaultConcurrency : Math.min(Math.max(concurrency, 1), Math.max(1, maxConcurrency));
        return new MigrationOptions(
                casePolicy,
                conflict,
                includeData == null ? Boolean.TRUE : includeData,
                includeIndexes == null ? Boolean.TRUE : includeIndexes,
                includeForeignKeys == null ? Boolean.TRUE : includeForeignKeys,
                includeCheckConstraints == null ? Boolean.TRUE : includeCheckConstraints,
                includeComments == null ? Boolean.TRUE : includeComments,
                includeSequences == null ? Boolean.TRUE : includeSequences,
                includeProgramObjects == null ? Boolean.FALSE : includeProgramObjects,
                createTargetSchemaIfMissing == null ? Boolean.TRUE : createTargetSchemaIfMissing,
                caseSensitiveCollation == null ? Boolean.TRUE : caseSensitiveCollation,
                tzPolicy,
                emptyPolicy,
                nulPolicy,
                prec,
                scale,
                useOracleScnSnapshot == null ? Boolean.TRUE : useOracleScnSnapshot,
                verifyAfterMigration == null ? Boolean.TRUE : verifyAfterMigration,
                vLevel,
                conc,
                selectedTables == null ? List.of() : List.copyOf(selectedTables),
                selectedProgramObjects == null ? List.of() : List.copyOf(selectedProgramObjects),
                columnTypeOverrides == null ? Map.of() : Map.copyOf(columnTypeOverrides),
                programObjectOverrides == null ? Map.of() : Map.copyOf(programObjectOverrides),
                tableNameMapping == null ? Map.of() : Map.copyOf(tableNameMapping),
                storageProfileId,
                artifactName == null || artifactName.isBlank() ? null : artifactName.trim()
        );
    }

    public boolean dataEnabled() { return !Boolean.FALSE.equals(includeData); }
    public boolean indexesEnabled() { return !Boolean.FALSE.equals(includeIndexes); }
    public boolean foreignKeysEnabled() { return !Boolean.FALSE.equals(includeForeignKeys); }
    public boolean checkConstraintsEnabled() { return !Boolean.FALSE.equals(includeCheckConstraints); }
    public boolean commentsEnabled() { return !Boolean.FALSE.equals(includeComments); }
    public boolean sequencesEnabled() { return !Boolean.FALSE.equals(includeSequences); }
    public boolean programObjectsEnabled() { return Boolean.TRUE.equals(includeProgramObjects); }
    public boolean createTargetSchemaEnabled() { return !Boolean.FALSE.equals(createTargetSchemaIfMissing); }
    public boolean caseSensitiveCollationEnabled() { return !Boolean.FALSE.equals(caseSensitiveCollation); }
    public boolean oracleScnSnapshotEnabled() { return !Boolean.FALSE.equals(useOracleScnSnapshot); }
    public boolean verifyAfterMigrationEnabled() { return !Boolean.FALSE.equals(verifyAfterMigration); }

    private static String normalizeEnum(String raw, String fallback, String... allowed) {
        if (raw == null || raw.isBlank()) return fallback;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(upper)) return upper;
        }
        throw new IllegalArgumentException("不支持的配置选项值：" + raw);
    }
}
