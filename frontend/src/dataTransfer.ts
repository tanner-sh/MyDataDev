/**
 * 跨连接数据传输的纯逻辑：表单整形、可执行判断与展示文案。
 *
 * <p>与 `dataDiff.ts` 是一对 —— 那边比两张表的差异，这边把一张表的内容搬到另一条连接上。
 * 冲突策略直接复用导入那一套（`dataImport.ts`）：目标端遇到同主键的行该怎么办，是同一个
 * 问题，不该在界面上出现两种说法。</p>
 */
import type { ImportConflictMode } from './dataImport';
import type { Connection } from './types';

/** 源端取数的两种方式。 */
export type DataTransferSourceMode = 'table' | 'query';

export type DataTransferForm = {
  sourceConnectionId?: number;
  sourceSchema: string;
  sourceMode: DataTransferSourceMode;
  sourceTable: string;
  sourceSql: string;
  targetConnectionId?: number;
  targetSchema: string;
  targetTable: string;
  conflictMode: ImportConflictMode;
};

export type DataTransferRequestBody = {
  sourceConnectionId: number;
  sourceSchema?: string;
  sourceTable?: string;
  sourceSql?: string;
  targetConnectionId: number;
  targetSchema?: string;
  targetTable: string;
  conflictMode: ImportConflictMode;
};

export const EMPTY_DATA_TRANSFER_FORM: DataTransferForm = {
  sourceSchema: '',
  sourceMode: 'table',
  sourceTable: '',
  sourceSql: '',
  targetSchema: '',
  targetTable: '',
  conflictMode: 'INSERT'
};

/** 源端有没有填够：整表模式看表名，查询模式看 SQL。 */
export function hasSource(form: DataTransferForm): boolean {
  return form.sourceMode === 'table' ? Boolean(form.sourceTable.trim()) : Boolean(form.sourceSql.trim());
}

/**
 * 同一条连接下的同一张表。
 *
 * <p>拦下来不是洁癖：把一张表插进它自己，成功的后果是行数翻倍且没有任何提示，比报错难查得多。
 * 自定义查询不拦 —— `SELECT ... WHERE ...` 回填同一张表是有意义的操作。</p>
 */
export function isSelfCopy(form: DataTransferForm): boolean {
  if (form.sourceMode !== 'table') return false;
  if (form.sourceConnectionId !== form.targetConnectionId) return false;
  const sameSchema = form.sourceSchema.trim().toLowerCase() === form.targetSchema.trim().toLowerCase();
  return sameSchema && form.sourceTable.trim().toLowerCase() === form.targetTable.trim().toLowerCase();
}

export function canRunTransfer(form: DataTransferForm): boolean {
  if (!form.sourceConnectionId || !form.targetConnectionId) return false;
  if (!form.targetTable.trim()) return false;
  return hasSource(form) && !isSelfCopy(form);
}

export function buildDataTransferRequest(form: DataTransferForm): DataTransferRequestBody {
  if (!form.sourceConnectionId || !form.targetConnectionId) throw new Error('请先选择源和目标连接');
  if (!form.targetTable.trim()) throw new Error('请填写目标表');
  if (!hasSource(form)) throw new Error(form.sourceMode === 'table' ? '请填写源表' : '请填写源查询');
  return {
    sourceConnectionId: form.sourceConnectionId,
    sourceSchema: form.sourceSchema.trim() || undefined,
    // 两种模式互斥：后端看到 sourceSql 就不会再去解析 sourceTable，留着上一次的输入只会让
    // 审计明细里写着一个根本没用上的表名。
    sourceTable: form.sourceMode === 'table' ? form.sourceTable.trim() : undefined,
    sourceSql: form.sourceMode === 'query' ? form.sourceSql.trim() : undefined,
    targetConnectionId: form.targetConnectionId,
    targetSchema: form.targetSchema.trim() || undefined,
    targetTable: form.targetTable.trim(),
    conflictMode: form.conflictMode
  };
}

function qualify(schema: string, name: string): string {
  const trimmed = schema.trim();
  return trimmed ? `${trimmed}.${name}` : name;
}

function connectionName(connections: Connection[], id?: number): string {
  return connections.find((connection) => connection.id === id)?.name ?? '未选择';
}

/** 确认与提示里那句「从哪到哪」。方向弄反的代价是往错的库里写数据，所以它要一直摆在眼前。 */
export function describeTransfer(form: DataTransferForm, connections: Connection[]): string {
  const from = form.sourceMode === 'table'
    ? qualify(form.sourceSchema, form.sourceTable.trim() || '（未填）')
    : '查询结果';
  return `从「${connectionName(connections, form.sourceConnectionId)}」的 ${from}`
    + ` 到「${connectionName(connections, form.targetConnectionId)}」的 ${qualify(form.targetSchema, form.targetTable.trim() || '（未填）')}`;
}

/** 源端是生产连接时要回连接名 —— 传输和导出一样是把数据带出这条连接。 */
export function sourceConfirmationName(form: DataTransferForm, connections: Connection[]): string | undefined {
  const source = connections.find((connection) => connection.id === form.sourceConnectionId);
  return source?.environment === 'prod' ? source.name : undefined;
}

/** 目标表默认与源表同名：跨库搬同一张表是最常见的一次，不该让用户抄一遍表名。 */
export function withSourceTable(form: DataTransferForm, sourceTable: string): DataTransferForm {
  const followed = form.targetTable.trim() === form.sourceTable.trim();
  return { ...form, sourceTable, targetTable: followed ? sourceTable : form.targetTable };
}
