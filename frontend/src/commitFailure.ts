/**
 * 把一次提交失败拆成界面上要显示的几块。
 *
 * <p>这个模块存在的原因：提交失败原来只有一行 toast，内容是 `localizeError` 的结果，而
 * `localizeError` 对 `INTERNAL_ERROR` 是**整句覆盖** —— 后端明明返回了「…如需反馈请提供错误
 * 编号 a1b2c3d4。」，前端把它换成了没有编号的那一句，于是唯一能拿去查服务端日志的线索就没了。
 * 用户看到的是「服务器内部错误，请稍后重试。」，然后无从下手。</p>
 *
 * <p>所以这里刻意不走 `localizeError`：后端的问题详情本来就是中文，直接用；`localizeMessage`
 * 里那些按内容匹配的老规则也不适用于这条路径。</p>
 */

import { ApiError } from './api';
import { redactDiagnostic } from './errorFeedback';

export type CommitFailure = {
  title: string;
  /** 后端原文（已脱敏）。长错误要能整段读、整段复制，不截断。 */
  message: string;
  code: string;
  /** 数据库返回的 SQLSTATE，后端在 SQL_ERROR 分支带上。 */
  sqlState?: string;
  /** 服务端日志里的错误编号，拿它能直接搜到堆栈。 */
  requestId?: string;
  /** 冲突时后端指出的第几条变更（从 0 起），用来告诉用户是哪一行。 */
  changeIndex?: number;
  /** 「复制诊断信息」按钮写进剪贴板的全文。 */
  copyText: string;
};

const TITLES: Record<string, string> = {
  DATA_EDIT_CONFLICT: '数据已被其他操作修改',
  PRODUCTION_CONFIRMATION_REQUIRED: '需要确认生产连接',
  TARGET_DATABASE_UNAVAILABLE: '数据库暂时无法连接',
  SQL_ERROR: '数据库拒绝了这次修改',
  READONLY_CONNECTION: '当前连接为只读连接',
  INTERNAL_ERROR: '服务端未能处理这次提交'
};

function readString(details: Record<string, unknown>, key: string): string | undefined {
  const value = details[key];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

export function describeCommitFailure(error: unknown): CommitFailure {
  const apiError = error instanceof ApiError ? error : undefined;
  const code = apiError?.code || (error instanceof Error ? 'REQUEST_FAILED' : 'UNKNOWN');
  const rawMessage = error instanceof Error && error.message ? error.message : '提交未能完成，且没有返回原因。';
  const details = apiError?.details ?? {};
  const changeIndexValue = details.changeIndex;
  const failure: CommitFailure = {
    title: TITLES[code] || '提交失败',
    message: redactDiagnostic(rawMessage),
    code,
    sqlState: readString(details, 'sqlState'),
    requestId: apiError?.requestId,
    changeIndex: typeof changeIndexValue === 'number' ? changeIndexValue : undefined,
    copyText: ''
  };
  failure.copyText = [
    failure.title,
    failure.message,
    `错误码：${failure.code}`,
    failure.sqlState ? `SQLSTATE：${failure.sqlState}` : undefined,
    `错误编号：${failure.requestId || '未提供'}`,
    failure.changeIndex === undefined ? undefined : `失败的变更序号：第 ${failure.changeIndex + 1} 条`,
    `时间：${new Date().toISOString()}`
  ].filter((line): line is string => Boolean(line)).join('\n');
  return failure;
}
