import { describe, expect, it } from 'vitest';
import { calculateTableScrollHeight, nextTableScrollHeight } from './useTableViewportHeight';

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

  it('keeps the last valid height while a tab is hidden', () => {
    expect(nextTableScrollHeight(380, 0, 40)).toBe(380);
    expect(nextTableScrollHeight(380, 24, 40)).toBe(380);
    expect(nextTableScrollHeight(undefined, 0, 40)).toBeUndefined();
  });

  it('replaces the preserved height after the tab becomes visible again', () => {
    expect(nextTableScrollHeight(380, 520, 40)).toBe(480);
  });
});
