import { describe, expect, it } from 'vitest';
import {
  canKillSession,
  filterRunningSessions,
  formatSessionDuration,
  isIdle,
  isLongRunning,
  LONG_RUNNING_SECONDS,
  orderSessions,
  analyzeBlocking,
  blockedCountLabel,
  blockingHighlights,
  blockingSummary,
  EMPTY_BLOCKING_ANALYSIS,
  sessionDurationLabel,
  sessionLabel,
  sessionSummary,
  type DatabaseSession,
  type DatabaseSessionPage,
  type SessionBlock
} from './databaseSessions';

const session = (overrides: Partial<DatabaseSession> = {}): DatabaseSession =>
  ({ sessionId: '1', user: 'app', host: '10.0.0.1', sql: 'select 1', durationSeconds: 3, ...overrides });

const page = (overrides: Partial<DatabaseSessionPage> = {}): DatabaseSessionPage =>
  ({ supported: true, canKill: true, sessions: [], blockingSupported: true, blocks: [], ...overrides });

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

  it('空闲会话再久也不算长时间运行', () => {
    // MySQL 的 PROCESSLIST.TIME 对 Sleep 会话是「已空闲多久」。按「已运行多久」高亮，
    // 等于对着一批什么都没做的连接池连接报警。
    expect(isLongRunning(session({ command: 'Sleep', sql: null, durationSeconds: 6_120 }))).toBe(false);
    expect(isLongRunning(session({ sql: null, durationSeconds: 6_120 }))).toBe(false);
  });
});

describe('sessionDurationLabel', () => {
  it('空闲会话的时长写明是空闲，避免被读成「跑了这么久」', () => {
    expect(sessionDurationLabel(session({ command: 'Sleep', sql: null, durationSeconds: 6_120 })))
      .toBe('空闲 1 小时 42 分');
    expect(sessionDurationLabel(session({ durationSeconds: 90 }))).toBe('1 分 30 秒');
    expect(sessionDurationLabel(session({ durationSeconds: null }))).toBe('—');
  });
});

describe('filterRunningSessions', () => {
  it('只看执行中时滤掉空闲会话', () => {
    const rows = [session(), session({ command: 'Sleep', sql: null })];
    expect(filterRunningSessions(rows, true)).toHaveLength(1);
    expect(filterRunningSessions(rows, false)).toHaveLength(2);
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
      .toBe('共 2 个会话：1 个正在执行，1 个空闲（多为各客户端连接池常驻的连接）');
    expect(sessionSummary(page({ sessions: [session(), session()] })))
      .toBe('共 2 个会话，全部正在执行');
  });
});

