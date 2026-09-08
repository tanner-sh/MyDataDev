import { describe, expect, it } from 'vitest';
import { cellDisplayKind, cellDraftChanged, cellDraftText } from './cellDraft';

describe('cellDisplayKind', () => {
  it('separates NULL from the empty string', () => {
    expect(cellDisplayKind(null)).toBe('null');
    expect(cellDisplayKind(undefined)).toBe('null');
    expect(cellDisplayKind('')).toBe('empty');
  });

  it('keeps falsy values that are real data out of both special states', () => {
    expect(cellDisplayKind(0)).toBe('value');
    expect(cellDisplayKind(false)).toBe('value');
    expect(cellDisplayKind('0')).toBe('value');
    expect(cellDisplayKind(' ')).toBe('value');
  });
});

describe('cellDraftText', () => {
  it('renders NULL and the empty string as the same empty text', () => {
    expect(cellDraftText(null)).toBe('');
    expect(cellDraftText(undefined)).toBe('');
    expect(cellDraftText('')).toBe('');
  });

  it('stringifies everything else', () => {
    expect(cellDraftText(0)).toBe('0');
    expect(cellDraftText(false)).toBe('false');
    expect(cellDraftText('abc')).toBe('abc');
  });
});

describe('cellDraftChanged', () => {
  // 这条就是那个 bug：点开一个 NULL 格子，什么都不输入再点走。
  it('does not treat an untouched NULL cell as an edit', () => {
    expect(cellDraftChanged('', cellDraftText(null))).toBe(false);
  });

  it('still treats clearing a real value as a change to the empty string', () => {
    expect(cellDraftChanged('', cellDraftText('abc'))).toBe(true);
  });

  it('reports ordinary typing', () => {
    expect(cellDraftChanged('新值', cellDraftText(null))).toBe(true);
    expect(cellDraftChanged('abc', cellDraftText('abc'))).toBe(false);
  });
});
