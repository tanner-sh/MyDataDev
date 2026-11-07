import type { SqlTab } from './types';
import { createCommandTab, createSqlTab } from './utils';

/**
 * SQL 标签页的纯逻辑：标签增删改时「数组该变成什么样」。状态与弹窗在 hooks/useSqlTabs.tsx。
 *
 * <p>这些规则原来散在 App.tsx 的七个函数里，而且「把编辑器里的现值写回标签」这一步写了四遍 ——
 * 编辑器的改动要 300ms 才同步回标签，关闭、复制、新开命令窗口、离开页面这几处都得先补上它，
 * 否则最后几个字会丢。</p>
 */

/** 同时打开的 SQL 标签上限：每个标签都带着结果，开太多会把浏览器内存吃满。 */
export const MAX_SQL_TABS = 20;

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
  return { tabs: nextTabs, activeId: (nextTabs[Math.max(0, targetIndex - 1)] ?? nextTabs[0]).id };
}

/** 复制一个标签：类型、命令窗口选项、分隔方式跟着走，结果不带（副本还没执行过）。 */
export function duplicateSqlTabOf(source: SqlTab, index: number, liveSql?: string): SqlTab {
  return {
    ...(source.kind === 'command' ? createCommandTab(index) : createSqlTab(index)),
    ...(source.commandOptions ? { commandOptions: source.commandOptions } : {}),
    ...(source.delimiter ? { delimiter: source.delimiter } : {}),
    title: `${source.title} 副本`.slice(0, 80),
    sql: liveSql ?? source.sql,
    dirty: true
  };
}

/** 重命名：去掉首尾空白，空名返回 null（由调用方报错），最长 80 个字符。 */
export function normalizeSqlTabTitle(title: string): string | null {
  const normalized = title.trim().slice(0, 80);
  return normalized ? normalized : null;
}
