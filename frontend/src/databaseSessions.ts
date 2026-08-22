/**
 * 目标库上的活动会话。
 *
 * 此前只能看到本工具自己的后台任务与前台 SQL，看不到目标库上到底在跑什么 —— 排查「谁把表
 * 锁住了」只能切到数据库自带的客户端。语句本身收在后端方言里，这里只负责展示逻辑。
 */

export type DatabaseSession = {
  sessionId?: string | null;
  user?: string | null;
  host?: string | null;
  database?: string | null;
  state?: string | null;
  command?: string | null;
  durationSeconds?: number | null;
  sql?: string | null;
};

export type DatabaseSessionPage = {
  supported: boolean;
  canKill: boolean;
  sessions: DatabaseSession[];
  message?: string | null;
};

export const SESSION_POLL_INTERVAL_MS = 5_000;
/** 超过这个时长的会话高亮：长事务/长查询往往就是锁的来源。 */
export const LONG_RUNNING_SECONDS = 60;

export function formatSessionDuration(seconds?: number | null): string {
  if (seconds == null || seconds < 0) return '—';
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} 分 ${seconds % 60} 秒`;
  const hours = Math.floor(minutes / 60);
  return `${hours} 小时 ${minutes % 60} 分`;
}

export function isLongRunning(session: DatabaseSession): boolean {
  return (session.durationSeconds ?? 0) >= LONG_RUNNING_SECONDS;
}

/** 空闲会话（没有正在执行的语句）排在后面，正在跑的先看。 */
export function orderSessions(sessions: DatabaseSession[]): DatabaseSession[] {
  return sessions
    .map((session, index) => ({ session, index }))
    .sort((left, right) => {
      const leftIdle = isIdle(left.session) ? 1 : 0;
      const rightIdle = isIdle(right.session) ? 1 : 0;
      if (leftIdle !== rightIdle) return leftIdle - rightIdle;
      const leftDuration = left.session.durationSeconds ?? -1;
      const rightDuration = right.session.durationSeconds ?? -1;
      return rightDuration - leftDuration || left.index - right.index;
    })
    .map(({ session }) => session);
}

export function isIdle(session: DatabaseSession): boolean {
  const marker = `${session.state || ''} ${session.command || ''}`.toLowerCase();
  return !session.sql?.trim() || marker.includes('sleep') || marker.includes('idle');
}

export function sessionLabel(session: DatabaseSession): string {
  const parts = [session.user, session.host].filter(Boolean);
  return parts.length > 0 ? parts.join('@') : session.sessionId || '未知会话';
}

/** 无法定位的会话不能终止 —— 没有 id 就没有可执行的语句。 */
export function canKillSession(page: DatabaseSessionPage, session: DatabaseSession): boolean {
  return page.canKill && Boolean(session.sessionId?.trim());
}

export function sessionSummary(page: DatabaseSessionPage): string {
  if (!page.supported) return page.message || '当前数据库类型暂不支持查看活动会话';
  if (page.message) return page.message;
  const active = page.sessions.filter((session) => !isIdle(session)).length;
  return `共 ${page.sessions.length} 个会话，其中 ${active} 个正在执行`;
}
