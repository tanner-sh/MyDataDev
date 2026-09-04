export type Connection = {
  id: number;
  name: string;
  dbType: string;
  jdbcUrl: string;
  username?: string;
  environment: string;
  readonly: boolean;
  /** 连接分组，仅用于组织列表。 */
  groupName?: string;
  tags?: string[];
  /** 连接级默认 schema/catalog：打开连接后资源树默认停在这里。 */
  defaultSchema?: string;
  /** 每建立一条物理数据库会话时执行的语句。 */
  initSql?: string;
  description?: string;
  /** SSH 隧道配置摘要；后端只回传「有没有配」，不回传任何密钥。 */
  ssh?: ConnectionSsh;
  capabilities: DatabaseCapabilities;
  /** 当前登录用户在这条连接上的服务端权限。 */
  permissions?: import('./accessControl').ConnectionPermission[];
};

export type ConnectionSshAuthMode = 'PASSWORD' | 'PRIVATE_KEY';

export type ConnectionSsh = {
  enabled: boolean;
  host?: string;
  port: number;
  username?: string;
  authMode: ConnectionSshAuthMode;
  hasPassword: boolean;
  hasPrivateKey: boolean;
  hasPassphrase: boolean;
  serverFingerprint?: string;
  skipHostKeyCheck: boolean;
};

export type DatabaseCapabilities = {
  tableBrowse: boolean;
  tableEdit: boolean;
  tableDesign: boolean;
  explain: boolean;
  nativeBackupMethods: string[];
  nativeRestoreMethods?: string[];
  schemaObjects?: SchemaObjectCapability[];
};

export type SchemaObjectKind = 'VIEW' | 'MATERIALIZED_VIEW' | 'SEQUENCE' | 'TRIGGER' | 'PROCEDURE' | 'FUNCTION';
export type SchemaObjectOperation = 'LIST' | 'DETAIL' | 'SOURCE' | 'CREATE' | 'REPLACE' | 'DROP' | 'INVOKE' | 'REFRESH' | 'ENABLE' | 'DISABLE' | 'DEPENDENCIES';
export type SchemaObjectCapability = { kind: SchemaObjectKind; operations: SchemaObjectOperation[] };
export type SchemaObjectSummary = {
  objectKey: string;
  schemaName?: string;
  name: string;
  displayName: string;
  kind: SchemaObjectKind;
  subtype?: string;
  status?: string;
};
export type SchemaObjectPage = {
  items: SchemaObjectSummary[];
  total: number;
  totalExact: boolean;
  page: number;
  pageSize: number;
  hasMore: boolean;
  cachedAt?: string;
  cacheHit?: boolean;
};
export type SchemaObjectParameter = { position: number; name?: string; mode: 'IN' | 'OUT' | 'INOUT' | 'RETURN'; typeName?: string; jdbcType?: number; nullable: boolean };
export type SchemaObjectDependency = { schemaName?: string; name: string; kind: string; direction: string };
export type SchemaObjectDetail = {
  object: SchemaObjectSummary;
  source?: string;
  sourceAvailable: boolean;
  sourceUnavailableReason?: string;
  parameters: SchemaObjectParameter[];
  dependencies: SchemaObjectDependency[];
  dependenciesAvailable: boolean;
  dependenciesUnavailableReason?: string;
  structureVersion: string;
  operations: SchemaObjectOperation[];
  properties: Record<string, unknown>;
};
export type SchemaObjectLifecycleOperation = 'CREATE' | 'REPLACE' | 'DROP' | 'REFRESH' | 'ENABLE' | 'DISABLE';
export type SchemaObjectLifecycleRequest = {
  operation: SchemaObjectLifecycleOperation;
  kind: SchemaObjectKind;
  schemaName?: string;
  objectName: string;
  objectKey?: string;
  source?: string;
  structureVersion?: string;
  confirmation?: string;
};
export type SchemaObjectLifecycleResponse = { sql: string[]; message: string };
export type SchemaObjectTemplate = { kind: SchemaObjectKind; schemaName?: string; objectName: string; source: string };
export type RoutineArgumentInput = { position: number; name?: string; value?: string; nullValue: boolean };
export type RoutineOutParameter = { name?: string; typeName?: string; value: unknown };
export type RoutineResultItem = { kind: 'RESULT_SET' | 'UPDATE_COUNT'; result?: SqlResult; updateCount?: number };
export type RoutineInvokeResponse = { status: string; elapsedMs: number; returnValue?: unknown; outParameters: RoutineOutParameter[]; results: RoutineResultItem[]; truncated: boolean };

