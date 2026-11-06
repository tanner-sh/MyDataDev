/**
 * JOIN 条件的命名推断。
 *
 * <p>不少业务库根本不建外键（NC / BIP 系是典型），表之间靠同名字段关联。写 SQL 时最费劲的
 * 一步就是想起「这两张表拿哪个字段连」。这里只看两张表的列名、主键和类型，推断出可能的
 * 等值条件，交给补全放在 `JOIN … ON` 后面。</p>
 *
 * <p>两条规则：</p>
 * <ol>
 *   <li><b>同名主键</b>：一张表的单列主键（`pk_org`、`cuserid`、`order_no`……）在另一张表里出现
 *   同名列，就是另一张表引用了它。主键叫 `id` 的不算 —— 每张表都有 `id`，两边同名说明不了什么，
 *   那种情况交给第二条。</li>
 *   <li><b>`xxx_id` → `xxx.id`</b>：列名形如 `xxx_id`（或驼峰 `xxxId`），而另一张表的表名就是
 *   `xxx`（也认复数 `xxxs` / `xxxes`，以及带前缀的 `t_xxx`、`sys_xxx`），条件是那张表的主键。</li>
 * </ol>
 *
 * <p>两边类型明显不兼容（一边数字一边字符）就不推断；类型认不出时放行。推断只是候选，
 * 用户选了才写进编辑器，不会自己生效 —— 所以方向是「宁可少给」：给出一条错的条件，
 * 比不给更糟，因为它看起来像是工具确认过的。</p>
 */

import type { RelationKnowledge } from './tableRelations';
import type { TableRelationPair } from './types';

export type JoinColumn = { name: string; type?: string };

export type JoinTable = {
  /** 写进条件里的前缀：有别名用别名，否则用表名（见 sqlTableQualifier）。 */
  qualifier: string;
  /** 表名，不含 Schema。 */
  name: string;
  columns: JoinColumn[];
  /** 主键列；读不到时为空，规则一就不适用于这张表。 */
  primaryKeys: string[];
};

export type JoinReason = 'confirmed' | 'history' | 'same-name-key' | 'id-suffix';

export type JoinSuggestion = {
  /** 已经在语句里的那张表一侧。 */
  existing: { qualifier: string; column: string; table: string };
  /** 正在 JOIN 的那张表一侧。 */
  joined: { qualifier: string; column: string; table: string };
  reason: JoinReason;
  /** 越大越可信，用于排序。 */
  score: number;
  /** 给人看的一句理由。 */
  explanation: string;
};

const GENERIC_KEYS = new Set(['id', 'rowid', 'uuid', 'guid', 'code', 'name']);

/**
 * 推断「正在 JOIN 的表」与「语句里已有的各张表」之间的条件。
 *
 * existing 按在语句里出现的顺序传入；离得越近（越靠后）的表排在越前面 —— `a JOIN b JOIN c ON`
 * 时，c 最可能连的是刚 JOIN 进来的 b。
 *
 * knowledge 是人的判断与执行历史（见 tableRelations.ts）：确认过的排最前（100 分），历史里用过的
 * 按次数排（82 起，用得越多越靠前，常用的会超过命名推断），否决过的不论哪条规则推出来都去掉。
 */
export function inferJoinConditions(joined: JoinTable, existing: JoinTable[], knowledge?: RelationKnowledge): JoinSuggestion[] {
  const candidates: JoinSuggestion[] = [];
  existing.forEach((other, index) => {
    // 同一张表自连接时也照常推断：树形结构里 pk_parent → pk_xxx 本来就是这样连的。
    const proximity = (index + 1) / (existing.length + 1);
    const between = [
      ...fromKnowledge(joined, other, knowledge),
      ...inferBetween(other, joined, 'existing-owns'),
      ...inferBetween(joined, other, 'joined-owns')
    ];
    for (const candidate of between) candidates.push({ ...candidate, score: candidate.score + proximity });
  });
  const rejected = knowledge?.rejectedKeys;
  const seen = new Set<string>();
  return candidates
    .sort((left, right) => right.score - left.score)
    .filter((candidate) => {
      const key = conditionKey(candidate);
      if (seen.has(key)) return false;
      seen.add(key);
      return !rejected?.has(relationPairKey(candidate));
    });
}

