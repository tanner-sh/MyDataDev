import { describe, expect, it } from 'vitest';
import { replaceResultRowSelection, resolveResultGridKeyboardAction, updateResultRowSelection } from './resultRowSelection';

const displayed = ['1', '2', '3', '4'];

describe('result row selection', () => {
  it('adds a row on a plain click without clearing the current selection', () => {
    expect(updateResultRowSelection({ current: ['1', '2'], clicked: '3', displayed })).toEqual({ selected: ['1', '2', '3'], anchor: '3' });
  });

  it('only clears the clicked row when it is already selected', () => {
    expect(updateResultRowSelection({ current: ['2'], clicked: '2', displayed })).toEqual({ selected: [], anchor: undefined });
    expect(updateResultRowSelection({ current: ['1', '2'], clicked: '2', displayed })).toEqual({ selected: ['1'], anchor: '2' });
  });

  it('adds a contiguous range with Shift while preserving rows outside it', () => {
    expect(updateResultRowSelection({ current: ['1', '3'], clicked: '4', displayed, anchor: '3', range: true })).toEqual({ selected: ['1', '3', '4'], anchor: '3' });
  });

  it('selects or clears all displayed rows in displayed order', () => {
    expect(replaceResultRowSelection(displayed, displayed)).toEqual({ selected: displayed, anchor: '1' });
    expect(replaceResultRowSelection(displayed, [])).toEqual({ selected: [], anchor: undefined });
  });

  it('drops rows outside the filtered result and preserves a valid anchor', () => {
    expect(replaceResultRowSelection(['2', '4'], ['4', 'missing', '2'], '4')).toEqual({ selected: ['2', '4'], anchor: '4' });
    expect(replaceResultRowSelection(['2', '4'], ['4'], 'missing')).toEqual({ selected: ['4'], anchor: '4' });
  });

  it('uses a cached index and preserves displayed order for large selections', () => {
    const manyRows = Array.from({ length: 10_000 }, (_value, index) => String(index));
    const displayedIndex = new Map(manyRows.map((key, index) => [key, index]));
    const range = updateResultRowSelection({
      current: ['9999'],
      clicked: '7499',
      displayed: manyRows,
      displayedIndex,
      anchor: '2500',
      range: true
    });

    expect(range.selected).toHaveLength(5_001);
    expect(range.selected[0]).toBe('2500');
    expect(range.selected[range.selected.length - 2]).toBe('7499');
    expect(range.selected[range.selected.length - 1]).toBe('9999');
    expect(updateResultRowSelection({
      current: ['9000', '9500'],
      clicked: '100',
      displayed: manyRows,
      displayedIndex
    }).selected).toEqual(['100', '9000', '9500']);
  });

  it('resolves result shortcuts only for the focused result surface', () => {
    expect(resolveResultGridKeyboardAction({ key: 'c', ctrlKey: true })).toBe('copy');
    expect(resolveResultGridKeyboardAction({ key: 'C', metaKey: true })).toBe('copy');
    expect(resolveResultGridKeyboardAction({ key: 'a', ctrlKey: true })).toBe('select-all');
    expect(resolveResultGridKeyboardAction({ key: 'Escape' })).toBe('clear-selection');
    expect(resolveResultGridKeyboardAction({ key: 'c' })).toBeUndefined();
  });

  it('does not handle shortcuts originating from an input control', () => {
    expect(resolveResultGridKeyboardAction({ key: 'c', ctrlKey: true, textEntry: true })).toBeUndefined();
    expect(resolveResultGridKeyboardAction({ key: 'a', metaKey: true, textEntry: true })).toBeUndefined();
    expect(resolveResultGridKeyboardAction({ key: 'Escape', textEntry: true })).toBeUndefined();
  });
});
