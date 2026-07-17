import { describe, expect, it } from 'vitest';
import { getSqlFormatTarget } from './sqlFormatTarget';

describe('SQL format target', () => {
  it('uses a non-blank manual selection first and trims only its edges', () => {
    const sql = 'select 1;  select * from users ; select 3';
    const start = sql.indexOf('  select *');
    const end = sql.indexOf(' ; select 3');

    expect(getSqlFormatTarget(sql, 0, { start, end })).toEqual({
      sql: 'select * from users',
      start: start + 2,
      end,
      selected: true
    });
  });

  it('formats only the statement containing the cursor', () => {
    const sql = 'select 1;  select * from users ; select 3';
    const cursor = sql.indexOf('users');
    const target = getSqlFormatTarget(sql, cursor);

    expect(target?.sql).toBe('select * from users');
    expect(sql.slice(0, target?.start)).toBe('select 1;  ');
    expect(sql.slice(target?.end || 0)).toBe(' ; select 3');
  });

  it('ignores semicolons inside strings and comments', () => {
    const sql = "select ';' as marker /* ; */ from users; select 2";
    const target = getSqlFormatTarget(sql, sql.indexOf('users'));

    expect(target?.sql).toBe("select ';' as marker /* ; */ from users");
  });

  it('returns no target for an empty current statement', () => {
    expect(getSqlFormatTarget('select 1;   ', 'select 1;   '.length)).toBeUndefined();
  });
});
