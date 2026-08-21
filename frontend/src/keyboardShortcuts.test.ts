import { describe, expect, it } from 'vitest';
import { resolveAppShortcut, type ShortcutKeyEvent } from './keyboardShortcuts';

function press(overrides: Partial<ShortcutKeyEvent>): ShortcutKeyEvent {
  return { key: '', code: '', ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, ...overrides };
}

describe('resolveAppShortcut', () => {
  it('toggles the explorer on either command key', () => {
    expect(resolveAppShortcut(press({ key: 'b', code: 'KeyB', ctrlKey: true }))).toEqual({ kind: 'toggle-explorer' });
    expect(resolveAppShortcut(press({ key: 'B', code: 'KeyB', metaKey: true }))).toEqual({ kind: 'toggle-explorer' });
  });

  it('commits table changes on the command key plus S', () => {
    expect(resolveAppShortcut(press({ key: 's', code: 'KeyS', metaKey: true }))).toEqual({ kind: 'commit-table-changes' });
  });

  it('opens a new SQL tab on Alt+N', () => {
    // macOS rewrites `key` under Option, so only `code` is reliable here.
    expect(resolveAppShortcut(press({ key: '˜', code: 'KeyN', altKey: true }))).toEqual({ kind: 'new-sql-tab' });
  });

  it('selects a SQL tab by its ordinal on Alt plus a digit', () => {
    expect(resolveAppShortcut(press({ key: '¡', code: 'Digit1', altKey: true }))).toEqual({ kind: 'select-sql-tab', index: 0 });
    expect(resolveAppShortcut(press({ key: '', code: 'Digit9', altKey: true }))).toEqual({ kind: 'select-sql-tab', index: 8 });
    expect(resolveAppShortcut(press({ key: '', code: 'Numpad3', altKey: true }))).toEqual({ kind: 'select-sql-tab', index: 2 });
  });

  it('ignores Alt+0, which has no tab to select', () => {
    expect(resolveAppShortcut(press({ key: '0', code: 'Digit0', altKey: true }))).toBeUndefined();
  });

  it('ignores combinations that carry an extra modifier', () => {
    expect(resolveAppShortcut(press({ key: 'b', code: 'KeyB', ctrlKey: true, shiftKey: true }))).toBeUndefined();
    expect(resolveAppShortcut(press({ key: 's', code: 'KeyS', ctrlKey: true, altKey: true }))).toBeUndefined();
    expect(resolveAppShortcut(press({ key: 'n', code: 'KeyN', altKey: true, ctrlKey: true }))).toBeUndefined();
    expect(resolveAppShortcut(press({ key: '1', code: 'Digit1', altKey: true, shiftKey: true }))).toBeUndefined();
  });

  it('ignores plain typing', () => {
    expect(resolveAppShortcut(press({ key: 'b', code: 'KeyB' }))).toBeUndefined();
    expect(resolveAppShortcut(press({ key: '1', code: 'Digit1' }))).toBeUndefined();
  });

  it('leaves the editor shortcuts alone', () => {
    // Ctrl+Enter and Ctrl+Shift+F belong to the Monaco surface, not here.
    expect(resolveAppShortcut(press({ key: 'Enter', code: 'Enter', ctrlKey: true }))).toBeUndefined();
    expect(resolveAppShortcut(press({ key: 'f', code: 'KeyF', ctrlKey: true, shiftKey: true }))).toBeUndefined();
  });
});