/** 去重用的键：同一个别名组合下的同一对列（不同来源推出同一条条件只留分数最高的那个）。 */
function conditionKey(suggestion: JoinSuggestion): string {
  return `${suggestion.existing.qualifier}.${suggestion.existing.column.toLowerCase()}=${suggestion.joined.qualifier}.${suggestion.joined.column.toLowerCase()}`;
}

/** 与服务端、tableRelations.relationKey 同一个规范，用来对照否决记录。 */
function relationPairKey(suggestion: JoinSuggestion): string {
  const a = `${suggestion.existing.table}.${suggestion.existing.column}`.toLowerCase();
  const b = `${suggestion.joined.table}.${suggestion.joined.column}`.toLowerCase();
  return a <= b ? `${a}=${b}` : `${b}=${a}`;
}

/** 把确认过的 / 历史里挖到的关联套到这两张表上：表名对得上、两列在列清单里都存在才算。 */
function fromKnowledge(joined: JoinTable, other: JoinTable, knowledge: RelationKnowledge | undefined): JoinSuggestion[] {
  if (!knowledge) return [];
  const results: JoinSuggestion[] = [];
  const apply = (pair: TableRelationPair, reason: 'confirmed' | 'history', score: number, explanation: string) => {
    for (const [existingTable, existingColumn, joinedTable, joinedColumn] of [
      [pair.leftTable, pair.leftColumn, pair.rightTable, pair.rightColumn],
      [pair.rightTable, pair.rightColumn, pair.leftTable, pair.leftColumn]
    ]) {
      if (lower(existingTable) !== lower(other.name) || lower(joinedTable) !== lower(joined.name)) continue;
      const existingMeta = findColumn(other, existingColumn);
      const joinedMeta = findColumn(joined, joinedColumn);
      if (!existingMeta || !joinedMeta) continue;
      results.push({
        existing: { qualifier: other.qualifier, column: existingMeta.name, table: other.name },
        joined: { qualifier: joined.qualifier, column: joinedMeta.name, table: joined.name },
        reason, score, explanation
      });
    }
  };
  for (const relation of knowledge.confirmed) apply(relation, 'confirmed', 100, '已确认的关联');
  for (const relation of knowledge.mined) {
    apply(relation, 'history', 82 + Math.min(relation.hits, 12), `执行历史里用过 ${relation.hits} 次`);
  }
  return results;
}

/** 条件的 SQL 文本：已有的表在左，新 JOIN 的表在右。quote 负责按库引用标识符。 */
export function joinConditionText(suggestion: JoinSuggestion, quote: (identifier: string) => string = (value) => value): string {
  return `${suggestion.existing.qualifier}.${quote(suggestion.existing.column)} = ${suggestion.joined.qualifier}.${quote(suggestion.joined.column)}`;
}

/**
 * owner 是被引用的一方（主键所在的表），referrer 是引用它的一方。direction 说明 owner 是语句里
 * 已有的表还是正在 JOIN 的表，只用来把结果摆回 existing / joined 两侧。
 */
