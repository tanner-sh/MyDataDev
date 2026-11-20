import type {
  Connection,
  MigrationAssessmentResponse,
  MigrationConflictMode,
  MigrationMode,
  MigrationOptions,
  MigrationVerificationReport
} from './types';

export const DEFAULT_MIGRATION_OPTIONS: MigrationOptions = {
  identifierCasePolicy: 'PRESERVE',
  conflictMode: 'FAIL',
  includeData: true,
  includeIndexes: true,
  includeForeignKeys: true,
  includeCheckConstraints: true,
  includeComments: true,
  includeSequences: true,
  includeProgramObjects: false,
  createTargetSchemaIfMissing: true,
  caseSensitiveCollation: true,
  timezonePolicy: 'UTC',
  emptyStringToOraclePolicy: 'FAIL',
  pgNulBytePolicy: 'FAIL',
  unboundedNumberPrecision: 38,
  unboundedNumberScale: 10,
  useOracleScnSnapshot: true,
  verifyAfterMigration: true,
  verifyLevel: 'L3_FULL_HASH',
  concurrency: 4,
  selectedTables: [],
  selectedProgramObjects: [],
  columnTypeOverrides: {},
  programObjectOverrides: {},
  tableNameMapping: {},
  storageProfileId: null,
  artifactName: null
};

export type MigrationFormState = {
  mode: MigrationMode;
  sourceConnectionId?: number;
  sourceSchema: string;
  sourceArtifactId?: number;
  targetConnectionId?: number;
  targetSchema: string;
  options: MigrationOptions;
};

export function createInitialMigrationForm(defaultConnectionId?: number, defaultSchema = ''): MigrationFormState {
  return {
    mode: 'ONLINE_DIRECT',
    sourceConnectionId: defaultConnectionId,
    sourceSchema: defaultSchema,
    sourceArtifactId: undefined,
    targetConnectionId: undefined,
    targetSchema: '',
    options: { ...DEFAULT_MIGRATION_OPTIONS }
  };
}

export function canRunAssess(form: MigrationFormState): boolean {
  if (form.mode === 'EXPORT_ARTIFACT') {
    return Boolean(form.sourceConnectionId);
  }
  if (form.mode === 'IMPORT_ARTIFACT') {
    return Boolean(form.sourceArtifactId && form.targetConnectionId);
  }
  return Boolean(form.sourceConnectionId && form.targetConnectionId);
}

export function isDestructiveConflictMode(mode: MigrationConflictMode | string): boolean {
  return mode === 'DROP_AND_RECREATE' || mode === 'TRUNCATE_AND_LOAD';
}

export function sourceConfirmationTarget(form: MigrationFormState, connections: Connection[]): string | undefined {
  if (form.mode === 'IMPORT_ARTIFACT' || !form.sourceConnectionId) return undefined;
  const conn = connections.find((c) => c.id === form.sourceConnectionId);
  return conn?.environment === 'PROD' ? conn.name : undefined;
}

export function targetConfirmationTarget(form: MigrationFormState, connections: Connection[]): string | undefined {
  if (form.mode === 'EXPORT_ARTIFACT' || !form.targetConnectionId) return undefined;
  const conn = connections.find((c) => c.id === form.targetConnectionId);
  if (!conn) return undefined;
  if (conn.environment === 'PROD' || isDestructiveConflictMode(form.options.conflictMode)) {
    return conn.name;
  }
  return undefined;
}

export function withColumnTypeOverride(
  options: MigrationOptions,
  tableName: string,
  columnName: string,
  targetType: string
): MigrationOptions {
  const key = `${tableName}.${columnName}`;
  const next = { ...options.columnTypeOverrides };
  const trimmed = targetType.trim();
  if (!trimmed) {
    delete next[key];
  } else {
    next[key] = trimmed;
  }
  return { ...options, columnTypeOverrides: next };
}

export function withProgramObjectOverride(
  options: MigrationOptions,
  objectKey: string,
  targetSql: string
): MigrationOptions {
  const next = { ...options.programObjectOverrides };
  const trimmed = targetSql.trim();
  if (!trimmed) {
    delete next[objectKey];
  } else {
    next[objectKey] = trimmed;
  }
  return { ...options, programObjectOverrides: next };
}

