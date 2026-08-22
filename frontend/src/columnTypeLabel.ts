/**
 * 把 JDBC 返回的类型全称缩写成常见写法。
 *
 * `DatabaseMetaData` 给的是 SQL 标准全称（CHARACTER VARYING、DOUBLE PRECISION、
 * TIMESTAMP WITHOUT TIME ZONE…），在对象详情 150px 宽的「类型」列里必然折行，把那一行
 * 撑成两倍高，整张表的行高参差不齐。缩写只用于显示，完整原文仍放在 title 里。
 */

const COMPACT_TYPES: ReadonlyArray<readonly [RegExp, string]> = [
  [/^CHARACTER\s+VARYING$/, 'VARCHAR'],
  [/^CHARACTER$/, 'CHAR'],
  [/^CHARACTER\s+LARGE\s+OBJECT$/, 'CLOB'],
  [/^BINARY\s+LARGE\s+OBJECT$/, 'BLOB'],
  [/^BINARY\s+VARYING$/, 'VARBINARY'],
  [/^DOUBLE\s+PRECISION$/, 'DOUBLE'],
  [/^TIMESTAMP\s+WITH(OUT)?\s+TIME\s+ZONE$/, 'TIMESTAMP'],
  [/^TIME\s+WITH(OUT)?\s+TIME\s+ZONE$/, 'TIME']
];

export function compactColumnType(type: string | null | undefined): string {
  if (!type) return '';
  const normalized = type.trim();
  const upper = normalized.toUpperCase();
  for (const [pattern, compact] of COMPACT_TYPES) {
    if (pattern.test(upper)) return compact;
  }
  return normalized;
}
