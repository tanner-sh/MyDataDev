/**
 * 编辑器里的「这个表不存在」提示。
 *
 * <p>补全已经拿着当前 Schema 的对象清单了（补全就是靠它），但写错表名要到点执行才知道 ——
 * 同一份数据顺手就能把这件事提前到输入时。纯前端，不需要模型，也不多打一次接口。</p>
 *
 * <p><b>宁可不提示，也不能误报。</b>一条红线画在正确的表名下面，用户会开始怀疑整个提示，
 * 之后真的写错时也不会再看。所以下面每一条规则都往「不提示」的方向让步：</p>
 *
 * <ul>
 *   <li>对象清单没取全（资源树是分页的）时整个功能关掉 —— 第 2 页上的表不该被说成不存在；</li>
 *   <li>WITH 定义的名字、子查询别名一律跳过，它们在 FROM 后面和真表长得一样；</li>
 *   <li>表值函数（<code>FROM unnest(x)</code>）跳过：那是函数不是表；</li>
 *   <li>带库名/模式名限定且不是当前 Schema 的跳过 —— 我们手上没有那个 Schema 的清单；</li>
 *   <li>临时表前缀（<code>#</code>、<code>@</code>）跳过；</li>
 *   <li>比对一律折大小写：各家数据库的大小写规则不同，按原样比会误报一片。</li>
 * </ul>
 */
import { parseSqlTableReferences, sqlCteNames, tokenizeSql, type SqlToken } from './sqlCompletion';

export type KnownSchemaObjects = {
  /** 当前 Schema 的对象清单是否完整。分页没取完时一律不提示。 */
  complete: boolean;
  /** 清单所属的 Schema；限定名与它不同的引用不参与判断。 */
  schemaName: string;
  names: string[];
};

export type UnknownObjectMark = { start: number; end: number; name: string };

export function findUnknownObjects(sql: string, known: KnownSchemaObjects): UnknownObjectMark[] {
  if (!known.complete || known.names.length === 0 || !sql.trim()) return [];
  const knownNames = new Set(known.names.map(fold));
  const schema = fold(known.schemaName);
  const tokens = tokenizeSql(sql).filter((token) => token.kind !== 'whitespace' && token.kind !== 'comment');
  const cteNames = sqlCteNames(tokens);
  const marks: UnknownObjectMark[] = [];

  for (const reference of parseSqlTableReferences(tokens)) {
    const part = reference.parts[reference.parts.length - 1];
    if (!part) continue;
    if (cteNames.has(reference.normalizedName) || cteNames.has(fold(part.value))) continue;
    if (isTemporary(part.value)) continue;
    // 限定到别的 Schema 时手上没有它的清单，无从判断。
    if (reference.schemaName && fold(reference.schemaName) !== schema) continue;
    if (isFunctionCall(tokens, part.end)) continue;
    if (knownNames.has(fold(part.value))) continue;
    marks.push({ start: part.start, end: part.end, name: part.value });
  }
  return marks;
}

/** 紧跟一个左括号说明这是表值函数，不是表。 */
function isFunctionCall(tokens: SqlToken[], end: number): boolean {
  return tokens.some((token) => token.start === end && token.text === '(');
}

function isTemporary(name: string): boolean {
  return name.startsWith('#') || name.startsWith('@') || name.startsWith(':');
}

function fold(value: string): string {
  return value.trim().toLowerCase();
}
