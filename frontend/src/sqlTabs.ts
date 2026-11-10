import type { SqlTab } from './types';
import { createCommandTab, createSqlTab } from './utils';

/**
 * SQL 标签页的纯逻辑：标签增删改时「数组该变成什么样」。状态与弹窗在 hooks/useSqlTabs.tsx。
 *
 * <p>这些规则原来散在 App.tsx 的七个函数里，而且「把编辑器里的现值写回标签」这一步写了四遍 ——
 * 编辑器的改动要 300ms 才同步回标签，关闭、复制、新开命令窗口、离开页面这几处都得先补上它，
 * 否则最后几个字会丢。</p>
 */

/** 标签与当前标签：会话存储读出来、迁移出来的都是这个形状。 */
export type SqlSessionSnapshot = { tabs: SqlTab[]; activeTabId: string };

/**
 * 同时打开的 SQL 标签上限：每个标签都带着结果，开太多会把浏览器内存吃满。
 * 所有连接共用一条标签条之后，从每连接 20 个改为全部 30 个。
 */
export const MAX_SQL_TABS = 30;

export const SQL_TAB_LIMIT_MESSAGE = `最多同时打开 ${MAX_SQL_TABS} 个 SQL 标签页，请先关闭不需要的标签页。`;

/** 把编辑器里的现值写回对应标签；没传或没变时原样返回同一个数组。 */
export function withLiveSql(tabs: SqlTab[], tabId: string, liveSql: string | undefined): SqlTab[] {
  if (liveSql === undefined) return tabs;
  const target = tabs.find((tab) => tab.id === tabId);
  if (!target || target.sql === liveSql) return tabs;
  return tabs.map((tab) => tab.id === tabId ? { ...tab, sql: liveSql, dirty: true } : tab);
}

/** 合并一次修改：改了 SQL 就算有草稿，除非调用方明确给了 dirty。 */
export function patchSqlTab(tab: SqlTab, patch: Partial<SqlTab>): SqlTab {
  const sqlChanged = patch.sql !== undefined && patch.sql !== tab.sql;
  return { ...tab, ...patch, dirty: patch.dirty ?? (sqlChanged ? true : tab.dirty) };
}

/**
 * 关掉一个标签之后的标签与当前标签。关的是当前标签时落到它左边那个（没有就第一个）——
 * 和浏览器一样，不跳到最右边去。只剩一个标签时不关。
 */
export function closeSqlTabIn(tabs: SqlTab[], targetId: string, activeId: string): { tabs: SqlTab[]; activeId: string } {
  if (tabs.length <= 1) return { tabs, activeId };
  const targetIndex = tabs.findIndex((tab) => tab.id === targetId);
  if (targetIndex < 0) return { tabs, activeId };
  const nextTabs = tabs.filter((tab) => tab.id !== targetId);
  if (targetId !== activeId) return { tabs: nextTabs, activeId };
  // 标签来自多条连接时，先落到同一条连接的邻居上：关一个标签不该顺带把整个工作台切到另一个库。
  const connectionId = tabs[targetIndex].connectionId;
  const sameConnection = (tab: SqlTab) => tab.connectionId === connectionId;
  const left = nextTabs.slice(0, targetIndex).reverse().find(sameConnection);
  const right = nextTabs.slice(targetIndex).find(sameConnection);
  const fallback = nextTabs[Math.max(0, targetIndex - 1)] ?? nextTabs[0];
  return { tabs: nextTabs, activeId: (left ?? right ?? fallback).id };
}

/**
 * 给还没有连接的标签补上连接（升级前「未选连接」的会话、迁移前的默认标签）；没有要改的就原样返回。
 * 不动 dirty：这是补缺省值，不是用户改了标签。
 */
export function bindTabConnections(tabs: SqlTab[], connectionId: number | undefined): SqlTab[] {
  if (connectionId == null || tabs.every((tab) => tab.connectionId != null)) return tabs;
  return tabs.map((tab) => tab.connectionId != null ? tab : { ...tab, connectionId });
}

/**
 * 把一个查询标签换绑到另一条连接（顶栏连接选择器）。SQL 原样保留；Schema 清掉，等新连接的元数据回来
 * 再钉上它的默认值；上一条连接的结果、提示、分页一并清空 —— 留着会让人以为那是新连接上跑出来的。
 */
export function rebindSqlTab(tab: SqlTab, connectionId: number): SqlTab {
  if (tab.connectionId === connectionId) return tab;
  return {
    ...tab,
    connectionId,
    executionSchema: undefined,
    results: [],
    activeResultKey: undefined,
    message: '',
    statusKind: undefined,
    errorDetail: undefined
  };
}

/** 某条连接最近（最靠右）的一个查询标签；没有返回 undefined。 */
export function lastSqlTabOf(tabs: SqlTab[], connectionId: number): SqlTab | undefined {
  return [...tabs].reverse().find((tab) => tab.connectionId === connectionId);
}

/** 复制一个标签：类型、命令窗口选项、分隔方式跟着走，结果不带（副本还没执行过）。 */
export function duplicateSqlTabOf(source: SqlTab, index: number, liveSql?: string): SqlTab {
  return {
    ...(source.kind === 'command' ? createCommandTab(index) : createSqlTab(index)),
    ...(source.commandOptions ? { commandOptions: source.commandOptions } : {}),
    ...(source.delimiter ? { delimiter: source.delimiter } : {}),
    // 副本沿用来源标签的 Schema：复制一条查询通常是想在同一个地方改着跑。
    ...(source.executionSchema ? { executionSchema: source.executionSchema } : {}),
    title: `${source.title} 副本`.slice(0, 80),
    sql: liveSql ?? source.sql,
    dirty: true
  };
}

/**
 * 把还没有自己 Schema 的标签固定到给定的 Schema 上；没有要改的就原样返回同一个数组（调用方据此跳过 setState）。
 *
 * <p>标签没有 executionSchema 时会退回资源管理器当前的 Schema。这样的标签不止一种：默认的「查询 1」、
 * 升级前保存的会话、连接元数据还没加载完就新开的标签 —— 它们全都跟着资源管理器走，在资源管理器里
 * 换一次 Schema，这些标签一起变，看起来就是「所有标签指向同一个 Schema」，独立 Schema 名存实亡。
 * 元数据一加载出来就把它们钉住，之后每个标签只听自己工具栏上那个选择器的。</p>
 *
 * <p>不动 dirty：这是补一个缺省值，不是用户改了标签。</p>
 */
export function pinTabSchemas(tabs: SqlTab[], schema: string | undefined, connectionId?: number): SqlTab[] {
  // 只钉这条连接的标签：别的连接的标签拿这条连接的 Schema 名毫无意义，它们等自己的连接被选中时再钉。
  const pinnable = (tab: SqlTab) => !tab.executionSchema && (connectionId == null || tab.connectionId === connectionId);
  if (!schema || !tabs.some(pinnable)) return tabs;
  return tabs.map((tab) => pinnable(tab) ? { ...tab, executionSchema: schema } : tab);
}

/** 重命名：去掉首尾空白，空名返回 null（由调用方报错），最长 80 个字符。 */
export function normalizeSqlTabTitle(title: string): string | null {
  const normalized = title.trim().slice(0, 80);
  return normalized ? normalized : null;
}
