import type * as Monaco from 'monaco-editor';
import { DB_TYPE_OPTIONS, ENVIRONMENT_OPTIONS } from './constants';
import type { BackupTask, LegacyBackupScope, RowChange, SqlTab, TableRow } from './types';
import type { SqlEditorOnMount } from './sqlEditorTypes';

export function createSqlTab(index: number): SqlTab {
  return { id: `query-${Date.now()}-${index}`, title: `查询 ${index}`, sql: 'select 1 as val', dirty: false, results: [], message: '' };
}

export function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

export function timestamp() {
  const pad = (value: number) => String(value).padStart(2, '0');
  const now = new Date();
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

export function buildChanges(rows: TableRow[], _keyColumns: string[]): RowChange[] {
  const changes: RowChange[] = [];
  for (const row of rows) {
    if (row.inserted) {
      const touched = new Set(row.touchedColumns || Object.keys(row.values));
      changes.push({
        type: 'INSERT',
        values: Object.fromEntries(Object.entries(row.values).filter(([column]) => touched.has(column)))
      });
      continue;
    }
    if (!row.original) continue;
    if (row.deleted) {
      changes.push({ type: 'DELETE', keyToken: row.keyToken });
      continue;
    }
    const changedValues = diff(row.original, row.values);
    if (Object.keys(changedValues).length > 0) {
      changes.push({
        type: 'UPDATE',
        keyToken: row.keyToken,
        values: changedValues,
        originalValues: Object.fromEntries(Object.keys(changedValues).map((column) => [column, row.original?.[column]]))
      });
    }
  }
  return changes;
}

export type RowChangeSummary = { inserts: number; updates: number; deletes: number };

/**
 * Counts pending changes by kind in one pass.
 *
 * buildChanges walks every row and diffs every column, so it must run once per
 * edit at most. Callers that need both the changes and a breakdown pass the
 * already-computed list here instead of rebuilding it.
 */
export function summarizeRowChanges(changes: RowChange[]): RowChangeSummary {
  const summary: RowChangeSummary = { inserts: 0, updates: 0, deletes: 0 };
  for (const change of changes) {
    if (change.type === 'INSERT') summary.inserts++;
    else if (change.type === 'UPDATE') summary.updates++;
    else if (change.type === 'DELETE') summary.deletes++;
  }
  return summary;
}

export function diff(original: Record<string, unknown>, values: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(values).filter(([column, value]) => !sameCellValue(original[column], value)));
}

export function sameCellValue(left: unknown, right: unknown) {
  if (left == null || right == null) return left === right;
  return Object.is(left, right) || String(left) === String(right);
}

export function removeEmptyValues(values: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== ''));
}

export function normalizeBackupScope(scope: LegacyBackupScope | string) {
  return scope === 'TABLE' ? 'TABLES' : scope;
}

export function backupScopeLabel(scope: LegacyBackupScope | string, namespaceKind?: 'SCHEMA' | 'CATALOG') {
  if (scope === 'DATABASE') return '当前数据库';
  if (scope === 'SCHEMA') return namespaceKind === 'CATALOG' ? '指定数据库' : '指定 Schema';
  if (scope === 'TABLE' || scope === 'TABLES') return '指定表';
  return scope;
}

export function backupTargetLabel(task: Pick<BackupTask, 'scope' | 'schemaName' | 'tableName' | 'tableNames'>, namespaceKind?: 'SCHEMA' | 'CATALOG') {
  const scope = normalizeBackupScope(task.scope);
  if (scope === 'DATABASE') return '当前连接数据库范围';
  const namespace = task.schemaName?.trim();
  if (scope === 'SCHEMA') {
    const label = namespaceKind === 'CATALOG' ? '数据库' : 'Schema';
    return namespace ? `${label} ${namespace}` : `连接默认${label}`;
  }
  const tables = task.tableNames?.length ? task.tableNames : task.tableName ? [task.tableName] : [];
  if (!tables.length) return namespace ? `${namespace} 下的指定表` : '指定表';
  if (tables.length === 1) return `${namespace ? `${namespace}.` : ''}${tables[0]}`;
  const visible = tables.slice(0, 3).join('、');
  return `${namespace ? `${namespace} · ` : ''}${tables.length} 张表（${visible}${tables.length > 3 ? '…' : ''}）`;
}

export function backupStatusLabel(status?: string) {
  if (!status) return '尚未执行';
  if (status === 'SUCCESS') return '执行成功';
  if (status === 'FAILED') return '执行失败';
  if (status === 'RUNNING') return '后台执行中';
  return status;
}

export function backupMethodLabel(method?: string) {
  if (!method || method === 'SQL') return 'SQL 数据备份';
  if (method === 'MYSQLDUMP') return 'MySQL mysqldump';
  if (method === 'ORACLE_EXP') return 'Oracle exp';
  if (method === 'PG_DUMP') return 'PostgreSQL pg_dump';
  return method;
}

