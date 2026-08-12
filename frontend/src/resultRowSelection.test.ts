import { describe, expect, it } from 'vitest';
import { replaceResultRowSelection, updateResultRowSelection } from './resultRowSelection';

const displayed = ['1', '2', '3', '4'];

describe('result row selection', () => {
  it('selects one row on a plain click', () => {
    expect(updateResultRowSelection({ current: ['1', '2'], clicked: '3', displayed })).toEqual({ selected: ['3'], anchor: '3' });
  });

  it('toggles a row with Ctrl or Command', () => {
    expect(updateResultRowSelection({ current: ['1', '2'], clicked: '2', displayed, toggle: true }).selected).toEqual(['1']);
    expect(updateResultRowSelection({ current: ['1'], clicked: '3', displayed, toggle: true }).selected).toEqual(['1', '3']);
  });

  it('selects a contiguous range with Shift', () => {
    expect(updateResultRowSelection({ current: ['2'], clicked: '4', displayed, anchor: '2', range: true })).toEqual({ selected: ['2', '3', '4'], anchor: '2' });
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

    expect(range.selected).toHaveLength(5_000);
    expect(range.selected[0]).toBe('2500');
    expect(range.selected[range.selected.length - 1]).toBe('7499');
    expect(updateResultRowSelection({
      current: ['9000', '9500'],
      clicked: '100',
      displayed: manyRows,
      displayedIndex,
      toggle: true
    }).selected).toEqual(['100', '9000', '9500']);
  });
});
