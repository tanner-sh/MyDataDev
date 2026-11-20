package com.example.dbadmin.dto;

import com.example.dbadmin.model.MigrationArtifact;
import com.example.dbadmin.model.MigrationItem;
import com.example.dbadmin.model.MigrationJob;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/** 异构数据库完整迁移与标准制品（.mddpkg）REST DTO。 */
public final class MigrationDtos {
    private MigrationDtos() {
    }

    /** 评估请求：支持三种模式（ONLINE_DIRECT / EXPORT_ARTIFACT / IMPORT_ARTIFACT）。 */
    public record MigrationAssessRequest(
            @NotBlank String mode,
            Long sourceConnectionId,
            String sourceSchema,
            Long sourceArtifactId,
            Long targetConnectionId,
            String targetSchema,
            String targetDbType,
            MigrationOptions options
    ) {
    }

    public record AssessmentFinding(
            String severity,
            String code,
            String objectKind,
            String objectName,
            String columnName,
            String message,
            String suggestion
    ) {
    }

    public record ColumnMappingPreview(
            String sourceName,
            String targetName,
            String sourceNativeType,
            String canonicalType,
            String targetType,
            boolean nullable,
            boolean overridden,
            boolean lob,
            String severity,
            String note
    ) {
    }

    public record TableAssessment(
            String tableId,
            String sourceName,
            String targetName,
            Long estimatedRows,
            Long estimatedBytes,
            int columnCount,
            boolean detailsDeferred,
            boolean hasLobColumns,
            boolean hasPrimaryKey,
            List<String> primaryKeyColumns,
            int indexCount,
            int foreignKeyCount,
            int checkConstraintCount,
            List<ColumnMappingPreview> columns,
            List<String> createTableSql,
            List<String> postDataSql,
            List<String> sequenceSyncSql,
            List<AssessmentFinding> findings
    ) {
    }

    public record ProgramObjectAssessment(
            String key,
            String kind,
            String sourceName,
            String targetName,
            String translationMode,
            String sourceSql,
            String targetSql,
            boolean aiAssisted,
            List<String> dependencies,
            List<AssessmentFinding> findings
    ) {
    }

    public record AssessmentSummary(
            int tableCount,
            int sequenceCount,
            int programObjectCount,
            long estimatedTotalRows,
            long estimatedTotalBytes,
            int okCount,
            int warningCount,
            int blockerCount,
            boolean hasLobTables
    ) {
    }

    public record MigrationAssessmentResponse(
            String planToken,
            String mode,
            String sourceDbType,
            String sourceVersion,
            String sourceSchema,
            String targetDbType,
            String targetSchema,
            Boolean targetSchemaExists,
            List<String> createSchemaSql,
            boolean sameFamily,
            boolean canExecute,
            boolean aiTranslationAvailable,
            AssessmentSummary summary,
            List<TableAssessment> tables,
            List<ProgramObjectAssessment> programObjects,
            List<AssessmentFinding> globalFindings,
            MigrationOptions effectiveOptions
    ) {
    }

    public record MigrationStartRequest(
            @NotBlank String planToken,
            MigrationOptions options,
            String sourceProductionConfirmation,
            String targetProductionConfirmation
    ) {
    }

    public record MigrationRetryRequest(
            String targetProductionConfirmation
    ) {
    }

    public record MigrationVerifyRequest(
            String verifyLevel,
            List<String> tables
    ) {
    }

    public record AiTranslateObjectRequest(
            @NotBlank String planToken,
            @NotBlank String objectKey
    ) {
    }

    public record AiTranslateObjectResponse(
            String objectKey,
            String targetSql,
            boolean compiled,
            String compileMessage,
            List<String> notes
    ) {
    }

    public record MigrationJobPage(
            List<MigrationJob> items,
            int page,
            int pageSize,
            boolean hasMore
    ) {
    }

    public record MigrationJobDetail(
            MigrationJob job,
            List<MigrationItem> items,
            MigrationArtifact sourceArtifact,
            MigrationArtifact outputArtifact
    ) {
    }

    public record MigrationArtifactPage(
            List<MigrationArtifact> items,
            int page,
            int pageSize,
            boolean hasMore
    ) {
    }

    public record RegisterStorageArtifactRequest(
            long storageProfileId,
            @NotBlank String remotePath
    ) {
    }

    public record TableVerificationResult(
            String tableId,
            String sourceName,
            String targetName,
            String status,
            String level,
            long sourceRowCount,
            long targetRowCount,
            boolean rowCountMatch,
            boolean aggregateMatch,
            Boolean multisetHashMatch,
            long sourceLobBytes,
            long targetLobBytes,
            String sourceHashHex,
            String targetHashHex,
            List<String> mismatches,
            long elapsedMs
    ) {
    }

    public record MigrationVerificationReport(
            long jobId,
            String verifyMode,
            String verifyLevel,
            String overallStatus,
            int totalTables,
            int passedTables,
            int failedTables,
            int skippedTables,
            long totalRowsVerified,
            long totalLobBytesVerified,
            String verifiedAt,
            List<TableVerificationResult> tables,
            List<String> notes
    ) {
    }

    public record SupportedDialectMatrix(
            List<DialectSupportInfo> dialects
    ) {
    }

    public record DialectSupportInfo(
            String code,
            String family,
            int maxIdentifierLength,
            boolean supportsSequences,
            boolean supportsForeignKeys,
            boolean supportsSecondaryIndexes,
            boolean supportsColumnComments,
            boolean supportsCheckConstraints,
            int maxTemporalFsp,
            Map<String, String> highlights
    ) {
    }
}
