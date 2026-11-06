/**
 * 脚本的语句分隔方式（与后端 SqlScriptDelimiter 同一套），以及执行脚本时服务端要用户拿主意的三种情形。
 *
 * <p>分隔方式按 SQL 标签页记：一个标签页里放的是 DBeaver 导出的「!」脚本，另一个是普通 SQL，
 * 设成全局的话切到另一个标签页一执行就会整份脚本被当成一条。</p>
 */
import { ApiError } from './api';

export type SqlDelimiterMode = 'AUTO' | 'CUSTOM' | 'BLANK_LINE';
export type SqlDelimiterSetting = { mode: SqlDelimiterMode; token?: string };

export const DEFAULT_DELIMITER: SqlDelimiterSetting = { mode: 'AUTO' };
export const MAX_DELIMITER_LENGTH = 16;

export const DELIMITER_MODE_OPTIONS: ReadonlyArray<{ value: SqlDelimiterMode; label: string; short: string; hint: string }> = [
  { value: 'AUTO', label: '分号（自动）', short: '分号', hint: '按分号分段；Oracle 系的 PL/SQL 块到 END; 或独占行 / 结束，MySQL 认 DELIMITER，SQL Server 认 GO。' },
  { value: 'BLANK_LINE', label: '空行分隔', short: '空行', hint: '在自动规则之上，空行也是语句边界；PL/SQL 块与存储过程内部的空行不算。' },
  { value: 'CUSTOM', label: '自定义分隔符', short: '自定义', hint: '只有独占一行的指定符号是边界（如 DBeaver 的 !），分号不再分段。' }
];

/** 与后端校验同一套规则，先在界面上拦住，免得一次往返才知道填错了。 */
export function customDelimiterProblem(token: string | undefined): string | null {
  const value = (token ?? '').trim();
  if (!value) return '请填写分隔符';
  if (value.length > MAX_DELIMITER_LENGTH) return `分隔符最多 ${MAX_DELIMITER_LENGTH} 个字符`;
  if (/\s/.test(value)) return '分隔符不能包含空白';
  if (value === ';') return '分号就是默认方式，请选「分号（自动）」';
  if (value.startsWith('--') || value.startsWith('/*')) return '分隔符不能以注释符开头';
  if (/^['"`]/.test(value)) return '分隔符不能以引号开头';
  return null;
}

export function normalizeDelimiterSetting(value: unknown): SqlDelimiterSetting | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const candidate = value as Record<string, unknown>;
  if (candidate.mode === 'BLANK_LINE') return { mode: 'BLANK_LINE' };
  if (candidate.mode === 'CUSTOM' && typeof candidate.token === 'string' && !customDelimiterProblem(candidate.token)) {
    return { mode: 'CUSTOM', token: candidate.token.trim() };
  }
  return undefined;
}

/** 请求体里的两个字段。自定义符号不合法时退回自动：发一个注定被拒的请求没有意义。 */
export function delimiterRequestFields(setting: SqlDelimiterSetting | undefined): { delimiterMode: SqlDelimiterMode; delimiter?: string } {
  if (setting?.mode === 'BLANK_LINE') return { delimiterMode: 'BLANK_LINE' };
  if (setting?.mode === 'CUSTOM' && !customDelimiterProblem(setting.token)) return { delimiterMode: 'CUSTOM', delimiter: setting.token!.trim() };
  return { delimiterMode: 'AUTO' };
}

export function delimiterLabel(setting: SqlDelimiterSetting | undefined): string {
  if (setting?.mode === 'BLANK_LINE') return '空行分隔';
  if (setting?.mode === 'CUSTOM' && setting.token) return `独占行 ${setting.token.trim()}`;
  return '分号（自动）';
}

export type ScriptProblem =
  | { kind: 'delimiter-detected'; delimiter: string; statementCount: number; message: string }
  | { kind: 'large-script'; statementCount: number; threshold: number; message: string }
  | { kind: 'too-large'; statementCount: number; limit: number; message: string };

/** 执行脚本时服务端抛回来、要用户选一条路的三种 409/400。不是这三种就返回 undefined，照常当错误处理。 */
export function scriptProblemOf(error: unknown): ScriptProblem | undefined {
  if (!(error instanceof ApiError)) return undefined;
  const details = error.details;
  const count = typeof details.statementCount === 'number' ? details.statementCount : 0;
  if (error.code === 'SCRIPT_DELIMITER_DETECTED' && typeof details.delimiter === 'string') {
    return { kind: 'delimiter-detected', delimiter: details.delimiter, statementCount: count, message: error.message };
  }
  if (error.code === 'LARGE_SCRIPT_CONFIRMATION_REQUIRED') {
    return { kind: 'large-script', statementCount: count, threshold: typeof details.threshold === 'number' ? details.threshold : 0, message: error.message };
  }
  if (error.code === 'SCRIPT_TOO_LARGE') {
    return { kind: 'too-large', statementCount: count, limit: typeof details.limit === 'number' ? details.limit : 0, message: error.message };
  }
  return undefined;
}
