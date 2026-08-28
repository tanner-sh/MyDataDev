import { describe, expect, it } from 'vitest';
import {
  BACKGROUND_ACTIVE_INTERVAL_MS,
  BACKGROUND_CONNECTING_INTERVAL_MS,
  BACKGROUND_IDLE_INTERVAL_MS,
  BACKGROUND_RECONCILE_INTERVAL_MS,
  backgroundTaskPolling,
  backgroundTaskStreamUrl,
  nextStreamState,
  parseBackgroundTaskEvent
} from './backgroundTaskStream';

describe('background task stream', () => {
  it('订阅地址带上连接 id', () => {
    expect(backgroundTaskStreamUrl(42)).toContain('/restores/operations/stream?connectionId=42');
  });

  it('连接状态跟随 EventSource 的结局迁移', () => {
    expect(nextStreamState('connecting', 'open')).toBe('live');
    expect(nextStreamState('live', 'retrying')).toBe('connecting');
    expect(nextStreamState('live', 'closed')).toBe('fallback');
    expect(nextStreamState('connecting', 'unsupported')).toBe('fallback');
    // 已经放弃的通道不会因为一次重试事件被当成还有希望。
    expect(nextStreamState('fallback', 'retrying')).toBe('fallback');
    // 但真的连上了就该恢复。
    expect(nextStreamState('fallback', 'open')).toBe('live');
  });

  it('推送可用时只保留低频对账，退化后回到原来的节奏', () => {
    expect(backgroundTaskPolling('live', { watchingTasks: true }).intervalMs).toBe(BACKGROUND_RECONCILE_INTERVAL_MS);
    expect(backgroundTaskPolling('connecting', { watchingTasks: false }).intervalMs).toBe(BACKGROUND_CONNECTING_INTERVAL_MS);
    expect(backgroundTaskPolling('fallback', { watchingTasks: true }).intervalMs).toBe(BACKGROUND_ACTIVE_INTERVAL_MS);
    expect(backgroundTaskPolling('fallback', { watchingTasks: false }).intervalMs).toBe(BACKGROUND_IDLE_INTERVAL_MS);
  });

  it('解析推送消息，坏消息一律丢掉而不是抛出去', () => {
    const payload = JSON.stringify({ backups: [{ id: 1 }], restores: [], sqlFiles: [] });
    expect(parseBackgroundTaskEvent(payload)).toEqual({ backups: [{ id: 1 }], restores: [], sqlFiles: [] });

    expect(parseBackgroundTaskEvent('not json')).toBeNull();
    expect(parseBackgroundTaskEvent('null')).toBeNull();
    expect(parseBackgroundTaskEvent(JSON.stringify({ backups: [] }))).toBeNull();
    expect(parseBackgroundTaskEvent(undefined)).toBeNull();
    expect(parseBackgroundTaskEvent(42)).toBeNull();
  });
});
