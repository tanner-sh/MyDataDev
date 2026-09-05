import { describe, expect, it } from 'vitest';
import { findUnknownObjects, type KnownSchemaObjects } from './sqlUnknownObjects';

const known: KnownSchemaObjects = { complete: true, schemaName: 'shop', names: ['orders', 'app_user'] };

function marks(sql: string, overrides: Partial<KnownSchemaObjects> = {}): string[] {
  return findUnknownObjects(sql, { ...known, ...overrides }).map((mark) => mark.name);
}

describe('findUnknownObjects', () => {
  it('只标出清单里没有的表', () => {
    expect(marks('SELECT * FROM orders')).toEqual([]);
    expect(marks('SELECT * FROM order')).toEqual(['order']);
    expect(marks('SELECT * FROM orders o JOIN app_users u ON u.id = o.user_id')).toEqual(['app_users']);
  });

  /** 各家数据库的大小写规则不同，按原样比会误报一片。 */
  it('比对折大小写', () => {
    expect(marks('SELECT * FROM ORDERS')).toEqual([]);
    expect(marks('select * from "Orders"')).toEqual([]);
  });

  /** 资源树是分页的：第 2 页上的表不该被说成不存在。 */
  it('清单没取全时整个功能关掉', () => {
    expect(marks('SELECT * FROM whatever', { complete: false })).toEqual([]);
    expect(marks('SELECT * FROM whatever', { names: [] })).toEqual([]);
  });

  /** CTE 在 FROM 后面和真表长得一模一样，不排除的话每条 WITH 查询都会挂一串红线。 */
  it('跳过 WITH 定义的名字', () => {
    const sql = 'WITH recent AS (SELECT * FROM orders) SELECT * FROM recent';

    expect(marks(sql)).toEqual([]);
  });

  it('跳过表值函数与临时表', () => {
    expect(marks('SELECT * FROM unnest(ARRAY[1,2]) AS t')).toEqual([]);
    expect(marks('SELECT * FROM #tmp_orders')).toEqual([]);
  });

  /** 手上只有当前 Schema 的清单，别的 Schema 无从判断。 */
  it('限定到别的 Schema 时不判断', () => {
    expect(marks('SELECT * FROM other.mystery')).toEqual([]);
    expect(marks('SELECT * FROM shop.mystery')).toEqual(['mystery']);
  });

  it('注释与字符串里的表名不算引用', () => {
    expect(marks("SELECT * FROM orders -- FROM ghost_table\nWHERE note = 'FROM ghost_table'")).toEqual([]);
  });

  it('UPDATE 与 INSERT INTO 的目标同样检查', () => {
    expect(marks('UPDATE ghost SET a = 1')).toEqual(['ghost']);
    expect(marks('INSERT INTO ghost (a) VALUES (1)')).toEqual(['ghost']);
  });

  it('给出的是对象名那一段的位置', () => {
    const sql = 'SELECT * FROM shop.ghost';
    const [mark] = findUnknownObjects(sql, known);

    expect(sql.slice(mark.start, mark.end)).toBe('ghost');
  });
});