export type DbObject = {
  schemaName?: string;
  name: string;
  type: string;
  columns: { name: string; type: string; size: number; nullable: boolean; remarks?: string; ordinalPosition?: number; defaultValue?: string }[];
  indexes: { name: string; columnName: string; unique: boolean; ordinalPosition?: number }[];
};

export type ObjectDetail = DbObject & {
  primaryKeys: string[];
  primaryKeyName?: string | null;
  structureVersion: string;
};

export type ObjectDdl = { ddl: string; source?: string };
export type ObjectRowCount = { value?: number | null; exact: boolean; elapsedMs: number };

export type ObjectRelation = { constraintName?: string; pkSchemaName?: string; pkTableName: string; pkColumnName: string; fkSchemaName?: string; fkTableName: string; fkColumnName: string };
export type ObjectRelations = { importedKeys: ObjectRelation[]; exportedKeys: ObjectRelation[] };
export type ColumnDesign = { name: string; type: string; size?: number | null; nullable: boolean; defaultValue?: string; originalName?: string; deleted: boolean };
export type IndexDesign = { name: string; columns: string[]; unique: boolean; originalName?: string; deleted: boolean };
export type TableDesignRequest = { schemaName?: string; tableName: string; columns: ColumnDesign[]; indexes: IndexDesign[]; primaryKeys: string[]; structureVersion: string; confirmation?: string };
export type TableDesignResponse = { sql: string[]; message: string };
export type TableLifecycleOperation = 'CREATE' | 'RENAME' | 'DROP';
export type TableLifecycleRequest = {
  operation: TableLifecycleOperation;
  schemaName?: string;
  tableName: string;
  newTableName?: string;
  columns?: ColumnDesign[];
  indexes?: IndexDesign[];
  primaryKeys?: string[];
  structureVersion?: string;
  confirmation?: string;
};
export type ObjectStructure = DbObject;
export type Metadata = {
  schemas: string[];
  currentSchema: string;
  selectedSchema: string;
  namespaceKind?: 'SCHEMA' | 'CATALOG';
  objects: DbObject[];
  totalObjects: number;
  totalObjectsExact?: boolean;
  page: number;
  pageSize: number;
  hasMore: boolean;
  cachedAt?: string;
  cacheHit?: boolean;
};
export type ResultColumn = { key: string; label: string; typeName: string };
export type SqlPageInfo = {
  connectionId: number;
  schemaName?: string;
  offset: number;
  requestedPageSize: number;
  effectivePageSize: number;
  hasMore: boolean;
  previousOffsets?: number[];
};
export type SqlResultSourceTable = { nameParts: string[] };
export type SqlResult = {
  columns: ResultColumn[];
  rows: unknown[][];
  affectedRows: number;
  elapsedMs: number;
  resultSet: boolean;
  maxRows?: number;
  truncated?: boolean;
  page?: SqlPageInfo | null;
  sourceTable?: SqlResultSourceTable | null;
  /** 单表来源且有稳定行定位字段时随结果下发，见 resultEditing.ts。 */
  edit?: import('./resultEditing').ResultEditInfo | null;
};
export type SqlPageNavigation = { offset: number; pageSize: number; previousOffsets: number[] };
export type SqlStatementResult = { index: number; sql: string; startOffset: number; endOffset: number; status: 'SUCCESS' | 'FAILED'; errorMessage?: string | null; result: SqlResult };
export type SqlScriptResult = { status: 'SUCCESS' | 'FAILED'; elapsedMs: number; executedCount: number; results: SqlStatementResult[]; metadataChanged?: boolean };
export type BackupScope = 'DATABASE' | 'SCHEMA' | 'TABLES';
export type LegacyBackupScope = BackupScope | 'TABLE';
export type BackupMethod = 'SQL' | 'MYSQLDUMP' | 'ORACLE_EXP' | 'PG_DUMP';
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
  totalExact?: boolean;
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
  /** 任务上保存的执行时区；为空表示是旧任务，实际按服务端默认时区触发。 */
  scheduleZone?: string;
  /** 后端计算出的生效时区，旧任务会回落到服务端默认时区。 */
  zoneId?: string;
  nextRunAt?: string;
  retentionDays?: number;
  retentionCount?: number;
  storageProfileId?: number;
  storageProfileName?: string;
  storageType?: StorageType | 'LOCAL';
  lastStorageType?: StorageType | 'LOCAL';
  lastStorageProfileId?: number;
  lastStorageObjectKey?: string;
  lastFileAvailable?: boolean;
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
  fileFormat?: RestoreFileFormat;
  backupMethod?: BackupMethod | string;
  sourceDbType?: string;
  checksumSha256?: string;
  phase?: string;
  progressCurrent?: number;
  progressTotal?: number;
  cancelRequested?: boolean;
  storageType?: StorageType | 'LOCAL';
  storageProfileId?: number;
  storageProfileName?: string;
  storageObjectKey?: string;
  stagingExpiresAt?: string;
  fileAvailable?: boolean;
  stagingAvailable?: boolean;
};
export type BackupHistoryPage = { items: BackupHistory[]; page: number; pageSize: number; hasMore: boolean };
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
  scheduleZone?: string;
  enabled: boolean;
  retentionDays?: number;
  retentionCount?: number;
  storageProfileId?: number;
};
export type StorageType = 'SMB' | 'NFS' | 'FTP' | 'SFTP';
export type StorageProfile = {
  id: number;
  name: string;
  type: StorageType;
  host: string;
  port: number;
  basePath?: string;
  username?: string;
  passwordConfigured: boolean;
  smbShare?: string;
  smbDomain?: string;
  nfsExportPath?: string;
  nfsUid?: number;
  nfsGid?: number;
  nfsGroups: number[];
  ftpTlsMode?: 'NONE' | 'EXPLICIT';
  sftpAuthMode?: 'PASSWORD' | 'PRIVATE_KEY';
  privateKeyConfigured: boolean;
  privateKeyPassphraseConfigured: boolean;
  serverFingerprint?: string;
  skipServerVerification: boolean;
  enabled: boolean;
  lastTestStatus?: string;
  lastTestMessage?: string;
  lastTestedAt?: string;
  taskReferences: number;
  historyReferences: number;
};
export type StorageProfileRequest = {
  name: string;
  type: StorageType;
  host: string;
  port?: number;
  basePath?: string;
  username?: string;
  password?: string;
  smbShare?: string;
  smbDomain?: string;
  nfsExportPath?: string;
  nfsUid?: number;
  nfsGid?: number;
  nfsGroups?: number[];
  ftpTlsMode?: 'NONE' | 'EXPLICIT';
  sftpAuthMode?: 'PASSWORD' | 'PRIVATE_KEY';
  privateKey?: string;
  privateKeyPassphrase?: string;
  serverFingerprint?: string;
  skipServerVerification: boolean;
  enabled: boolean;
};
export type StorageTestResponse = { ok: boolean; message: string };
export type BackupRunResponse = { task: BackupTask; execution: BackupHistory };
export type BackupTaskPage = { items: BackupTask[]; page: number; pageSize: number; hasMore: boolean };
export type RestoreFileFormat = 'SQL' | 'MYSQLDUMP' | 'ORACLE_DMP' | 'PG_DUMP';
export type RestoreConflictMode = 'SAFE' | 'OVERWRITE' | 'APPEND';
export type RestoreSourceRef = { kind: 'HISTORY' | 'UPLOAD'; id: number };
export type RestoreUpload = {
  id: number;
  originalName: string;
  filePath: string;
  fileSize: number;
  checksumSha256: string;
  fileFormat: RestoreFileFormat;
  sourceDbType?: string;
  createdAt: string;
  expiresAt: string;
};
export type RestorePreflight = {
  valid: boolean;
  planToken?: string;
  fileFormat: RestoreFileFormat;
  sourceDbType: string;
  targetDbType: string;
  statementCount: number;
  namespaces: string[];
  tables: string[];
  warnings: string[];
  errors: string[];
};
export type RestoreJob = {
  id: number;
  sourceKind: string;
  sourceId: number;
  sourceName?: string;
  fileFormat: RestoreFileFormat;
  sourceDbType?: string;
  targetConnectionId: number;
  targetDbType: string;
  conflictMode: RestoreConflictMode;
  status: string;
  phase?: string;
  progressCurrent?: number;
  progressTotal?: number;
  message?: string;
  cancelRequested?: boolean;
  startedAt?: string;
  finishedAt?: string;
  createdAt: string;
};
export type RestoreJobPage = { items: RestoreJob[]; page: number; pageSize: number; hasMore: boolean };
export type ActiveOperations = { backups: BackupHistory[]; restores: RestoreJob[]; sqlFiles: SqlFileExecution[] };
export type NativeToolStatus = {
  tool: 'MYSQLDUMP' | 'MYSQL' | 'ORACLE_EXP' | 'ORACLE_IMP' | 'PG_DUMP' | 'PG_RESTORE';
  displayName: string;
  available: boolean;
  resolvedPath?: string;
  version?: string;
  source?: string;
  message?: string;
};
export type NativeToolsResponse = { detectedAt: string; tools: NativeToolStatus[] };
export type ActiveTable = { schemaName?: string; tableName: string };
export type TableColumn = { name: string; typeName: string; jdbcType: number; nullable: boolean; editable?: boolean; truncated?: boolean };
export type TableRow = { id: string; values: Record<string, unknown>; original?: Record<string, unknown>; keyToken?: string; touchedColumns?: string[]; deleted?: boolean; inserted?: boolean };
export type TableData = { columns: TableColumn[]; rows: Record<string, unknown>[]; rowKeyTokens?: string[]; keyColumns: string[]; editable: boolean; navigationMode: 'KEYSET' | 'OFFSET'; nextCursor?: string | null; hasMore: boolean };
export type CompletionCatalog = { namespaceKind?: 'SCHEMA' | 'CATALOG'; selectedSchema?: string; objects: DbObject[]; hasMore?: boolean };
export type RowChange = { type: 'INSERT' | 'UPDATE' | 'DELETE'; keyToken?: string; values?: Record<string, unknown>; originalValues?: Record<string, unknown> };
export type ConnectionForm = {
  name: string;
  dbType: string;
  jdbcUrl: string;
  username: string;
  password: string;
  environment: string;
  readonly: boolean;
  groupName: string;
  /** 逗号分隔，提交给后端时由后端规范化。 */
  tags: string;
  defaultSchema: string;
  initSql: string;
  description: string;
  ssh: ConnectionSshForm;
};
/** 三个密钥字段沿用数据库密码的约定：****** 表示沿用已保存的值，空串表示清除。 */
export type ConnectionSshForm = {
  enabled: boolean;
  host: string;
  port: number;
  username: string;
  authMode: ConnectionSshAuthMode;
  password: string;
  privateKey: string;
  passphrase: string;
  serverFingerprint: string;
  skipHostKeyCheck: boolean;
};
export type WorkspaceStatusKind = 'idle' | 'loading' | 'success' | 'info' | 'error';
export type WorkspaceStatus = { kind: WorkspaceStatusKind; text: string; detail?: string };
export type SqlTab = {
  id: string;
  title: string;
  sql: string;
  dirty: boolean;
  results: SqlStatementResult[];
  activeResultKey?: string;
  message: string;
  statusKind?: WorkspaceStatusKind;
  /**
   * 整次请求失败（连接不上、被闸门拒绝、驱动报错）的完整原文。
   * 状态栏只有一行、会被截断且无法复制，所以结果区也要显示它。
   */
  errorDetail?: string;
};
export type SqlHistory = { id: number; connectionId: number; sql: string; type: string; status: string; elapsedMs: number; errorMessage?: string; actor?: string; actorUserId?: number; createdAt: string };
export type SqlFileExecutionStatus = 'ANALYZING' | 'READY' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED' | 'EXPIRED';
export type SqlFileExecution = {
  id: number;
  connectionId: number;
  connectionName: string;
  targetDbType: string;
  fileName: string;
  fileSize: number;
  checksumSha256: string;
  detectedCharset?: string;
  status: SqlFileExecutionStatus;
  phase?: string;
  processedBytes: number;
  statementTotal?: number;
  statementCurrent: number;
  queryCount: number;
  mutationCount: number;
  ddlCount: number;
  unknownCount: number;
  successCount: number;
  queryRowCount: number;
  failedStatementIndex?: number;
  failedSqlPreview?: string;
  message?: string;
  metadataChanged: boolean;
  sessionChanged: boolean;
  cancelRequested: boolean;
  expiresAt: string;
  startedAt?: string;
  finishedAt?: string;
  createdAt: string;
};
export type SqlFileExecutionPage = { items: SqlFileExecution[]; page: number; pageSize: number; hasMore: boolean };
export type SqlFileCandidate = {
  requestId: number;
  file: File;
  connection: Connection;
  /** 存在时表示这是一份要转成 INSERT 脚本的 CSV，而不是直接执行的 SQL 文件。 */
  csvImport?: { schemaName?: string; tableName: string };
};
export type SqlCompletionItem = { label: string; kind: string; insertText: string; detail: string };
export type ExportFormat = 'csv' | 'json' | 'sql' | 'xml' | 'markdown' | 'xlsx';
export type ResultCopyFormat = 'sql' | 'pipe';
export type ImportFormat = 'csv' | 'json' | 'sql';
export type ImportResult = { rows: Record<string, unknown>[]; message: string };
export type ResultRow = {
  key: string;
  values: unknown[];
  /** 在本批结果里的原始下标，就地编辑用它定位行定位令牌。 */
  rowIndex: number;
  /** 该行未提交的修改，折在记录上让 shouldCellUpdate 能比较出来。 */
  edits?: Record<string, unknown>;
  /** 正在编辑的列名。 */
  editingColumn?: string;
};
export type EditableRow = TableRow;
export type RefreshConnectionsOptions = { retry?: boolean; preferredConnectionId?: number };

