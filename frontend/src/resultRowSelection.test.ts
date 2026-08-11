import { describe, expect, it } from 'vitest';
import { updateResultRowSelection } from './resultRowSelection';

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
});
