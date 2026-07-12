import { describe, expect, it } from 'vitest';
import { buildChanges, sameCellValue } from './utils';
import type { TableRow } from './types';

describe('table data changes', () => {
  it('preserves NULL and empty strings while omitting untouched database defaults', () => {
    const rows: TableRow[] = [{
      id: 'new-1',
      inserted: true,
      values: { generated_id: 100, nullable_note: null, empty_note: '' },
      touchedColumns: ['nullable_note', 'empty_note']
    }];

    expect(buildChanges(rows, ['generated_id'])).toEqual([{
      type: 'INSERT',
      values: { nullable_note: null, empty_note: '' }
    }]);
  });

  it('sends only changed values with optimistic original predicates', () => {
    const rows: TableRow[] = [{
      id: 'row-1',
      keyToken: 'signed-row-token',
      original: { id: 1, amount: 10, note: null },
      values: { id: 1, amount: '20', note: '' }
    }];

    expect(buildChanges(rows, ['id'])).toEqual([{
      type: 'UPDATE',
      keyToken: 'signed-row-token',
      values: { amount: '20', note: '' },
      originalValues: { amount: 10, note: null }
    }]);
  });

  it('does not confuse numeric display strings with changed numeric values', () => {
    expect(sameCellValue(10, '10')).toBe(true);
    expect(sameCellValue(null, '')).toBe(false);
  });
});
