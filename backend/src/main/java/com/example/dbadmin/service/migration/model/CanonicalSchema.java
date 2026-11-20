package com.example.dbadmin.service.migration.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 跨库转换的中立中间表示（Canonical Schema IR）与制品/校验结构。
 *
 * <p>既是「在线直迁」内存传递的契约，也是标准制品 {@code .mddpkg} 中 {@code schema.json}、
 * {@code manifest.json} 与 {@code verify/checksums.json} 的持久化格式。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalSchema(
        int formatVersion,
        String sourceDbType,
        String sourceFamily,
        String sourceVersion,
        String sourceCharset,
        String sourceSchema,
        String snapshotToken,
        List<CanonicalTable> tables,
        List<CanonicalSequence> sequences,
        List<CanonicalProgramObject> programObjects
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public enum LengthUnit {
        NONE,
        CHAR,
        BYTE
    }

    public enum DefaultExprKind {
        NONE,
        LITERAL_STRING,
        LITERAL_NUMBER,
        LITERAL_BOOLEAN,
        CURRENT_TIMESTAMP,
        CURRENT_DATE,
        CURRENT_USER,
        UUID,
        SEQUENCE_NEXT,
        EXPRESSION_RAW
    }

    public enum IndexKind {
        BTREE,
        BITMAP,
        HASH,
        FULLTEXT,
        EXPRESSION,
        OTHER
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IdentitySpec(
            boolean generatedAlways,
            long startWith,
            long incrementBy
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CanonicalColumn(
            String name,
            CanonicalTypeKind canonicalType,
            String sourceNativeType,
            Integer length,
            LengthUnit lengthUnit,
            Integer precision,
            Integer scale,
            boolean unboundedDecimal,
            boolean unsigned,
            boolean national,
            Integer fractionalSeconds,
            boolean nullable,
            DefaultExprKind defaultKind,
            String defaultRaw,
            IdentitySpec identity,
            String generatedExpr,
            String comment
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexColumn(
            String name,
            boolean descending,
            Integer prefixLength,
            String expression
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CanonicalIndex(
            String name,
            List<IndexColumn> columns,
            boolean unique,
            IndexKind kind,
            String filterExpr
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CanonicalForeignKey(
            String name,
            List<String> localColumns,
            String refSchema,
            String refTable,
            List<String> refColumns,
            String onUpdate,
            String onDelete
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CanonicalCheckConstraint(
            String name,
            String expression
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CanonicalTable(
            String tableId,
            String name,
            String comment,
            List<CanonicalColumn> columns,
            String primaryKeyName,
            List<String> primaryKeyColumns,
            List<CanonicalIndex> indexes,
            List<CanonicalForeignKey> foreignKeys,
            List<CanonicalCheckConstraint> checkConstraints,
            String partitionSummary,
            Long estimatedRows,
            Long estimatedBytes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CanonicalSequence(
            String name,
            long currentValue,
            long incrementBy,
            Long minValue,
            Long maxValue,
            boolean cycle,
            Long cacheSize,
            String ownedByTable,
            String ownedByColumn
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CanonicalProgramObject(
            String kind,
            String name,
            String sourceSql,
            List<String> dependencies,
            String status
    ) {
    }

    /** 制品 manifest.json 的结构。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtifactManifest(
            int formatVersion,
            String generatorVersion,
            String exportedAt,
            String sourceDbType,
            String sourceFamily,
            String sourceVersion,
            String sourceCharset,
            String sourceSchema,
            String snapshotToken,
            int tableCount,
            long totalRows,
            long totalRawBytes,
            List<ArtifactTableEntry> tables,
            Map<String, String> entryChecksums
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtifactTableEntry(
            String tableId,
            String tableName,
            long rowCount,
            long rawBytes,
            int partCount,
            List<ArtifactPartEntry> parts
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtifactPartEntry(
            int partIndex,
            String entryPath,
            long rowCount,
            long rawBytes,
            String sha256
    ) {
    }

    /** 离线/在线通用表级校验摘要（存入 verify/checksums.json）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableChecksum(
            String tableId,
            String tableName,
            long rowCount,
            long totalLobBytes,
            String multisetHashHex,
            int temporalFspCap,
            Map<String, ColumnAggregate> columnAggregates
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnAggregate(
            long nonNullCount,
            String numericSum,
            String minValue,
            String maxValue,
            long lobByteSum
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtifactChecksums(
            int formatVersion,
            Map<String, TableChecksum> tables
    ) {
    }
}
