import { describe, expect, it } from 'vitest';
import { tableToolbarCollapsed } from './tableToolbarLayout';

describe('tableToolbarCollapsed', () => {
  it('宽度未知时不收起', () => {
    expect(tableToolbarCollapsed(undefined, false)).toBe(false);
    expect(tableToolbarCollapsed(0, true)).toBe(false);
  });

  it('没有待提交修改时按 1000px 收起', () => {
    expect(tableToolbarCollapsed(1001, false)).toBe(false);
    expect(tableToolbarCollapsed(1000, false)).toBe(true);
  });

  it('有待提交修改时更早收起', () => {
    expect(tableToolbarCollapsed(1100, false)).toBe(false);
    expect(tableToolbarCollapsed(1100, true)).toBe(true);
    expect(tableToolbarCollapsed(1241, true)).toBe(false);
  });
});
