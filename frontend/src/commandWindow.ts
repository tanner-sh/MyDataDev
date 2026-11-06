/**
 * 命令窗口的纯逻辑：选项、请求体、日志合并与定位。
 *
 * <p>命令窗口与普通执行的分工：普通执行同步跑完、每条结果可以在表格里看和改；命令窗口交给后台
 * 逐条执行，结果是一条滚动日志（像 SQL*Plus），几千条也不会把界面占住，可以遇错继续。</p>
 */
import type { SqlCommandLogEntry, SqlFileExecution } from './types';

export type CommandWindowOptions = {
  onError: 'STOP' | 'CONTINUE';
  transactionMode: 'AUTOCOMMIT' | 'SCRIPT';
  endOfFileAction: 'COMMIT' | 'ROLLBACK';
};

export const DEFAULT_COMMAND_OPTIONS: CommandWindowOptions = { onError: 'STOP', transactionMode: 'AUTOCOMMIT', endOfFileAction: 'ROLLBACK' };
/** 浏览器里最多留这么多行；更早的丢掉并在顶部说明（服务端仍保留完整日志直到任务被清理）。 */
export const MAX_CLIENT_LOG_LINES = 5000;

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'CANCELLED', 'EXPIRED']);

/** 脚本控制事务只在 Oracle 系上有（与后台文件任务同一条限制）。 */
export function supportsScriptTransaction(dbType: string | undefined): boolean {
  return ['oracle', 'oceanbase-oracle'].includes((dbType ?? '').toLowerCase());
}

/** 换了连接后，脚本控制事务可能不再可用：退回逐条提交，而不是发一个注定被拒的请求。 */
export function effectiveCommandOptions(options: CommandWindowOptions | undefined, dbType: string | undefined): CommandWindowOptions {
  const value = options ?? DEFAULT_COMMAND_OPTIONS;
  return value.transactionMode === 'SCRIPT' && !supportsScriptTransaction(dbType) ? { ...value, transactionMode: 'AUTOCOMMIT' } : value;
}

export function commandOptionsLabel(options: CommandWindowOptions): string {
  const transaction = options.transactionMode === 'SCRIPT'
    ? `脚本控制事务，结束时${options.endOfFileAction === 'COMMIT' ? '提交' : '回滚'}`
    : '逐条提交';
  return `${transaction} · 遇错${options.onError === 'CONTINUE' ? '继续' : '停止'}`;
}

export function isTerminalJob(job: Pick<SqlFileExecution, 'status'> | undefined): boolean {
  return Boolean(job && TERMINAL.has(job.status));
}

/** 追加一批日志：按 seq 去重（轮询重叠时不重复），超出上限丢最早的。 */
export function mergeCommandLog(existing: SqlCommandLogEntry[], incoming: SqlCommandLogEntry[], max = MAX_CLIENT_LOG_LINES)
  : { entries: SqlCommandLogEntry[]; dropped: number } {
  const last = existing.length ? existing[existing.length - 1].seq : 0;
  const fresh = incoming.filter((entry) => entry.seq > last);
  const merged = fresh.length ? [...existing, ...fresh] : existing;
  const dropped = Math.max(0, merged.length - max);
  return { entries: dropped ? merged.slice(dropped) : merged, dropped };
}

export function lastSeq(entries: SqlCommandLogEntry[]): number {
  return entries.length ? entries[entries.length - 1].seq : 0;
}

/**
 * 日志里的行号（相对提交的那段文字）换成编辑器里的偏移，用来选中出错的语句。
 * 行号从 1 开始；超出范围时夹到文字两端。
 */
export function lineRangeOffsets(text: string, startLine: number, endLine: number, baseOffset = 0): { start: number; end: number } {
  const starts = [0];
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '\n') starts.push(i + 1);
  }
  const clamp = (line: number) => Math.min(Math.max(line, 1), starts.length);
  const first = clamp(startLine);
  const last = clamp(Math.max(endLine, startLine));
  const lineEnd = last < starts.length ? starts[last] - 1 : text.length;
  return { start: baseOffset + starts[first - 1], end: baseOffset + Math.max(starts[first - 1], lineEnd) };
}

/** 头部一行：进度、失败数、阶段。 */
export function commandProgressText(job: SqlFileExecution | undefined, failedCount: number): string {
  if (!job) return '正在提交…';
  const total = job.statementTotal ?? 0;
  const failed = failedCount > 0 ? ` · 失败 ${failedCount}` : '';
  if (job.status === 'ANALYZING' || job.status === 'READY' || job.status === 'QUEUED') return `排队中 · 共 ${total} 条`;
  if (job.status === 'RUNNING') return `执行中 ${job.statementCurrent} / ${total}${failed}`;
  if (job.status === 'SUCCESS') return `${failedCount > 0 ? '已完成（有失败）' : '已完成'} · 成功 ${job.successCount} / ${total}${failed}`;
  if (job.status === 'CANCELLED') return `已取消 · 成功 ${job.successCount} / ${total}${failed}`;
  return `已停止 · 成功 ${job.successCount} / ${total}${failed}`;
}

/** 复制整段日志时的纯文本形状，接近 SQL*Plus 的输出。 */
export function commandLogText(entries: SqlCommandLogEntry[]): string {
  return entries.map((entry) => {
    switch (entry.kind) {
      case 'STATEMENT': return `SQL> ${entry.text}`;
      case 'ERROR': return `错误${entry.startLine ? `（第 ${entry.startLine}${entry.endLine && entry.endLine !== entry.startLine ? `–${entry.endLine}` : ''} 行）` : ''}：${entry.text}`;
      case 'INFO': return `-- ${entry.text}`;
      default: return entry.text;
    }
  }).join('\n');
}
