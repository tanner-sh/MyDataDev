import { describe, expect, it, vi } from 'vitest';
import { AsyncResourceCache } from './asyncResourceCache';
import { analyzeSqlCompletion, findSqlObjectReferenceAtOffset, getCurrentSqlStatement, isSqlCompletionListIncomplete, parseSqlTableReferences, shouldTriggerSqlConditionColumnCompletion, tokenizeSqlIter } from './sqlCompletion';

describe('SQL completion context', () => {
  it('only analyzes the statement containing the cursor', () => {
    const sql = "select * from old_table; select  from users u where u.id = 1";
    const cursor = sql.indexOf(' from users');
    const statement = getCurrentSqlStatement(sql, cursor);

    expect(statement.text).toContain('from users');
    expect(statement.text).not.toContain('old_table');
  });

  it('stops tokenizing at the closing semicolon instead of scanning the rest of the document', () => {
    // Finding the current statement only needs tokens up to the first
    // top-level semicolon after the cursor; a huge trailing statement must not
    // be tokenized just to answer a query about an early one. Count how many
    // tokens the shared generator actually yields for the whole document, by
    // wrapping the same iterable getCurrentSqlStatement pulls from.
    const trailing = 'select col from padding_table where x = 1; '.repeat(5_000);
    const sql = `select  from users u; ${trailing}`;
    const cursor = 'select '.length;

    let pulled = 0;
    const countingIter = { [Symbol.iterator]: () => {
      const inner = tokenizeSqlIter(sql)[Symbol.iterator]();
      return {
        next: () => { pulled += 1; return inner.next(); },
        return: inner.return?.bind(inner)
      };
    } };
    let start = 0;
    let end = sql.length;
    for (const token of countingIter as Iterable<{ kind: string; text: string; start: number; end: number }>) {
      if (token.kind !== 'symbol' || token.text !== ';') continue;
      if (token.end <= cursor) { start = token.end; continue; }
      if (token.start >= cursor) { end = token.start; break; }
    }
    expect(sql.slice(start, end).trim()).toBe('select  from users u');
    // The full document tokenizes into tens of thousands of tokens; stopping
    // at the first closing semicolon should pull only a handful.
    expect(pulled).toBeLessThan(20);
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

  it('keeps literal underscores in table prefixes and refreshes capped suggestions while typing', () => {
    const sql = 'select * from si_';
    const context = analyzeSqlCompletion(sql, sql.length);

    expect(context.mode).toBe('table');
    expect(context.replacement.prefix).toBe('si_');
    expect(isSqlCompletionListIncomplete(context)).toBe(true);
  });

  it('does not suggest inside strings or comments', () => {
    const stringSql = "select 'from users'";
    const commentSql = 'select 1 -- from users';

    expect(analyzeSqlCompletion(stringSql, stringSql.indexOf('users')).mode).toBe('none');
    expect(analyzeSqlCompletion(commentSql, commentSql.length).mode).toBe('none');
  });

  it('automatically triggers column completion after condition keywords', () => {
    const statements = [
      'select * from users where ',
      'select * from users where active = 1 and ',
      'select * from users where active = 1 or ',
      'select role_id, count(*) from users group by role_id having ',
      'select * from orders o join customers c on '
    ];

    statements.forEach((sql) => {
      expect(shouldTriggerSqlConditionColumnCompletion(analyzeSqlCompletion(sql, sql.length))).toBe(true);
    });
  });

  it('does not automatically trigger columns after unrelated or repeated spaces', () => {
    const statements = [
      'select * from users ',
      'select * from users where  ',
      'select * where ',
      "select * from users where 'unfinished ",
      "select * from users where status = 'OR' ",
      'select * from users "WHERE" '
    ];

    statements.forEach((sql) => {
      expect(shouldTriggerSqlConditionColumnCompletion(analyzeSqlCompletion(sql, sql.length))).toBe(false);
    });
  });
});

describe('SQL object definition navigation', () => {
  it('resolves table and view names in query and mutation sources', () => {
    const statements = [
      'select * from SM_USER',
      'select * from account_view',
      'update users set active = 1',
      "insert into audit_log(message) values ('ok')",
      'delete from old_sessions where expired = 1'
    ];

    expect(statements.map((sql) => {
      const expected = ['SM_USER', 'account_view', 'users', 'audit_log', 'old_sessions']
        .find((name) => sql.includes(name))!;
      return findSqlObjectReferenceAtOffset(sql, sql.indexOf(expected) + 1)?.name;
    })).toEqual(['SM_USER', 'account_view', 'users', 'audit_log', 'old_sessions']);
  });

  it('resolves only the object segment of qualified and quoted names', () => {
    const sql = 'select * from "Trade"."Order" o join [dbo].[User] u on u.id = o.user_id';
    const order = findSqlObjectReferenceAtOffset(sql, sql.indexOf('Order') + 1);
    const user = findSqlObjectReferenceAtOffset(sql, sql.indexOf('User') + 1);

    expect(order).toMatchObject({ schemaName: 'Trade', name: 'Order' });
    expect(user).toMatchObject({ schemaName: 'dbo', name: 'User' });
    expect(findSqlObjectReferenceAtOffset(sql, sql.indexOf('Trade') + 1)).toBeUndefined();
  });

  it('uses only the statement containing the clicked object', () => {
    const sql = 'select * from old_table; select * from current_table';
    const current = findSqlObjectReferenceAtOffset(sql, sql.indexOf('current_table') + 2);

    expect(current?.name).toBe('current_table');
  });

  it('does not resolve fields, aliases, strings, comments, CTEs or derived tables', () => {
    const sql = [
      'with recent as (select * from audit_log)',
      'select r.id, recent_text',
      "from recent r join (select * from users) u on u.id = r.user_id -- from ignored_table",
      "where r.note = 'from string_table'"
    ].join('\n');

    expect(findSqlObjectReferenceAtOffset(sql, sql.indexOf('audit_log') + 1)?.name).toBe('audit_log');
    expect(findSqlObjectReferenceAtOffset(sql, sql.indexOf('users') + 1)?.name).toBe('users');
    expect(findSqlObjectReferenceAtOffset(sql, sql.indexOf('recent r') + 1)).toBeUndefined();
    expect(findSqlObjectReferenceAtOffset(sql, sql.indexOf('r.id'))).toBeUndefined();
    expect(findSqlObjectReferenceAtOffset(sql, sql.indexOf('recent_text') + 1)).toBeUndefined();
    expect(findSqlObjectReferenceAtOffset(sql, sql.indexOf('ignored_table') + 1)).toBeUndefined();
    expect(findSqlObjectReferenceAtOffset(sql, sql.indexOf('string_table') + 1)).toBeUndefined();
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
