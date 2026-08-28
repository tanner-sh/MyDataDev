/**
 * 结构对比的纯逻辑：请求整形、脚本拼装与展示用的文案。
 *
 * 面板本身只负责表单和渲染 —— 判断「这次对比要不要发」「脚本长什么样」这类逻辑放在这里，
 * 才能不启动浏览器就测到。
 */
import type { SchemaDiffChange, SchemaDiffRequest, SchemaDiffResponse, SchemaDiffStatus } from './types';

export type SchemaDiffForm = {
  sourceConnectionId?: number;
  sourceSchema: string;
  targetConnectionId?: number;
  targetSchema: string;
  /** 逗号或换行分隔的表名，留空表示比较全部表。 */
  tables: string;
  includeDrops: boolean;
};

export const EMPTY_SCHEMA_DIFF_FORM: SchemaDiffForm = {
  sourceSchema: '',
  targetSchema: '',
  tables: '',
  includeDrops: false
};

export const SCHEMA_DIFF_STATUS_LABELS: Readonly<Record<SchemaDiffStatus, string>> = {
  ONLY_IN_SOURCE: '目标端缺失',
  ONLY_IN_TARGET: '源端没有',
  DIFFERENT: '结构不一致',
  IDENTICAL: '一致'
};

export const SCHEMA_DIFF_CHANGE_LABELS: Readonly<Record<SchemaDiffChange, string>> = {
  ADDED: '新增',
  REMOVED: '多余',
  CHANGED: '变更'
};

export const SCHEMA_DIFF_CATEGORY_LABELS: Readonly<Record<string, string>> = {
  COLUMN: '字段',
  INDEX: '索引',
  PRIMARY_KEY: '主键'
};

/** 表名输入按逗号、分号、空白和换行拆开，顺手去重去空。 */
export function parseTableFilter(value: string): string[] {
  const seen = new Set<string>();
  for (const raw of value.split(/[,，;；\s]+/)) {
    const name = raw.trim();
    if (name) seen.add(name);
  }
  return [...seen];
}

export function canRunSchemaDiff(form: SchemaDiffForm): boolean {
  if (!form.sourceConnectionId || !form.targetConnectionId) return false;
  // 同一条连接下必须比较两个不同的 Schema，否则是在和自己比。
  if (form.sourceConnectionId !== form.targetConnectionId) return true;
  const source = form.sourceSchema.trim().toLowerCase();
  const target = form.targetSchema.trim().toLowerCase();
  return source.length > 0 && target.length > 0 && source !== target;
}

export function buildSchemaDiffRequest(form: SchemaDiffForm): SchemaDiffRequest {
  if (!form.sourceConnectionId || !form.targetConnectionId) throw new Error('请先选择源和目标连接');
  return {
    sourceConnectionId: form.sourceConnectionId,
    sourceSchema: form.sourceSchema.trim() || undefined,
    targetConnectionId: form.targetConnectionId,
    targetSchema: form.targetSchema.trim() || undefined,
    tables: parseTableFilter(form.tables),
    includeDrops: form.includeDrops
  };
}

/**
 * 迁移脚本。
 *
 * 后端返回的是一条条语句（注释行原样混在里面），这里补上分号和一段抬头，让脚本可以直接
 * 粘进 SQL 工作台执行 —— 注释行不能加分号，否则整段会被当成一条空语句。
 */
export function buildMigrationScript(response: SchemaDiffResponse): string {
  if (response.migration.length === 0) return '';
  const header = [
    `-- 结构同步脚本：把 ${describeEndpoint(response.target)} 对齐到 ${describeEndpoint(response.source)}`,
    '-- 由 MyDataDev 结构对比生成，执行前请逐条复核。',
    ...response.warnings.map((warning) => `-- 注意：${warning}`)
  ];
  const body = response.migration.map((line) => (isComment(line) ? line : `${line};`));
  return [...header, '', ...body].join('\n');
}

export function summarizeSchemaDiff(response: SchemaDiffResponse): string {
  const { onlyInSource, onlyInTarget, different, identical } = response.summary;
  if (onlyInSource + onlyInTarget + different === 0) {
    return `两侧结构一致，共比较 ${identical} 张表。`;
  }
  return `目标端缺 ${onlyInSource} 张表，多 ${onlyInTarget} 张表，${different} 张表结构不一致，${identical} 张表一致。`;
}

export function describeEndpoint(endpoint: { connectionName: string; schemaName: string }): string {
  return `${endpoint.connectionName} / ${endpoint.schemaName}`;
}

function isComment(line: string): boolean {
  return line.trimStart().startsWith('--');
}
