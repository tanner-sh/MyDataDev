import { useCallback, useEffect, useState } from 'react';

export type ThemeMode = 'light' | 'dark';

export interface LayoutPreferences {
  themeMode: ThemeMode;
  explorerWidth: number;
  explorerCollapsed: boolean;
  editorSplitRatio: number;
  sqlPageSize: number;
  tablePageSize: number;
}

type PreferenceUpdate<T> = T | ((current: T) => T);

export interface LayoutPreferencesController extends LayoutPreferences {
  setThemeMode: (value: PreferenceUpdate<ThemeMode>) => void;
  setExplorerWidth: (value: PreferenceUpdate<number>) => void;
  setExplorerCollapsed: (value: PreferenceUpdate<boolean>) => void;
  toggleExplorer: () => void;
  setEditorSplitRatio: (value: PreferenceUpdate<number>) => void;
  setSqlPageSize: (value: PreferenceUpdate<number>) => void;
  setTablePageSize: (value: PreferenceUpdate<number>) => void;
}

export const EXPLORER_WIDTH_MIN = 240;
export const EXPLORER_WIDTH_MAX = 480;
export const EDITOR_SPLIT_RATIO_MIN = 0.2;
export const EDITOR_SPLIT_RATIO_MAX = 0.8;

export const DEFAULT_LAYOUT_PREFERENCES: Readonly<LayoutPreferences> = {
  themeMode: 'light',
  explorerWidth: 300,
  explorerCollapsed: false,
  editorSplitRatio: 0.52,
  sqlPageSize: 500,
  tablePageSize: 100
};

const STORAGE_KEY = 'db-admin:layout-preferences:v1';

export function useLayoutPreferences(): LayoutPreferencesController {
  const [preferences, setPreferences] = useState<LayoutPreferences>(readStoredPreferences);

  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }

    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences));
    } catch {
      // Storage can be unavailable in private browsing or a restricted iframe.
    }
  }, [preferences]);

  const setThemeMode = useCallback((value: PreferenceUpdate<ThemeMode>) => {
    setPreferences((current) => ({
      ...current,
      themeMode: resolveUpdate(value, current.themeMode)
    }));
  }, []);

  const setExplorerWidth = useCallback((value: PreferenceUpdate<number>) => {
    setPreferences((current) => ({
      ...current,
      explorerWidth: clamp(
        resolveUpdate(value, current.explorerWidth),
        EXPLORER_WIDTH_MIN,
        EXPLORER_WIDTH_MAX
      )
    }));
  }, []);

  const setExplorerCollapsed = useCallback((value: PreferenceUpdate<boolean>) => {
    setPreferences((current) => ({
      ...current,
      explorerCollapsed: resolveUpdate(value, current.explorerCollapsed)
    }));
  }, []);

  const toggleExplorer = useCallback(() => {
    setPreferences((current) => ({
      ...current,
      explorerCollapsed: !current.explorerCollapsed
    }));
  }, []);

  const setEditorSplitRatio = useCallback((value: PreferenceUpdate<number>) => {
    setPreferences((current) => ({
      ...current,
      editorSplitRatio: clamp(
        resolveUpdate(value, current.editorSplitRatio),
        EDITOR_SPLIT_RATIO_MIN,
        EDITOR_SPLIT_RATIO_MAX
      )
    }));
  }, []);

  const setSqlPageSize = useCallback((value: PreferenceUpdate<number>) => {
    setPreferences((current) => ({
      ...current,
      sqlPageSize: normalizeSqlPageSize(resolveUpdate(value, current.sqlPageSize))
    }));
  }, []);

  const setTablePageSize = useCallback((value: PreferenceUpdate<number>) => {
    setPreferences((current) => ({
      ...current,
      tablePageSize: normalizePageSize(resolveUpdate(value, current.tablePageSize))
    }));
  }, []);

  return {
    ...preferences,
    setThemeMode,
    setExplorerWidth,
    setExplorerCollapsed,
    toggleExplorer,
    setEditorSplitRatio,
    setSqlPageSize,
    setTablePageSize
  };
}

function readStoredPreferences(): LayoutPreferences {
  if (typeof window === 'undefined') {
    return { ...DEFAULT_LAYOUT_PREFERENCES };
  }

  try {
    const serialized = window.localStorage.getItem(STORAGE_KEY);
    if (!serialized) {
      return { ...DEFAULT_LAYOUT_PREFERENCES };
    }

    return normalizeLayoutPreferences(JSON.parse(serialized) as unknown);
  } catch {
    return { ...DEFAULT_LAYOUT_PREFERENCES };
  }
}

export function normalizeLayoutPreferences(value: unknown): LayoutPreferences {
  if (!isRecord(value)) {
    return { ...DEFAULT_LAYOUT_PREFERENCES };
  }

  const explorerWidth = finiteNumber(value.explorerWidth);
  const editorSplitRatio = finiteNumber(value.editorSplitRatio);
  const sqlPageSize = finiteNumber(value.sqlPageSize) ?? finiteNumber(value.sqlMaxRows);
  const tablePageSize = finiteNumber(value.tablePageSize);

  return {
    themeMode: value.themeMode === 'dark' ? 'dark' : 'light',
    explorerWidth: clamp(
      explorerWidth ?? DEFAULT_LAYOUT_PREFERENCES.explorerWidth,
      EXPLORER_WIDTH_MIN,
      EXPLORER_WIDTH_MAX
    ),
    explorerCollapsed: typeof value.explorerCollapsed === 'boolean'
      ? value.explorerCollapsed
      : DEFAULT_LAYOUT_PREFERENCES.explorerCollapsed,
    editorSplitRatio: clamp(
      editorSplitRatio ?? DEFAULT_LAYOUT_PREFERENCES.editorSplitRatio,
      EDITOR_SPLIT_RATIO_MIN,
      EDITOR_SPLIT_RATIO_MAX
    ),
    sqlPageSize: normalizeSqlPageSize(sqlPageSize ?? DEFAULT_LAYOUT_PREFERENCES.sqlPageSize),
    tablePageSize: normalizePageSize(tablePageSize ?? DEFAULT_LAYOUT_PREFERENCES.tablePageSize)
  };
}

function normalizePageSize(value: number) {
  const supported = [50, 100, 200];
  return supported.includes(value) ? value : DEFAULT_LAYOUT_PREFERENCES.tablePageSize;
}

function normalizeSqlPageSize(value: number) {
  return Math.round(clamp(value, 1, 2_147_483_647));
}

function resolveUpdate<T>(update: PreferenceUpdate<T>, current: T): T {
  return typeof update === 'function'
    ? (update as (value: T) => T)(current)
    : update;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) {
    return min;
  }
  return Math.min(max, Math.max(min, value));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