export type McpLimits = {
  defaultQueryRows: number;
  maxQueryRows: number;
  maxResultCells: number;
  maxResultTextChars: number;
  maxCellTextChars: number;
  maxSqlChars: number;
  queryTimeoutSeconds: number;
  metadataPageSize: number;
  maxMetadataPageSize: number;
  tablePageSize: number;
  maxTablePageSize: number;
  sessionTtlMinutes: number;
};
export type McpAccessLevel = 'READ_ONLY' | 'DATA_WRITE' | 'FULL';
/** 一条连接授权：连接与它的档位是一体的，分开存会出现「授了连接没档位」的中间态。 */
export type McpAgentGrant = { connectionId: number; accessLevel: McpAccessLevel };
export type McpAgent = {
  id: number;
  agentId: string;
  enabled: boolean;
  allowProduction: boolean;
  grants: McpAgentGrant[];
  createdAt: string;
  updatedAt: string;
};
export type McpConnectionOption = {
  id: number;
  name: string;
  dbType: string;
  environment: string;
  readonly: boolean;
};
export type McpConfig = {
  enabled: boolean;
  endpointPath: string;
  allowedOrigins: string[];
  limits: McpLimits;
  agents: McpAgent[];
  connections: McpConnectionOption[];
};
export type McpCredential = { agent: McpAgent; credential: string };

