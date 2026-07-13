import { describe, expect, it } from 'vitest';
import { DEFAULT_LAYOUT_PREFERENCES, normalizeLayoutPreferences } from './useLayoutPreferences';

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
});