function inferBetween(owner: JoinTable, referrer: JoinTable, direction: 'existing-owns' | 'joined-owns'): JoinSuggestion[] {
  const results: JoinSuggestion[] = [];
  const place = (ownerColumn: string, referrerColumn: string, reason: JoinReason, score: number, explanation: string) => {
    const ownerSide = { qualifier: owner.qualifier, column: ownerColumn, table: owner.name };
    const referrerSide = { qualifier: referrer.qualifier, column: referrerColumn, table: referrer.name };
    results.push(direction === 'existing-owns'
      ? { existing: ownerSide, joined: referrerSide, reason, score, explanation }
      : { existing: referrerSide, joined: ownerSide, reason, score, explanation });
  };

  const ownerKey = owner.primaryKeys.length === 1 ? owner.primaryKeys[0] : undefined;
  const referrerKeys = new Set(referrer.primaryKeys.map(lower));

  // 规则一：同名主键。
  if (ownerKey && !GENERIC_KEYS.has(lower(ownerKey))) {
    const referrerColumn = findColumn(referrer, ownerKey);
    const ownerColumn = findColumn(owner, ownerKey);
    // 两边主键恰好都是这一列：1:1 的扩展表，可能是对的，但不如「子表引用父表」可信。
    const bothKeys = referrerKeys.size === 1 && referrerKeys.has(lower(ownerKey));
    if (referrerColumn && compatibleTypes(ownerColumn?.type, referrerColumn.type)) {
      place(ownerColumn?.name ?? ownerKey, referrerColumn.name, 'same-name-key', bothKeys ? 70 : 90,
        `${referrer.name}.${referrerColumn.name} 与 ${owner.name} 的主键同名`);
    }
  }

  // 规则二：xxx_id → xxx 表的主键。
  const ownerId = ownerKey ?? (owner.primaryKeys.length === 0 ? findColumn(owner, 'id')?.name : undefined);
  if (ownerId) {
    const ownerColumn = findColumn(owner, ownerId);
    for (const column of referrer.columns) {
      if (referrerKeys.has(lower(column.name)) && referrerKeys.size === 1) continue;
      const stem = idSuffixStem(column.name);
      if (!stem || !tableNameMatchesStem(owner.name, stem)) continue;
      // 主键本身就叫 xxx_id 时规则一已经给过了。
      if (lower(ownerId) === lower(column.name)) continue;
      if (!compatibleTypes(ownerColumn?.type, column.type)) continue;
      place(ownerColumn?.name ?? ownerId, column.name, 'id-suffix', ownerKey ? 80 : 60,
        `${referrer.name}.${column.name} 指向 ${owner.name}（按 ${stem}_id 命名推断）`);
    }
  }
  return results;
}

/** `customer_id` / `customerId` / `CUSTOMER_ID` → `customer`；不是这种形状返回 undefined。 */
export function idSuffixStem(columnName: string): string | undefined {
  const snake = /^(.+?)_id$/i.exec(columnName);
  if (snake) return snake[1].toLowerCase();
  // 驼峰只认小写开头、Id 结尾的：`userId`。全大写的 `USERID` 分不清词界，不猜。
  const camel = /^([a-z][a-zA-Z0-9]*?)Id$/.exec(columnName);
  return camel ? camel[1].replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase() : undefined;
}

/** 表名与词干是否对得上：完全相同、复数、或带一段前缀（t_customer、sys_user）。 */
export function tableNameMatchesStem(tableName: string, stem: string): boolean {
  const name = lower(tableName);
  const forms = [stem, `${stem}s`, `${stem}es`, stem.endsWith('y') ? `${stem.slice(0, -1)}ies` : undefined].filter(Boolean) as string[];
  return forms.some((form) => name === form || name.endsWith(`_${form}`));
}

type TypeFamily = 'number' | 'text' | 'unknown';

function typeFamily(type: string | undefined): TypeFamily {
  const value = lower(type ?? '');
  if (!value) return 'unknown';
  if (/(int|number|numeric|decimal|serial|float|double|real|money)/.test(value)) return 'number';
  if (/(char|text|string|clob|uuid|guid)/.test(value)) return 'text';
  return 'unknown';
}

function compatibleTypes(left: string | undefined, right: string | undefined): boolean {
  const a = typeFamily(left);
  const b = typeFamily(right);
  return a === 'unknown' || b === 'unknown' || a === b;
}

function findColumn(table: JoinTable, name: string): JoinColumn | undefined {
  const target = lower(name);
  return table.columns.find((column) => lower(column.name) === target);
}

function lower(value: string): string {
  return value.toLowerCase();
}
