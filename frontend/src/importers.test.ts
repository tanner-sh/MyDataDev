import { describe, expect, it } from 'vitest';
import { parseImportFile } from './importers';

describe('parseImportFile', () => {
  it('preserves SQL BIGINT and DECIMAL literals as exact strings', () => {
    const result = parseImportFile(
      'INSERT INTO users (id, amount) VALUES (9007199254740993, 1234567890.123456789);',
      'users.sql',
      'users',
      ['id', 'amount']
    );

    expect(result.rows).toEqual([{ id: '9007199254740993', amount: '1234567890.123456789' }]);
  });

  it('accepts the generic query_result table produced by SQL export', () => {
    const result = parseImportFile(
      'INSERT INTO query_result ("id", "name") VALUES (1, \'Alice\');',
      'query-result.sql',
      'users',
      ['id', 'name']
    );

    expect(result.rows).toEqual([{ id: '1', name: 'Alice' }]);
  });

  it('accepts the positional JSON shape produced by query export', () => {
    const result = parseImportFile(
      JSON.stringify({ columns: ['id', 'name'], rows: [['9007199254740993', 'Alice']] }),
      'users.json',
      'users',
      ['id', 'name']
    );

    expect(result.rows).toEqual([{ id: '9007199254740993', name: 'Alice' }]);
  });

  it('keeps case-sensitive column names distinct', () => {
    const result = parseImportFile(
      JSON.stringify([{ Foo: 'upper', foo: 'lower' }]),
      'case.json',
      'case_table',
      ['Foo', 'foo']
    );

    expect(result.rows).toEqual([{ Foo: 'upper', foo: 'lower' }]);
  });

  it('rejects malformed CSV instead of silently importing partial data', () => {
    expect(() => parseImportFile('id,name\n1,"Alice', 'users.csv', 'users', ['id', 'name']))
      .toThrow('未闭合的引号');
    expect(() => parseImportFile('id,name\n1,Alice,extra', 'users.csv', 'users', ['id', 'name']))
      .toThrow('超出表头');
  });

  it('rejects oversized imports before mapping every row', () => {
    const rows = Array.from({ length: 1001 }, (_, id) => ({ id }));

    expect(() => parseImportFile(JSON.stringify(rows), 'users.json', 'users', ['id']))
      .toThrow('单次最多导入 1000 行');
  });
});