export type AuditEvent = {
  id: number;
  actor: string;
  action: string;
  target?: string;
  detail?: string;
  detailTruncated: boolean;
  remoteAddress?: string;
  forwardedFor?: string;
  userAgent?: string;
  requestId?: string;
  createdAt: string;
};

export type AuditEventPage = { items: AuditEvent[]; page: number; pageSize: number; hasMore: boolean };

export type AuditFacets = { actors: string[]; actions: string[] };
/** ER 图数据。只包含参与关系的列，完整列定义在对象详情里。 */
export type DiagramColumn = {
  name: string;
  type: string;
  nullable: boolean;
  primaryKey: boolean;
  foreignKey: boolean;
};
export type DiagramTable = {
  schemaName: string;
  name: string;
  keyColumns: DiagramColumn[];
  columnCount: number;
};
export type DiagramRelation = {
  constraintName: string | null;
  fromTable: string;
  fromColumn: string;
  toTable: string;
  toColumn: string;
};
export type SchemaDiagram = {
  schemaName: string;
  tables: DiagramTable[];
  relations: DiagramRelation[];
  totalTables: number;
  truncated: boolean;
};

export type AuditChainStatus = { valid: boolean; checkedEvents: number; firstInvalidId?: number; anchorHash?: string; headHash?: string; complete: boolean; nextId?: number };
export type AuditAlertStatus = { enabled: boolean; webhookConfigured: boolean; signed: boolean; cooldownSeconds: number; actions: string[] };

