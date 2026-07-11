import { describe, expect, it, vi } from 'vitest';
import { AsyncResourceCache } from './asyncResourceCache';
import { analyzeSqlCompletion, getCurrentSqlStatement, parseSqlTableReferences } from './sqlCompletion';

describe('SQL completion context', () => {
  it('only analyzes the statement containing the cursor', () => {
    const sql = "select * from old_table; select  from users u where u.id = 1";
    const cursor = sql.indexOf(' from users');
    const statement = getCurrentSqlStatement(sql, cursor);

    expect(statement.text).toContain('from users');
    expect(statement.text).not.toContain('old_table');
  });

  it('suggests bare columns when one table is fixed after the cursor', () => {
    const sql = 'select  from users u';
    const context = analyzeSqlCompletion(sql, 'select '.length);

    expect(context.mode).toBe('column');
    expect(context.suggestBareColumns).toBe(true);
    expect(context.tables).toMatchObject([{ name: 'users', alias: 'u' }]);
  });

  it('resolves alias-dot completion', () => {
    const sql = 'select u. from `app`.`users` as u';
    const context = analyzeSqlCompletion(sql, sql.indexOf('u.') + 2);

    expect(context.mode).toBe('qualified-column');
    expect(context.qualifierParts).toEqual(['u']);
    expect(context.tables[0]).toMatchObject({ schemaName: 'app', name: 'users', alias: 'u' });
  });

  it('qualifies columns for a multi-table query', () => {
    const sql = 'select  from orders o join customers c on c.id = o.customer_id';
    const context = analyzeSqlCompletion(sql, 'select '.length);

    expect(context.mode).toBe('column');
    expect(context.qualifyColumns).toBe(true);
    expect(context.tables.map((table) => table.alias)).toEqual(['o', 'c']);
  });

  it('recognizes table positions and quoted identifiers', () => {
    const tableContext = analyzeSqlCompletion('select * from ', 'select * from '.length);
    const references = parseSqlTableReferences('select * from "Trade"."Order" o');

    expect(tableContext.mode).toBe('table');
    expect(references[0]).toMatchObject({ schemaName: 'Trade', name: 'Order', alias: 'o' });
  });

  it('does not suggest inside strings or comments', () => {
    const stringSql = "select 'from users'";
    const commentSql = 'select 1 -- from users';

    expect(analyzeSqlCompletion(stringSql, stringSql.indexOf('users')).mode).toBe('none');
    expect(analyzeSqlCompletion(commentSql, commentSql.length).mode).toBe('none');
  });
});

describe('AsyncResourceCache', () => {
  it('coalesces concurrent metadata requests', async () => {
    const cache = new AsyncResourceCache<string, string>();
    const loader = vi.fn(async () => 'columns');

    const [first, second] = await Promise.all([cache.load('users', loader), cache.load('users', loader)]);

    expect(first).toBe('columns');
    expect(second).toBe('columns');
    expect(loader).toHaveBeenCalledTimes(1);
  });

  it('does not repopulate after a refresh clears an in-flight request', async () => {
    const cache = new AsyncResourceCache<string, string>();
    let resolve!: (value: string) => void;
    const pending = cache.load('users', () => new Promise<string>((done) => { resolve = done; }));
    await Promise.resolve();
    cache.clear();
    resolve('stale');
    await pending;

    expect(cache.get('users')).toBeUndefined();
  });
});