export function isMigrationJobActive(status?: string | null): boolean {
  return status === 'QUEUED' || status === 'RUNNING';
}

export function migrationStatusMeta(status?: string | null): { label: string; color: string } {
  switch (status) {
    case 'QUEUED':
      return { label: '排队中', color: 'default' };
    case 'RUNNING':
    case 'DATA_RUNNING':
    case 'DDL_RUNNING':
      return { label: '执行中', color: 'processing' };
    case 'DDL_DONE':
      return { label: '已建表', color: 'blue' };
    case 'DATA_DONE':
      return { label: '数据完成', color: 'cyan' };
    case 'SUCCESS':
    case 'DONE':
      return { label: '已完成', color: 'success' };
    case 'PARTIAL_FAILED':
      return { label: '部分失败', color: 'warning' };
    case 'FAILED':
      return { label: '失败', color: 'error' };
    case 'CANCELLED':
      return { label: '已取消', color: 'default' };
    case 'SKIPPED':
      return { label: '已跳过', color: 'default' };
    default:
      return { label: status || '未知', color: 'default' };
  }
}

export function verifyStatusMeta(status?: string | null): { label: string; color: string } {
  switch (status) {
    case 'PASSED':
      return { label: '校验通过', color: 'success' };
    case 'FAILED':
      return { label: '存在差异', color: 'error' };
    case 'SKIPPED':
      return { label: '已跳过', color: 'default' };
    default:
      return { label: '未校验', color: 'default' };
  }
}

export function formatMigrationBytes(bytes?: number | null): string {
  if (!bytes || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let idx = 0;
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024;
    idx++;
  }
  return `${value >= 100 || idx === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[idx]}`;
}

export function migrationModeLabel(mode: MigrationMode | string): string {
  switch (mode) {
    case 'ONLINE_DIRECT':
      return '在线直迁';
    case 'EXPORT_ARTIFACT':
      return '导出标准制品 (.mddpkg)';
    case 'IMPORT_ARTIFACT':
      return '导入标准制品 (.mddpkg)';
    default:
      return mode;
  }
}

export function parseVerificationReport(json?: string | null): MigrationVerificationReport | null {
  if (!json || !json.trim()) return null;
  try {
    return JSON.parse(json) as MigrationVerificationReport;
  } catch {
    return null;
  }
}

export function buildCombinedDdlPreview(assessment: MigrationAssessmentResponse): string {
  const sections: string[] = [];
  sections.push(`-- MyDataDev 异构转换 DDL 预览 (${assessment.sourceDbType} -> ${assessment.targetDbType ?? 'artifact'})`);
  if (assessment.createSchemaSql && assessment.createSchemaSql.length > 0) {
    sections.push(`\n-- [阶段0 自动创建目标 Schema/数据库] ${assessment.targetSchema ?? ''}`);
    sections.push(assessment.createSchemaSql.map((s) => `${s};`).join('\n'));
  }
  const inlineTables = assessment.tables.filter((t) => !t.detailsDeferred);
  const previewTables = inlineTables.slice(0, 80);
  if (assessment.tables.length > previewTables.length) {
    sections.push(`\n-- 当前 Schema 共 ${assessment.tables.length} 张表，页面内仅预览前 ${previewTables.length} 张表 DDL；完整脚本请点击上方「下载完整 DDL」`);
  }
  for (const table of previewTables) {
    if (table.createTableSql.length > 0) {
      sections.push(`\n-- [阶段1 基础表] ${table.sourceName} -> ${table.targetName}`);
      sections.push(table.createTableSql.map((s) => `${s};`).join('\n'));
    }
  }
  for (const table of previewTables) {
    if (table.postDataSql.length > 0) {
      sections.push(`\n-- [阶段3 索引与约束] ${table.targetName}`);
      sections.push(table.postDataSql.map((s) => `${s};`).join('\n'));
    }
  }
  for (const table of previewTables) {
    if (table.sequenceSyncSql.length > 0) {
      sections.push(`\n-- [阶段4 自增/序列对齐] ${table.targetName}`);
      sections.push(table.sequenceSyncSql.map((s) => `${s};`).join('\n'));
    }
  }
  return sections.join('\n');
}
