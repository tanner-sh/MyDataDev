export type AppShortcut =
  | { kind: 'toggle-explorer' }
  | { kind: 'new-sql-tab' }
  | { kind: 'select-sql-tab'; index: number }
  | { kind: 'commit-table-changes' };

export type ShortcutKeyEvent = {
  key: string;
  code: string;
  ctrlKey: boolean;
  metaKey: boolean;
  shiftKey: boolean;
  altKey: boolean;
};

/**
 * Maps a keydown to one of the workbench-wide actions.
 *
 * <p>Ctrl/Cmd combinations are matched on `key` so they follow the user's
 * keyboard layout, while Alt combinations are matched on `code`: macOS rewrites
 * `key` under Option (Alt+N produces "˜", Alt+1 produces "¡").</p>
 *
 * <p>Chrome and Firefox reserve Ctrl+T, Ctrl+W and Ctrl+Tab, so tab management
 * deliberately sits on Alt instead of mirroring browser tab shortcuts.</p>
 */
export function resolveAppShortcut(event: ShortcutKeyEvent): AppShortcut | undefined {
  const commandKey = event.ctrlKey || event.metaKey;

  if (commandKey && !event.altKey && !event.shiftKey) {
    if (event.key.toLowerCase() === 'b') return { kind: 'toggle-explorer' };
    if (event.key.toLowerCase() === 's') return { kind: 'commit-table-changes' };
    return undefined;
  }

  if (event.altKey && !commandKey && !event.shiftKey) {
    if (event.code === 'KeyN') return { kind: 'new-sql-tab' };
    const digit = shortcutDigit(event.code);
    if (digit !== undefined) return { kind: 'select-sql-tab', index: digit - 1 };
  }

  return undefined;
}

export const SHORTCUT_HINTS = {
  toggleExplorer: 'Ctrl/Cmd+B',
  newSqlTab: 'Alt+N',
  selectSqlTab: 'Alt+1~9',
  commitTableChanges: 'Ctrl/Cmd+S'
} as const;

function shortcutDigit(code: string): number | undefined {
  const match = /^(?:Digit|Numpad)([1-9])$/.exec(code);
  return match ? Number(match[1]) : undefined;
}
