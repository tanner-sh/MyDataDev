import type { Connection } from './types';
import { dbTypeLabel } from './utils';

/**
 * 管理面板里「连接 + Schema + 表」这组字段的纯逻辑。
 *
 * <p>数据检索、数据传输、对象导出、数据对比、结构对比、结构快照六个面板原来各写一份：连接下拉的
 * 文案有的带环境有的不带，Schema 和表名是只能手打的文本框 —— 打错一个字母要等请求回来才知道。
 * 现在一处定义：连接文案统一，Schema 与表名给出候选，但仍允许直接输入（候选读不到时不该把人卡住）。</p>
 */

/** 连接下拉的文案：名称 + 类型，生产连接额外标出 —— 选错一条生产连接的代价最大。 */
export function connectionOptionLabel(connection: Pick<Connection, 'name' | 'dbType' | 'environment'>): string {
  return `${connection.name}（${dbTypeLabel(connection.dbType)}${connection.environment === 'prod' ? ' · 生产' : ''}）`;
}

export function connectionOptions(connections: Connection[]): Array<{ value: number; label: string }> {
  return connections.map((connection) => ({ value: connection.id, label: connectionOptionLabel(connection) }));
}

/** 候选最多给这么多条：Schema 上千的库（Oracle 的用户）一次全铺开没法挑，靠输入收窄。 */
export const SCOPE_SUGGESTION_LIMIT = 50;

/**
 * 按输入过滤候选，忽略大小写；输入为空时给全部（截到上限）。
 * 以输入开头的排在前面 —— 输入 `ord` 时 `ORDERS` 比 `T_RECORD` 更可能是要找的那个。
 */
export function scopeSuggestions(candidates: string[], keyword: string, limit = SCOPE_SUGGESTION_LIMIT): string[] {
  const needle = keyword.trim().toLowerCase();
  const unique = [...new Set(candidates)];
  if (!needle) return unique.slice(0, limit);
  const prefixed: string[] = [];
  const contained: string[] = [];
  for (const candidate of unique) {
    const lower = candidate.toLowerCase();
    if (lower.startsWith(needle)) prefixed.push(candidate);
    else if (lower.includes(needle)) contained.push(candidate);
  }
  return [...prefixed, ...contained].slice(0, limit);
}

/** 只取表与视图的名字：数据对比、数据传输的源与目标都只能是这两类。 */
export function tableNameCandidates(objects: Array<{ name: string; type: string }>): string[] {
  return objects
    .filter((object) => /TABLE|VIEW/i.test(object.type))
    .map((object) => object.name);
}
