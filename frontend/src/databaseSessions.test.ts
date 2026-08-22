import { describe, expect, it } from 'vitest';
import {
  canKillSession,
  formatSessionDuration,
  isIdle,
  isLongRunning,
  LONG_RUNNING_SECONDS,
  orderSessions,
  sessionLabel,
  sessionSummary,
  type DatabaseSession,
  type DatabaseSessionPage
} from './databaseSessions';

const session = (overrides: Partial<DatabaseSession> = {}): DatabaseSession =>
  ({ sessionId: '1', user: 'app', host: '10.0.0.1', sql: 'select 1', durationSeconds: 3, ...overrides });

const page = (overrides: Partial<DatabaseSessionPage> = {}): DatabaseSessionPage =>
  ({ supported: true, canKill: true, sessions: [], ...overrides });

describe('formatSessionDuration', () => {
  it('scales the unit and copes with missing values', () => {
    expect(formatSessionDuration(5)).toBe('5 秒');
    expect(formatSessionDuration(125)).toBe('2 分 5 秒');
    expect(formatSessionDuration(3_725)).toBe('1 小时 2 分');
    expect(formatSessionDuration(null)).toBe('—');
    expect(formatSessionDuration(-1)).toBe('—');
  });
});

describe('isIdle', () => {
  it('treats sleeping and query-less sessions as idle', () => {
    expect(isIdle(session({ command: 'Sleep' }))).toBe(true);
    expect(isIdle(session({ state: 'idle' }))).toBe(true);
    expect(isIdle(session({ sql: '   ' }))).toBe(true);
    expect(isIdle(session({ sql: null }))).toBe(true);
    expect(isIdle(session())).toBe(false);
  });
});

describe('orderSessions', () => {
  it('puts running sessions first, longest first, and keeps idle ones last', () => {
    const ordered = orderSessions([
      session({ sessionId: 'idle', command: 'Sleep', durationSeconds: 999 }),
      session({ sessionId: 'short', durationSeconds: 2 }),
      session({ sessionId: 'long', durationSeconds: 500 })
    ]);

    expect(ordered.map((s) => s.sessionId)).toEqual(['long', 'short', 'idle']);
  });

  it('is stable for sessions with the same duration', () => {
    const ordered = orderSessions([
      session({ sessionId: 'a', durationSeconds: 5 }),
      session({ sessionId: 'b', durationSeconds: 5 })
    ]);
    expect(ordered.map((s) => s.sessionId)).toEqual(['a', 'b']);
  });
});

describe('isLongRunning', () => {
  it('flags sessions at or past the threshold', () => {
    expect(isLongRunning(session({ durationSeconds: LONG_RUNNING_SECONDS }))).toBe(true);
    expect(isLongRunning(session({ durationSeconds: LONG_RUNNING_SECONDS - 1 }))).toBe(false);
    expect(isLongRunning(session({ durationSeconds: null }))).toBe(false);
  });
});

describe('sessionLabel', () => {
  it('prefers user@host and falls back to the id', () => {
    expect(sessionLabel(session())).toBe('app@10.0.0.1');
    expect(sessionLabel(session({ host: null }))).toBe('app');
    expect(sessionLabel(session({ user: null, host: null }))).toBe('1');
    expect(sessionLabel(session({ user: null, host: null, sessionId: null }))).toBe('未知会话');
  });
});

describe('canKillSession', () => {
  it('needs both dialect support and a usable id', () => {
    expect(canKillSession(page(), session())).toBe(true);
    expect(canKillSession(page({ canKill: false }), session())).toBe(false);
    expect(canKillSession(page(), session({ sessionId: '  ' }))).toBe(false);
    expect(canKillSession(page(), session({ sessionId: null }))).toBe(false);
  });
});

describe('sessionSummary', () => {
  it('distinguishes unsupported, failed and normal listings', () => {
    expect(sessionSummary(page({ supported: false, message: '暂不支持' }))).toBe('暂不支持');
    expect(sessionSummary(page({ message: '权限不足' }))).toBe('权限不足');
    expect(sessionSummary(page({ sessions: [session(), session({ command: 'Sleep' })] })))
      .toBe('共 2 个会话，其中 1 个正在执行');
  });
});
