export type AppShortcut =
  | { kind: 'toggle-explorer' }
  | { kind: 'new-sql-tab' }
  | { kind: 'select-sql-tab'; index: number }
  | { kind: 'commit-table-changes' }
  | { kind: 'open-object-search' }
  | { kind: 'open-command-palette' };

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
    // Ctrl/Cmd+P 是浏览器打印，处理方会 preventDefault —— VS Code Web 等同类工具是同样的取舍。
    if (event.key.toLowerCase() === 'p') return { kind: 'open-object-search' };
    // Ctrl/Cmd+P 搜库里的对象，Ctrl/Cmd+K 搜应用能做的事。两者分开，因为它们的结果点开
    // 之后去的是完全不同的地方。
    if (event.key.toLowerCase() === 'k') return { kind: 'open-command-palette' };
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
  executeSql: 'Ctrl/Cmd+Enter',
  formatSql: 'Ctrl/Cmd+Shift+F',
  toggleExplorer: 'Ctrl/Cmd+B',
  newSqlTab: 'Alt+N',
  selectSqlTab: 'Alt+1~9',
  commitTableChanges: 'Ctrl/Cmd+S',
  openObjectSearch: 'Ctrl/Cmd+P',
  openCommandPalette: 'Ctrl/Cmd+K'
} as const;

/**
 * 按钮上直接显示的快捷键：提示里写「Ctrl/Cmd+Enter」是为了两边的人都看得懂，但按钮上
 * 地方小，只写本机那一种 —— macOS 上用 ⌘ ⇧ ⌥ 符号（和系统菜单一致），其余平台写 Ctrl / Shift / Alt。
 */
export function shortcutKeycap(hint: string, mac: boolean): string {
  const keys = hint.split('+').map((key) => {
    if (key === 'Ctrl/Cmd') return mac ? '⌘' : 'Ctrl';
    if (key === 'Shift') return mac ? '⇧' : 'Shift';
    if (key === 'Alt') return mac ? '⌥' : 'Alt';
    if (key === 'Enter') return mac ? '↵' : 'Enter';
    return key;
  });
  return mac ? keys.join('') : keys.join('+');
}

/** 当前是不是 macOS（含 iPadOS 外接键盘）。只用来决定快捷键怎么写，不做功能判断。 */
export function isMacPlatform(): boolean {
  if (typeof navigator === 'undefined') return false;
  const platform = (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData?.platform || navigator.platform || navigator.userAgent;
  return /mac|iphone|ipad/i.test(platform);
}

function shortcutDigit(code: string): number | undefined {
  const match = /^(?:Digit|Numpad)([1-9])$/.exec(code);
  return match ? Number(match[1]) : undefined;
}
