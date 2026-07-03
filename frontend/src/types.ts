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
  columns: { name: string; type: string; size: number; nullable: boolean }[];
  indexes: { name: string; columnName: string; unique: boolean }[];
};

export type ObjectDetail = DbObject & {
  primaryKeys: string[];
  rowCount?: number | null;
  ddl: string;
};

export type Metadata = { schemas: string[]; objects: DbObject[] };
export type SqlResult = { columns: string[]; rows: Record<string, unknown>[]; affectedRows: number; elapsedMs: number; resultSet: boolean };
export type SqlStatementResult = { index: number; sql: string; startOffset: number; endOffset: number; status: 'SUCCESS' | 'FAILED'; errorMessage?: string | null; result: SqlResult };
export type SqlScriptResult = { status: 'SUCCESS' | 'FAILED'; elapsedMs: number; executedCount: number; results: SqlStatementResult[] };
export type BackupTask = { id: number; name: string; connectionId: number; scope: string; cron?: string; lastStatus?: string; lastMessage?: string };
export type ActiveTable = { schemaName?: string; tableName: string };
export type TableRow = { id: string; values: Record<string, unknown>; original?: Record<string, unknown>; deleted?: boolean; inserted?: boolean };
export type TableData = { columns: string[]; rows: Record<string, unknown>[]; keyColumns: string[]; editable: boolean };
export type RowChange = { type: 'INSERT' | 'UPDATE' | 'DELETE'; key?: Record<string, unknown>; values?: Record<string, unknown> };
export type ConnectionForm = { name: string; dbType: string; jdbcUrl: string; username: string; password: string; environment: string; readonly: boolean };
export type SqlTab = { id: string; title: string; sql: string; results: SqlStatementResult[]; activeResultKey?: string; message: string };
export type SqlHistory = { id: number; connectionId: number; sql: string; type: string; status: string; elapsedMs: number; errorMessage?: string; actor?: string; createdAt: string };
export type SqlCompletionItem = { label: string; kind: string; insertText: string; detail: string };
export type ExportFormat = 'csv' | 'json' | 'sql' | 'xml';
export type ImportFormat = 'csv' | 'json' | 'sql';
export type ImportResult = { rows: Record<string, unknown>[]; message: string };
export type ResultRow = { key: string } & Record<string, unknown>;
export type EditableRow = TableRow;
export type RefreshConnectionsOptions = { retry?: boolean };