export function formatFileSize(size?: number) {
  if (!size || size <= 0) return '';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export function sqlKeywordCompletionItems(monaco: Parameters<SqlEditorOnMount>[1], range: Monaco.IRange) {
  return [
    'SELECT', 'FROM', 'WHERE', 'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN',
    'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'INSERT', 'UPDATE', 'DELETE'
  ].map((keyword) => ({
    label: keyword,
    kind: monaco.languages.CompletionItemKind.Keyword,
    insertText: keyword,
    detail: 'SQL 关键字',
    range
  }));
}

export function completionKind(monaco: Parameters<SqlEditorOnMount>[1], kind: string) {
  if (kind === 'TABLE') return monaco.languages.CompletionItemKind.Class;
  if (kind === 'COLUMN') return monaco.languages.CompletionItemKind.Field;
  if (kind === 'SCHEMA') return monaco.languages.CompletionItemKind.Module;
  return monaco.languages.CompletionItemKind.Keyword;
}

export function formatHistoryTime(value: string) {
  if (!value) return '';
  // Java ZonedDateTime may append a region suffix such as "[Asia/Shanghai]",
  // which browsers do not accept even though the preceding offset is valid ISO-8601.
  const parsed = new Date(value.replace(/\[[^\]]+\]$/, ''));
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false });
}

export function dbTypeLabel(dbType: string) {
  return DB_TYPE_OPTIONS.find((option) => option.value === dbType)?.label || dbType;
}

export function normalizeEnvironment(environment?: string) {
  return ENVIRONMENT_OPTIONS.some((option) => option.value === environment) ? environment as string : 'dev';
}

export function environmentLabel(environment?: string) {
  return ENVIRONMENT_OPTIONS.find((option) => option.value === environment)?.label || '开发';
}

export function objectTypeLabel(type: string) {
  const normalized = type.toUpperCase();
  if (normalized.includes('TABLE')) return '表';
  if (normalized.includes('VIEW')) return '视图';
  return type;
}

/**
 * Chinese text for the error codes the backend attaches to a problem response.
 * Codes are the reliable channel; the substring matching below only exists for
 * responses that predate a code.
 */
const ERROR_CODE_MESSAGES: Record<string, string> = {
  INTERNAL_ERROR: '服务器内部错误，请稍后重试。',
  TARGET_DATABASE_UNAVAILABLE: '无法连接目标数据库，请检查数据库状态与网络后重试。',
  READONLY_CONNECTION: '当前连接为只读连接，不允许执行写入或结构变更。',
  CONNECTION_HAS_BACKUP_TASKS: '该连接存在关联备份任务，请先切换到“备份任务”删除相关任务后再删除连接。',
  CONNECTION_RESTORE_RUNNING: '该连接有正在执行的恢复任务，请等待恢复完成后再删除连接。',
  CONNECTION_BACKGROUND_BUSY: '该连接已有后台重任务正在执行，请等待完成后重试。',
  BACKUP_ALREADY_RUNNING: '该备份任务正在执行，请勿重复启动。',
  BACKUP_QUEUE_FULL: '备份执行队列已满，请稍后重试。',
  RESTORE_ALREADY_RUNNING: '该恢复任务正在执行，请勿重复启动。',
  RESTORE_QUEUE_FULL: '恢复执行队列已满，请稍后重试。',
  SQL_FILE_QUEUE_FULL: 'SQL 文件执行队列已满，请稍后重试。',
  SQL_FILE_NOT_READY: 'SQL 文件尚未完成解析或状态已变化，请重新选择文件。',
  SQL_FILE_EXPIRED: 'SQL 文件已过期，请重新选择文件。',
  SQL_FILE_ALREADY_RUNNING: '该连接已有 SQL 文件任务正在执行。'
};

/**
 * Turns a backend error into Chinese UI text.
 *
 * Always pass the `code` when one is available: without it an unmapped backend
 * message (raw JDBC driver text, Spring internals) reaches the user verbatim.
 */
export function localizeMessage(message: string, code?: string) {
  if (code && ERROR_CODE_MESSAGES[code]) return ERROR_CODE_MESSAGES[code];
  if (!message) return '';
  if (message.includes('Backup framework executed')) return '备份任务已执行。当前数据库类型暂未实现物理备份适配器。';
  if (message.includes('Physical backup adapter is not implemented')) return '当前数据库类型暂未实现物理备份适配器。';
  if (message.includes('MySQL backup task prepared')) return 'MySQL 备份任务已准备完成，请在服务端配置物理备份命令。';
  if (message.includes('Connection not found')) return '未找到数据库连接。';
  if (message.includes('backup task')) return '该连接存在关联备份任务，请先处理备份任务后再删除。';
  if (message.includes('connection ok')) return '连接测试成功。';
  if (message.includes('No pending changes')) return '没有待提交的变更。';
  return message;
}

/** Localizes a caught error, preferring the `code` carried by an ApiError. */
export function localizeError(error: unknown) {
  const code = typeof (error as { code?: unknown })?.code === 'string' ? (error as { code: string }).code : undefined;
  return localizeMessage((error as Error)?.message || '', code);
}
