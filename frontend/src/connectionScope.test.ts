import { describe, expect, it } from 'vitest';
import { connectionOptionLabel, scopeSuggestions, tableNameCandidates } from './connectionScope';

describe('connection scope fields', () => {
  it('labels connections with their type and flags production', () => {
    expect(connectionOptionLabel({ name: '订单库', dbType: 'mysql', environment: 'dev' })).toBe('订单库（MySQL）');
    expect(connectionOptionLabel({ name: '订单库', dbType: 'mysql', environment: 'prod' })).toBe('订单库（MySQL · 生产）');
  });

  it('filters suggestions case-insensitively, prefix matches first', () => {
    expect(scopeSuggestions(['T_RECORD', 'ORDERS', 'customer'], 'ord')).toEqual(['ORDERS', 'T_RECORD']);
    expect(scopeSuggestions(['A', 'B', 'A'], '')).toEqual(['A', 'B']);
    expect(scopeSuggestions(['A1', 'A2', 'A3'], 'a', 2)).toEqual(['A1', 'A2']);
  });

  it('keeps only tables and views as table candidates', () => {
    expect(tableNameCandidates([
      { name: 'ORDERS', type: 'TABLE' },
      { name: 'V_ORDERS', type: 'VIEW' },
      { name: 'SEQ_ORDER', type: 'SEQUENCE' }
    ])).toEqual(['ORDERS', 'V_ORDERS']);
  });
});
