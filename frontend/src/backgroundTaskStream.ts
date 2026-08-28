/**
 * 后台任务进度的推送通道（SSE）与它的降级策略。
 *
 * 后端把「有没有变化」的判断收到了服务端，浏览器只在真的有变化时收到一条消息。但 SSE 会被
 * 一些反向代理和运行环境掐掉，所以这里保留轮询作为退路：连接状态决定还要不要轮、多久轮一次。
 *
 * 纯逻辑放在这里，EventSource 的生命周期在 hooks/useBackgroundTasks.ts。
 */
import { API } from './constants';
import type { ActiveOperations } from './types';

export type BackgroundStreamState =
  /** 还没连上，或断线后正在自动重连。 */
  | 'connecting'
  /** 推送可用，轮询退到最低频率。 */
  | 'live'
  /** 推送用不了，完全靠轮询。 */
  | 'fallback';

export type BackgroundStreamEvent = 'open' | 'retrying' | 'closed' | 'unsupported';

/** 推送正常时仍保留的对账轮询间隔：万一服务端的扫描线程停了，界面不会一直停在旧状态。 */
export const BACKGROUND_RECONCILE_INTERVAL_MS = 60_000;
export const BACKGROUND_ACTIVE_INTERVAL_MS = 2_000;
export const BACKGROUND_IDLE_INTERVAL_MS = 20_000;
export const BACKGROUND_CONNECTING_INTERVAL_MS = 5_000;

export function backgroundTaskStreamUrl(connectionId: number): string {
  return `${API}/restores/operations/stream?connectionId=${connectionId}`;
}

/**
 * EventSource 的三种结局映射到通道状态。
 *
 * `retrying` 是 EventSource 自己会重连的那种断线（readyState 仍是 CONNECTING），
 * `closed` 则是它已经放弃了 —— 这两种情况轮询的紧迫程度不一样。
 */
export function nextStreamState(current: BackgroundStreamState, event: BackgroundStreamEvent): BackgroundStreamState {
  switch (event) {
    case 'open':
      return 'live';
    case 'retrying':
      // 已经彻底放弃的通道不该因为一次重试事件被当成「还有希望」。
      return current === 'fallback' ? 'fallback' : 'connecting';
    case 'closed':
    case 'unsupported':
      return 'fallback';
    default:
      return current;
  }
}

/**
 * 当前状态下要不要轮询、多久一次。
 *
 * 推送可用时只留一个很慢的对账；退化时回到原来的节奏：备份抽屉开着或有任务在跑就 2 秒，
 * 否则 20 秒。
 */
export function backgroundTaskPolling(
  state: BackgroundStreamState,
  options: { watchingTasks: boolean }
): { intervalMs: number } {
  if (state === 'live') return { intervalMs: BACKGROUND_RECONCILE_INTERVAL_MS };
  if (state === 'connecting') return { intervalMs: BACKGROUND_CONNECTING_INTERVAL_MS };
  return { intervalMs: options.watchingTasks ? BACKGROUND_ACTIVE_INTERVAL_MS : BACKGROUND_IDLE_INTERVAL_MS };
}

/**
 * 解析一条推送消息。
 *
 * 坏消息一律丢掉而不是抛出去：推送是尽力而为的通道，一条读不懂的消息不该让整个界面报错，
 * 下一次变化或者对账轮询会把状态补回来。
 */
export function parseBackgroundTaskEvent(data: unknown): ActiveOperations | null {
  if (typeof data !== 'string') return null;
  try {
    const parsed = JSON.parse(data) as Partial<ActiveOperations>;
    if (!parsed || typeof parsed !== 'object') return null;
    if (!Array.isArray(parsed.backups) || !Array.isArray(parsed.restores) || !Array.isArray(parsed.sqlFiles)) {
      return null;
    }
    return { backups: parsed.backups, restores: parsed.restores, sqlFiles: parsed.sqlFiles };
  } catch {
    return null;
  }
}
