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

/** 一条阻塞关系：blockedSessionId 在等 blockingSessionId。 */
export type SessionBlock = {
  blockedSessionId: string;
  blockingSessionId: string;
  waitObject?: string | null;
  waitSeconds?: number | null;
  blockedSql?: string | null;
};

export type DatabaseSessionPage = {
  supported: boolean;
  canKill: boolean;
  sessions: DatabaseSession[];
  message?: string | null;
  /** 这个数据库类型能不能查阻塞关系。 */
  blockingSupported: boolean;
  blocks: SessionBlock[];
  /** 不支持、或这次读失败的原因；与 message 分开，因为「会话读得到、阻塞读不到」很常见。 */
  blockingMessage?: string | null;
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

/**
 * 值得高亮的长时间运行会话。
 *
 * 空闲会话一律不算。各家的「时长」列对空闲连接说的都不是同一件事：MySQL 的
 * PROCESSLIST.TIME 对 Sleep 会话是「已空闲多久」，PostgreSQL 的 now()-query_start 是
 * 「距上一条语句开始多久」。把它们按「已运行多久」高亮成橙色，等于对着一批什么都没做的
 * 连接池连接报警 —— 而连接池本来就该常驻若干条空闲连接。
 */
export function isLongRunning(session: DatabaseSession): boolean {
  return !isIdle(session) && (session.durationSeconds ?? 0) >= LONG_RUNNING_SECONDS;
}

/**
 * 时长标签。空闲会话显式写成「空闲 N」，否则一个裸时长会被读成「跑了这么久」。
 */
export function sessionDurationLabel(session: DatabaseSession): string {
  const duration = formatSessionDuration(session.durationSeconds);
  if (duration === '—') return duration;
  return isIdle(session) ? `空闲 ${duration}` : duration;
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
  const idle = page.sessions.length - active;
  if (idle === 0) return `共 ${page.sessions.length} 个会话，全部正在执行`;
  // 说清楚空闲的那些是什么，否则「怎么这么多会话」是每个人的第一反应 —— 而它们通常
  // 只是其他客户端连接池里常驻的空闲连接。
  return `共 ${page.sessions.length} 个会话：${active} 个正在执行，${idle} 个空闲（多为各客户端连接池常驻的连接）`;
}

/** 「只看执行中」时留下的会话。 */
export function filterRunningSessions(sessions: DatabaseSession[], runningOnly: boolean): DatabaseSession[] {
  return runningOnly ? sessions.filter((session) => !isIdle(session)) : sessions;
}

/**
 * 等待链上的一个节点。
 *
 * <p>{@link BlockingNode.session} 可能是空的：阻塞者有时不在会话列表里（列表有条数上限，
 * 权限也可能只让人看到自己的会话）。这种情况要如实显示成一个只有会话号的节点，而不是把
 * 整条链丢掉 —— 链的根正是最该被看到的那一个。</p>
 */
export type BlockingNode = {
  sessionId: string;
  session?: DatabaseSession;
  /** 这个会话在等什么（只有被阻塞的节点才有）。 */
  waitObject?: string | null;
  waitSeconds?: number | null;
  blockedSql?: string | null;
  children: BlockingNode[];
  /** 这个节点下游一共堵住了多少个会话（含间接）。根节点的这个数就是「杀了它能放开几个」。 */
  blockedCount: number;
};

export type BlockingAnalysis = {
  trees: BlockingNode[];
  /** 处在互相等待环里的会话号。非空说明这不是一条链，而是一个环 —— 通常就是死锁。 */
  cycleSessionIds: string[];
  /** 被阻塞的会话总数。 */
  blockedSessions: number;
};

export const EMPTY_BLOCKING_ANALYSIS: BlockingAnalysis = { trees: [], cycleSessionIds: [], blockedSessions: 0 };

/**
 * 把「谁在等谁」的边拼成等待链。
 *
 * <p>会话列表能让人杀掉一个会话，却说不出该杀哪一个。真正要回答的是「链的根在哪」——
 * 所以每个节点都带上它下游堵了多少个会话，根节点那个数就是终止它能放开的会话数。</p>
 *
 * <p>必须挡住环：互相等待时递归下去会无限展开。环上的会话号单独报出来，因为那已经不是
 * 「谁堵了谁」而是死锁，处理方式完全不同。</p>
 */
export function analyzeBlocking(page: Pick<DatabaseSessionPage, 'blocks' | 'sessions'>): BlockingAnalysis {
  const blocks = page.blocks || [];
  if (blocks.length === 0) return EMPTY_BLOCKING_ANALYSIS;

  const sessionsById = new Map<string, DatabaseSession>();
  for (const session of page.sessions || []) {
    if (session.sessionId) sessionsById.set(session.sessionId, session);
  }
  const children = new Map<string, SessionBlock[]>();
  const blockedIds = new Set<string>();
  const blockerIds = new Set<string>();
  for (const block of blocks) {
    const list = children.get(block.blockingSessionId);
    if (list) list.push(block);
    else children.set(block.blockingSessionId, [block]);
    blockedIds.add(block.blockedSessionId);
    blockerIds.add(block.blockingSessionId);
  }

  const cycleSessionIds = findCycleMembers(children);
  // 根是「堵着别人、自己却没在等谁」的会话。全都在等（互相等待）时一个根都找不到 ——
  // 那时拿环上的会话当入口，否则整棵树连一个节点都显示不出来。
  let roots = [...blockerIds].filter((id) => !blockedIds.has(id));
  if (roots.length === 0) roots = cycleSessionIds.slice(0, 1);

  const trees = roots.map((id) => buildNode(id, undefined, children, sessionsById, new Set()));
  return { trees, cycleSessionIds, blockedSessions: blockedIds.size };
}

function buildNode(
  sessionId: string,
  edge: SessionBlock | undefined,
  children: Map<string, SessionBlock[]>,
  sessionsById: Map<string, DatabaseSession>,
  path: Set<string>
): BlockingNode {
  const node: BlockingNode = {
    sessionId,
    session: sessionsById.get(sessionId),
    waitObject: edge?.waitObject,
    waitSeconds: edge?.waitSeconds,
    blockedSql: edge?.blockedSql,
    children: [],
    blockedCount: 0
  };
  // 已经在当前路径上出现过就停：再展开一层就是无限递归。
  if (path.has(sessionId)) return node;
  const nextPath = new Set(path).add(sessionId);
  for (const child of children.get(sessionId) || []) {
    const built = buildNode(child.blockedSessionId, child, children, sessionsById, nextPath);
    node.children.push(built);
    node.blockedCount += 1 + built.blockedCount;
  }
  return node;
}

/** 环上的会话号。用三色 DFS：走到一个仍在栈上的节点，说明从它开始到当前这一段构成环。 */
function findCycleMembers(children: Map<string, SessionBlock[]>): string[] {
  const done = new Set<string>();
  const onStack: string[] = [];
  const onStackSet = new Set<string>();
  const members = new Set<string>();

  function visit(id: string) {
    if (done.has(id)) return;
    if (onStackSet.has(id)) {
      for (let index = onStack.lastIndexOf(id); index >= 0 && index < onStack.length; index++) {
        members.add(onStack[index]);
      }
      return;
    }
    onStack.push(id);
    onStackSet.add(id);
    for (const edge of children.get(id) || []) visit(edge.blockedSessionId);
    onStack.pop();
    onStackSet.delete(id);
    done.add(id);
  }

  for (const id of children.keys()) visit(id);
  return [...members];
}

/** 会话表里要标出来的两组会话号。 */
export function blockingHighlights(blocks: SessionBlock[]): { blocked: Set<string>; blocking: Set<string> } {
  return {
    blocked: new Set(blocks.map((block) => block.blockedSessionId)),
    blocking: new Set(blocks.map((block) => block.blockingSessionId))
  };
}

/**
 * 阻塞分析那一栏的结论。
 *
 * <p>没有阻塞时明确说「没有」，而不是留一片空白 —— 空白会被读成「这个功能没生效」。</p>
 */
export function blockingSummary(page: DatabaseSessionPage, analysis: BlockingAnalysis): string {
  if (!page.blockingSupported) return page.blockingMessage || '当前数据库类型暂不支持阻塞关系分析';
  if (page.blockingMessage) return page.blockingMessage;
  if (analysis.blockedSessions === 0) return '没有会话在等待锁';
  if (analysis.cycleSessionIds.length > 0) {
    return `${analysis.blockedSessions} 个会话在等待锁，其中 ${analysis.cycleSessionIds.length} 个互相等待（可能是死锁）`;
  }
  return `${analysis.blockedSessions} 个会话在等待锁，源头是 ${analysis.trees.length} 个会话`;
}

/** 根节点上那句「终止它能放开几个」。只对真正堵着别人的节点有意义。 */
export function blockedCountLabel(node: BlockingNode): string {
  return node.blockedCount === 0 ? '' : `堵住 ${node.blockedCount} 个会话`;
}
