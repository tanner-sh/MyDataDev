import { describe, expect, it } from 'vitest';
import { calculateTableScrollHeight } from './useTableViewportHeight';

describe('calculateTableScrollHeight', () => {
  it('uses the visible viewport after subtracting the header', () => {
    expect(calculateTableScrollHeight(420, 40)).toBe(380);
    expect(calculateTableScrollHeight(420.9, 39.4, 8)).toBe(373);
  });

  it('does not invent a one-row fallback for hidden or undersized panes', () => {
    expect(calculateTableScrollHeight(0, 40)).toBeUndefined();
    expect(calculateTableScrollHeight(40, 40)).toBeUndefined();
    expect(calculateTableScrollHeight(24, 40)).toBeUndefined();
  });
});
