import type { OnMount } from '@monaco-editor/react';
import type * as Monaco from 'monaco-editor';
import { DB_TYPE_OPTIONS, ENVIRONMENT_OPTIONS } from './constants';
import type { RowChange, SqlTab, TableRow } from './types';

export function createSqlTab(index: number): SqlTab {
  return { id: `query-${Date.now()}-${index}`, title: `查询 ${index}`, sql: 'select 1 as val', results: [], message: '' };
}

export function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

export function timestamp() {
  const pad = (value: number) => String(value).padStart(2, '0');
  const now = new Date();
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

export function buildChanges(rows: TableRow[], keyColumns: string[]): RowChange[] {
  const changes: RowChange[] = [];
  for (const row of rows) {
    if (row.inserted) {
      changes.push({ type: 'INSERT', values: removeEmptyValues(row.values) });
      continue;
    }
    if (!row.original) continue;
    if (row.deleted) {
      changes.push({ type: 'DELETE', key: key(row.original, keyColumns) });
      continue;
    }
    const changedValues = diff(row.original, row.values);
    if (Object.keys(changedValues).length > 0) {
      changes.push({ type: 'UPDATE', key: key(row.original, keyColumns), values: changedValues });
    }
  }
  return changes;
}

export function key(row: Record<string, unknown>, keyColumns: string[]) {
  return Object.fromEntries(keyColumns.map((column) => [column, row[column]]));
}

export function diff(original: Record<string, unknown>, values: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(values).filter(([column, value]) => String(original[column] ?? '') !== String(value ?? '')));
}

export function removeEmptyValues(values: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== ''));
}

export function backupScopeLabel(scope: string) {
  if (scope === 'DATABASE') return '全库';
  if (scope === 'TABLE') return '单表';
  return scope;
}

export function backupStatusLabel(status?: string) {
  if (!status) return '尚未执行';
  if (status === 'SUCCESS') return '执行成功';
  if (status === 'FAILED') return '执行失败';
  return status;
}

export function backupMethodLabel(method?: string) {
  if (!method || method === 'SQL') return 'SQL 逻辑备份';
  if (method === 'MYSQLDUMP') return 'MySQL mysqldump';
  if (method === 'ORACLE_EXP') return 'Oracle exp';
  return method;
}

export function formatFileSize(size?: number) {
  if (!size || size <= 0) return '';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export function sqlKeywordCompletionItems(monaco: Parameters<OnMount>[1], range: Monaco.IRange) {
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

export function completionKind(monaco: Parameters<OnMount>[1], kind: string) {
  if (kind === 'TABLE') return monaco.languages.CompletionItemKind.Class;
  if (kind === 'COLUMN') return monaco.languages.CompletionItemKind.Field;
  if (kind === 'SCHEMA') return monaco.languages.CompletionItemKind.Module;
  return monaco.languages.CompletionItemKind.Keyword;
}

export function formatHistoryTime(value: string) {
  if (!value) return '';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
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

export function localizeMessage(message: string) {
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