describe('analyzeBlocking', () => {
  const block = (blocked: string, blocking: string, overrides: Partial<SessionBlock> = {}): SessionBlock =>
    ({ blockedSessionId: blocked, blockingSessionId: blocking, waitObject: 'shop.orders', waitSeconds: 10, ...overrides });

  it('没有阻塞边时不构造任何树', () => {
    expect(analyzeBlocking({ blocks: [], sessions: [] })).toEqual(EMPTY_BLOCKING_ANALYSIS);
  });

  /** 「该杀哪一个」的答案就是链的根，所以根必须能被找出来。 */
  it('把一条等待链拼成从根到叶的树', () => {
    const analysis = analyzeBlocking({
      blocks: [block('9', '7'), block('11', '9')],
      sessions: [session({ sessionId: '7', user: 'batch' })]
    });

    expect(analysis.trees).toHaveLength(1);
    expect(analysis.trees[0].sessionId).toBe('7');
    expect(analysis.trees[0].session?.user).toBe('batch');
    expect(analysis.trees[0].children.map((child) => child.sessionId)).toEqual(['9']);
    expect(analysis.trees[0].children[0].children.map((child) => child.sessionId)).toEqual(['11']);
    expect(analysis.blockedSessions).toBe(2);
  });

  /** 根节点上那个数就是「终止它能放开几个会话」，间接的也要算进来。 */
  it('每个节点带上下游被堵的会话总数（含间接）', () => {
    const analysis = analyzeBlocking({ blocks: [block('9', '7'), block('11', '9'), block('12', '7')], sessions: [] });

    expect(analysis.trees[0].blockedCount).toBe(3);
    expect(blockedCountLabel(analysis.trees[0])).toBe('堵住 3 个会话');
    expect(blockedCountLabel(analysis.trees[0].children[1])).toBe('');
  });

  /** 阻塞者常常不在会话列表里（列表有条数上限，权限也可能只让人看到自己的会话）。 */
  it('阻塞者不在会话列表里时保留节点而不是丢掉整条链', () => {
    const analysis = analyzeBlocking({ blocks: [block('9', '7')], sessions: [session({ sessionId: '9' })] });

    expect(analysis.trees[0].sessionId).toBe('7');
    expect(analysis.trees[0].session).toBeUndefined();
    expect(analysis.trees[0].children[0].session?.sessionId).toBe('9');
  });

  /** 被阻塞节点上挂的是它自己在等什么，不是阻塞者的。 */
  it('等待对象与等待时长挂在被阻塞的那个节点上', () => {
    const analysis = analyzeBlocking({
      blocks: [block('9', '7', { waitObject: 'shop.items', waitSeconds: 42, blockedSql: 'UPDATE items' })],
      sessions: []
    });

    expect(analysis.trees[0].waitObject).toBeUndefined();
    expect(analysis.trees[0].children[0]).toMatchObject({
      waitObject: 'shop.items', waitSeconds: 42, blockedSql: 'UPDATE items'
    });
  });

  /**
   * 互相等待时一个根都找不到，而递归展开会无限下去。
   *
   * <p>这已经不是「谁堵了谁」而是死锁，处理方式完全不同，所以环上的会话要单独报出来。</p>
   */
  it('挡住互相等待的环并把环上的会话报出来', () => {
    const analysis = analyzeBlocking({ blocks: [block('7', '9'), block('9', '7')], sessions: [] });

    expect(analysis.cycleSessionIds.sort()).toEqual(['7', '9']);
    expect(analysis.trees).toHaveLength(1);
    // 展开到重复出现的那一层就停，不再往下。
    expect(analysis.trees[0].children[0].children[0].children).toEqual([]);
  });

  it('一个会话被多个会话同时阻塞时挂在每个阻塞者下面', () => {
    const analysis = analyzeBlocking({ blocks: [block('11', '7'), block('11', '9')], sessions: [] });

    expect(analysis.trees.map((tree) => tree.sessionId).sort()).toEqual(['7', '9']);
    expect(analysis.blockedSessions).toBe(1);
  });
});

describe('blockingSummary', () => {
  it('方言不支持时说明原因', () => {
    expect(blockingSummary(page({ blockingSupported: false, blockingMessage: '暂不支持' }), EMPTY_BLOCKING_ANALYSIS))
      .toBe('暂不支持');
  });

  /** 留一片空白会被读成「这个功能没生效」。 */
  it('没有阻塞时明确说没有', () => {
    expect(blockingSummary(page(), EMPTY_BLOCKING_ANALYSIS)).toBe('没有会话在等待锁');
  });

  it('有阻塞时给出被堵数量和源头数量', () => {
    const analysis = analyzeBlocking({
      blocks: [{ blockedSessionId: '9', blockingSessionId: '7' }],
      sessions: []
    });
    expect(blockingSummary(page(), analysis)).toBe('1 个会话在等待锁，源头是 1 个会话');
  });

  it('检测到环时把死锁这件事说出来', () => {
    const analysis = analyzeBlocking({
      blocks: [{ blockedSessionId: '7', blockingSessionId: '9' }, { blockedSessionId: '9', blockingSessionId: '7' }],
      sessions: []
    });
    expect(blockingSummary(page(), analysis)).toContain('可能是死锁');
  });

  it('读取失败时优先显示失败原因', () => {
    expect(blockingSummary(page({ blockingMessage: '读取阻塞关系失败：权限不足' }), EMPTY_BLOCKING_ANALYSIS))
      .toContain('权限不足');
  });
});

describe('blockingHighlights', () => {
  it('分别给出被堵与堵人的会话号，供会话表打标', () => {
    const highlights = blockingHighlights([{ blockedSessionId: '9', blockingSessionId: '7' }]);
    expect([...highlights.blocked]).toEqual(['9']);
    expect([...highlights.blocking]).toEqual(['7']);
  });
});
