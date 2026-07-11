export type Connection = {
  id: number;
  name: string;
  dbType: string;
  jdbcUrl: string;
  username?: string;
  environment: string;
  readonly: boolean;
};

export type DbObject = {
  schemaName?: string;
  name: string;
  type: string;
  columns: { name: string; type: string; size: number; nullable: boolean; remarks?: string; ordinalPosition?: number; defaultValue?: string }[];
  indexes: { name: string; columnName: string; unique: boolean }[];
};

export type ObjectDetail = DbObject & {
  primaryKeys: string[];
  primaryKeyName?: string | null;
  rowCount?: number | null;
  ddl: string;
  ddlSource?: string;
};

export type ObjectRelation = { constraintName?: string; pkSchemaName?: string; pkTableName: string; pkColumnName: string; fkSchemaName?: string; fkTableName: string; fkColumnName: string };
export type ObjectRelations = { importedKeys: ObjectRelation[]; exportedKeys: ObjectRelation[] };
export type ColumnDesign = { name: string; type: string; size?: number | null; nullable: boolean; defaultValue?: string; originalName?: string; deleted: boolean };
export type IndexDesign = { name: string; columns: string[]; unique: boolean; originalName?: string; deleted: boolean };
export type TableDesignRequest = { schemaName?: string; tableName: string; columns: ColumnDesign[]; indexes: IndexDesign[]; primaryKeys: string[]; confirmation?: string };
export type TableDesignResponse = { sql: string[]; message: string };
export type ObjectStructure = DbObject;
export type Metadata = {
  schemas: string[];
  currentSchema: string;
  selectedSchema: string;
  namespaceKind?: 'SCHEMA' | 'CATALOG';
  objects: DbObject[];
  totalObjects: number;
  page: number;
  pageSize: number;
  hasMore: boolean;
  cachedAt?: string;
  cacheHit?: boolean;
};
export type SqlResult = { columns: string[]; rows: Record<string, unknown>[]; affectedRows: number; elapsedMs: number; resultSet: boolean; maxRows?: number; truncated?: boolean };
export type SqlStatementResult = { index: number; sql: string; startOffset: number; endOffset: number; status: 'SUCCESS' | 'FAILED'; errorMessage?: string | null; result: SqlResult };
export type SqlScriptResult = { status: 'SUCCESS' | 'FAILED'; elapsedMs: number; executedCount: number; results: SqlStatementResult[]; metadataChanged?: boolean };
export type BackupScope = 'DATABASE' | 'SCHEMA' | 'TABLES';
export type LegacyBackupScope = BackupScope | 'TABLE';
export type BackupMethod = 'SQL' | 'MYSQLDUMP' | 'ORACLE_EXP';
export type BackupTargetItem = { name: string; current?: boolean };
export type BackupTargetPage = {
  namespaceKind?: 'SCHEMA' | 'CATALOG';
  currentNamespace?: string;
  namespaceName?: string;
  items: BackupTargetItem[];
  page: number;
  pageSize: number;
  total: number;
  hasMore: boolean;
};
export type BackupTargetQuery = { keyword?: string; page: number; pageSize: number; refresh?: boolean };
export type BackupTableTargetQuery = BackupTargetQuery & { namespaceName: string };
export type BackupSchedulePreview = { cron?: string; zoneId: string; nextRuns: string[] };
export type BackupEditorRequest = {
  requestId: string | number;
  target: ActiveTable;
  name?: string;
};
export type BackupTask = {
  id: number;
  name: string;
  connectionId: number;
  scope: LegacyBackupScope;
  schemaName?: string;
  tableNames?: string[];
  /** @deprecated Compatibility field returned by older servers. */
  tableName?: string;
  backupMethod?: BackupMethod | string;
  toolPath?: string;
  extraArgs?: string;
  nativeConnectName?: string;
  cron?: string;
  enabled: boolean;
  lastStatus?: string;
  lastMessage?: string;
  lastFilePath?: string;
  lastFileSize?: number;
  lastRunAt?: string;
  zoneId?: string;
  nextRunAt?: string;
};
export type BackupHistory = {
  id: number;
  taskId: number;
  connectionId: number;
  status: string;
  message?: string;
  filePath?: string;
  fileSize?: number;
  startedAt?: string;
  finishedAt?: string;
};
export type BackupTaskForm = {
  name: string;
  scope: BackupScope;
  schemaName?: string;
  tableNames?: string[];
  /** @deprecated Sent for one release so an older backend can still read a single-table task. */
  tableName?: string;
  backupMethod?: BackupMethod | string;
  toolPath?: string;
  extraArgs?: string;
  nativeConnectName?: string;
  cron?: string;
  enabled: boolean;
};
export type ActiveTable = { schemaName?: string; tableName: string };
export type TableRow = { id: string; values: Record<string, unknown>; original?: Record<string, unknown>; deleted?: boolean; inserted?: boolean };
export type TableData = { columns: string[]; rows: Record<string, unknown>[]; keyColumns: string[]; editable: boolean; page?: number; pageSize?: number; hasMore?: boolean };
export type CompletionCatalog = { namespaceKind?: 'SCHEMA' | 'CATALOG'; selectedSchema?: string; objects: DbObject[] };
export type RowChange = { type: 'INSERT' | 'UPDATE' | 'DELETE'; key?: Record<string, unknown>; values?: Record<string, unknown> };
export type ConnectionForm = { name: string; dbType: string; jdbcUrl: string; username: string; password: string; environment: string; readonly: boolean };
export type WorkspaceStatusKind = 'idle' | 'loading' | 'success' | 'info' | 'error';
export type WorkspaceStatus = { kind: WorkspaceStatusKind; text: string; detail?: string };
export type SqlTab = { id: string; title: string; sql: string; results: SqlStatementResult[]; activeResultKey?: string; message: string; statusKind?: WorkspaceStatusKind };
export type SqlHistory = { id: number; connectionId: number; sql: string; type: string; status: string; elapsedMs: number; errorMessage?: string; actor?: string; createdAt: string };
export type SqlCompletionItem = { label: string; kind: string; insertText: string; detail: string };
export type ExportFormat = 'csv' | 'json' | 'sql' | 'xml';
export type ImportFormat = 'csv' | 'json' | 'sql';
export type ImportResult = { rows: Record<string, unknown>[]; message: string };
export type ResultRow = { key: string } & Record<string, unknown>;
export type EditableRow = TableRow;
export type RefreshConnectionsOptions = { retry?: boolean };
