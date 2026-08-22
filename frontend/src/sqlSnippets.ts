/**
 * 保存的 SQL 片段。
 *
 * 执行历史有保留期且会被定期清理，反复使用的对账查询、排查脚本、清理语句没有地方安放 ——
 * 此前唯一的「模板」是 sqlTemplates.ts 里一条硬编码的 SELECT 骨架。
 */

export const SNIPPET_SEARCH_DEBOUNCE_MS = 250;
export const MAX_SNIPPET_NAME_LENGTH = 120;
export const MAX_SNIPPET_TAGS = 8;

export type SqlSnippet = {
  id: number;
  name: string;
  description?: string;
  sql: string;
  dbType?: string;
  tags?: string;
  useCount: number;
  lastUsedAt?: string;
  actor?: string;
  updatedAt?: string;
};

export type SqlSnippetDraft = {
  id?: number;
  name: string;
  description: string;
  sql: string;
  /** 空串表示「适用于所有数据库类型」。 */
  dbType: string;
  tags: string;
};

export const EMPTY_SNIPPET_DRAFT: SqlSnippetDraft = { name: '', description: '', sql: '', dbType: '', tags: '' };

export function snippetDraftFrom(snippet: SqlSnippet): SqlSnippetDraft {
  return {
    id: snippet.id,
    name: snippet.name,
    description: snippet.description || '',
    sql: snippet.sql,
    dbType: snippet.dbType || '',
    tags: snippet.tags || ''
  };
}

/** 从当前编辑器内容起草一个片段：名字先用第一行有意义的文本兜底。 */
export function snippetDraftFromSql(sql: string, dbType?: string): SqlSnippetDraft {
  return { ...EMPTY_SNIPPET_DRAFT, name: suggestSnippetName(sql), sql, dbType: dbType || '' };
}

export function suggestSnippetName(sql: string): string {
  const line = sql
    .split('\n')
    .map((item) => item.replace(/^\s*--\s?/, '').trim())
    .find((item) => item.length > 0);
  if (!line) return '';
  return line.length > MAX_SNIPPET_NAME_LENGTH ? line.slice(0, MAX_SNIPPET_NAME_LENGTH) : line;
}

export function parseSnippetTags(tags?: string): string[] {
  if (!tags) return [];
  const seen = new Set<string>();
  const result: string[] = [];
  for (const raw of tags.split(/[,，\s]+/)) {
    const tag = raw.trim();
    if (!tag || seen.has(tag)) continue;
    seen.add(tag);
    result.push(tag);
    if (result.length >= MAX_SNIPPET_TAGS) break;
  }
  return result;
}

export type SnippetValidation = { valid: boolean; nameError?: string; sqlError?: string };

export function validateSnippetDraft(draft: SqlSnippetDraft): SnippetValidation {
  const name = draft.name.trim();
  const sql = draft.sql.trim();
  const nameError = !name
    ? '请输入片段名称'
    : name.length > MAX_SNIPPET_NAME_LENGTH
      ? `名称最多 ${MAX_SNIPPET_NAME_LENGTH} 个字符`
      : undefined;
  const sqlError = sql ? undefined : '片段内容不能为空';
  return { valid: !nameError && !sqlError, nameError, sqlError };
}

export function snippetRequestBody(draft: SqlSnippetDraft) {
  return {
    name: draft.name.trim(),
    description: draft.description.trim() || undefined,
    sql: draft.sql,
    // 空串在后端表示「通用」，别把它当成一个叫 "" 的数据库类型。
    dbType: draft.dbType.trim() || undefined,
    tags: parseSnippetTags(draft.tags).join(',') || undefined
  };
}

export function snippetListParams(keyword: string, dbType?: string): string {
  const params = new URLSearchParams();
  const normalized = keyword.trim();
  if (normalized) params.set('keyword', normalized);
  if (dbType) params.set('dbType', dbType);
  return params.toString();
}

/** 片段列表里的一行副标题。 */
export function snippetSubtitle(snippet: SqlSnippet): string {
  const parts: string[] = [];
  if (snippet.dbType) parts.push(snippet.dbType.toUpperCase());
  else parts.push('通用');
  if (snippet.useCount > 0) parts.push(`用过 ${snippet.useCount} 次`);
  return parts.join(' · ');
}

/** 把片段插到当前 SQL 后面，保证中间恰好隔一个空行。 */
export function appendSnippetToSql(currentSql: string, snippetSql: string): string {
  const base = currentSql.replace(/\s+$/, '');
  if (!base) return snippetSql;
  return `${base}\n\n${snippetSql}`;
}