export type SqlTransactionScriptResult = SqlScriptResult & {
  transaction: import("./sqlTransaction").SqlTransaction;
};

export type SchemaDiffStatus = 'ONLY_IN_SOURCE' | 'ONLY_IN_TARGET' | 'DIFFERENT' | 'IDENTICAL';
export type SchemaDiffChange = 'ADDED' | 'REMOVED' | 'CHANGED';
export type SchemaDiffRequest = {
  sourceConnectionId: number;
  sourceSchema?: string;
  targetConnectionId: number;
  targetSchema?: string;
  tables: string[];
  includeDrops: boolean;
};
export type SchemaDiffEndpoint = { connectionId: number; connectionName: string; dbType: string; schemaName: string };
/** 差异方向一律以源端为准：ADDED 表示源端有而目标端没有。 */
export type SchemaDiffItem = {
  category: string;
  name: string;
  change: SchemaDiffChange;
  source?: string;
  target?: string;
};
export type SchemaDiffTable = {
  tableName: string;
  status: SchemaDiffStatus;
  items: SchemaDiffItem[];
  migration: string[];
};
export type SchemaDiffSummary = { onlyInSource: number; onlyInTarget: number; different: number; identical: number };
export type SchemaDiffResponse = {
  source: SchemaDiffEndpoint;
  target: SchemaDiffEndpoint;
  summary: SchemaDiffSummary;
  tables: SchemaDiffTable[];
  migration: string[];
  warnings: string[];
};

export type AiProvider = 'ANTHROPIC' | 'OPENAI_COMPATIBLE';
export type AiEffort = 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH' | 'MAX';
/** 一条连接允许发给模型的内容范围；默认 NONE，没授权就连表名都取不到。 */
export type AiSchemaSharing = 'NONE' | 'STRUCTURE' | 'STRUCTURE_AND_SAMPLE';

export type AiSettings = {
  enabled: boolean;
  provider: AiProvider;
  baseUrl?: string | null;
  model: string;
  effort: AiEffort;
  /** 后端只说 Key 配没配，永远不回传密文。 */
  apiKeyConfigured: boolean;
};

export type AiConnectionPolicy = {
  connectionId: number;
  connectionName: string;
  dbType: string;
  environment: string;
  production: boolean;
  sharing: AiSchemaSharing;
  sampleRowLimit: number;
};

export type AiProbeResult = {
  ok: boolean;
  provider: string;
  model: string;
  latencyMs: number;
  message: string;
};

/** AI 可用性快照：功能开没开、哪些连接被授权了。不含任何配置细节。 */
export type AiStatus = { enabled: boolean; sharedConnectionIds: number[]; sampledConnectionIds: number[] };
