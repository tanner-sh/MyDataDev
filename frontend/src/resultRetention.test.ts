import { describe, expect, it, vi } from 'vitest';
import { createResultUnitCounter, enforceResultBudget } from './resultRetention';
import type { SqlStatementResult, SqlTab } from './types';

function result(rows: unknown[][]): SqlStatementResult[] {
  return [{
    index: 1,
    sql: 'select * from users',
    startOffset: 0,
    endOffset: 19,
    status: 'SUCCESS',
    result: {
      columns: [{ key: 'VALUE', label: 'VALUE', typeName: 'VARCHAR' }],
      rows,
      affectedRows: 0,
      elapsedMs: 1,
      resultSet: true
    }
  }];
}

function tab(id: string, results: SqlStatementResult[]): SqlTab {
  return { id, title: id, sql: 'select 1', dirty: false, results, message: '' };
}

describe('SQL result retention', () => {
  it('calculates an unchanged result array only once across SQL-only tab updates', () => {
    const calculate = vi.fn(() => 12);
    const units = createResultUnitCounter(calculate);
    const results = result([['same result']]);

    expect(units(results)).toBe(12);
    expect(units(results)).toBe(12);
    expect(calculate).toHaveBeenCalledTimes(1);
  });

  it('releases older inactive results while protecting the active tab', () => {
    const oldest = tab('oldest', result([['a'.repeat(500)]]));
    const newest = tab('newest', result([['b'.repeat(500)]]));

    const retained = enforceResultBudget([oldest, newest], 'newest', 6);

    expect(retained[0].results).toEqual([]);
    expect(retained[0].message).toContain('已释放');
    expect(retained[1]).toBe(newest);
  });
});
