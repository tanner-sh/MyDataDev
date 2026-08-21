import { describe, expect, it } from 'vitest';
import indexHtml from '../../index.html?raw';
import { DEFAULT_LAYOUT_PREFERENCES, MAX_SQL_PAGE_SIZE, normalizeLayoutPreferences, STORAGE_KEY } from './useLayoutPreferences';

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

describe('theme bootstrap in index.html', () => {
  // index.html applies the stored theme before the app bundle parses, so a
  // renamed storage key here would silently bring the white flash back.
  it('reads the same storage entry the hook writes', () => {
    expect(indexHtml).toContain(STORAGE_KEY);
  });

  it('sets both the theme attribute and the browser color scheme', () => {
    expect(indexHtml).toContain('documentElement.dataset.theme');
    expect(indexHtml).toContain('documentElement.style.colorScheme');
  });

  it('gives the boot placeholder a dark variant, so it cannot flash white either', () => {
    expect(indexHtml).toContain('id="app-boot"');
    expect(indexHtml).toContain(':root[data-theme="dark"] #app-boot');
  });
});
