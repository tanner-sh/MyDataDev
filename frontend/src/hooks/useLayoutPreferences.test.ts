import { describe, expect, it } from 'vitest';
import { DEFAULT_LAYOUT_PREFERENCES, MAX_SQL_PAGE_SIZE, normalizeLayoutPreferences } from './useLayoutPreferences';

describe('layout preference migration', () => {
  it('uses 500 rows for new SQL result pages', () => {
    expect(normalizeLayoutPreferences({}).sqlPageSize).toBe(500);
  });

  it('migrates the legacy sqlMaxRows preference', () => {
    expect(normalizeLayoutPreferences({ sqlMaxRows: 1200 }).sqlPageSize).toBe(1200);
  });

  it('prefers the new sqlPageSize value and keeps it positive', () => {
    expect(normalizeLayoutPreferences({ sqlMaxRows: 1200, sqlPageSize: 800 }).sqlPageSize).toBe(800);
    expect(normalizeLayoutPreferences({ sqlPageSize: 0 }).sqlPageSize).toBe(1);
    expect(DEFAULT_LAYOUT_PREFERENCES.sqlPageSize).toBe(500);
  });

  it('caps a mistyped page size instead of persisting it forever', () => {
    // The value is reused for every later execution, so an accidental 500000
    // would keep freezing the result grid on each query.
    expect(normalizeLayoutPreferences({ sqlPageSize: 500_000 }).sqlPageSize).toBe(MAX_SQL_PAGE_SIZE);
  });
});
