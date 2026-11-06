import { apiErrorCode } from './api';
import type { SqlFileExecution } from './types';

/** 前台：占着一个请求等结果，受 SQL 超时约束（默认 60 秒），可取消。后台：交给命令窗口那条后台任务。 */
export type RoutineInvokeMode = 'foreground' | 'background';

export type RoutineInvokeFailure = {
  kind: 'timeout' | 'cancelled' | 'error';
  title: string;
  message: string;
  /** 超时了才建议改走后台：别的失败换个执行方式也不会好。 */
  suggestBackground: boolean;
};

export const ROUTINE_INVOKE_MODE_HINTS: Record<RoutineInvokeMode, string> = {
  foreground: '等待结果返回，执行中可以取消；超过 SQL 超时（默认 60 秒）会被数据库中止。',
  background: '交给后台任务执行，单条语句最长 2 小时（默认），可随时取消；返回值、输出参数与结果集写进执行日志。适合跑批类的长过程。'
};

export function routineInvokePath(connectionId: number, mode: RoutineInvokeMode) {
  return `/metadata/${connectionId}/schema-objects/${mode === 'background' ? 'invoke-background' : 'invoke'}`;
}

/**
 * 调用失败之后放在调用对话框里的那句话。返回 undefined 表示不用提示（请求被页面自己中止，比如关闭了抽屉）。
 * 超时与取消的说明文字由服务端给出（它知道配置的超时是多少），这里只决定标题、语气和要不要建议后台执行。
 */
export function describeRoutineInvokeFailure(error: unknown): RoutineInvokeFailure | undefined {
  if (error instanceof Error && error.name === 'AbortError') return undefined;
  const message = error instanceof Error && error.message ? error.message : '例程调用失败';
  const code = apiErrorCode(error);
  if (code === 'SQL_TIMEOUT') return { kind: 'timeout', title: '调用超时，已被中止', message, suggestBackground: true };
  if (code === 'SQL_CANCELLED') return { kind: 'cancelled', title: '调用已取消', message, suggestBackground: false };
  if (code === 'OPERATION_RESULT_UNKNOWN') {
    return { kind: 'error', title: '没有收到调用结果', message: `${message} 过程可能仍在数据库中执行，请先核对数据，不要直接重试。`, suggestBackground: false };
  }
  return { kind: 'error', title: '调用失败', message, suggestBackground: false };
}

/** 后台提交没能开始（解析失败、未开始执行）时服务端仍返回任务，状态为 FAILED。 */
export function backgroundSubmitFailure(job: SqlFileExecution): RoutineInvokeFailure | undefined {
  if (job.status !== 'FAILED') return undefined;
  return { kind: 'error', title: '后台调用未能开始', message: job.message || '后台任务未能开始执行。', suggestBackground: false };
}
