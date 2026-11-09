import { describe, expect, it } from 'vitest';
import { tableToolbarCollapsed } from './tableToolbarLayout';

describe('tableToolbarCollapsed', () => {
  it('宽度未知时不收起', () => {
    expect(tableToolbarCollapsed(undefined, false)).toBe(false);
    expect(tableToolbarCollapsed(0, true)).toBe(false);
  });

  it('没有待提交修改时按 1100px 收起', () => {
    expect(tableToolbarCollapsed(1101, false)).toBe(false);
    expect(tableToolbarCollapsed(1100, false)).toBe(true);
  });

  it('有待提交修改时更早收起', () => {
    expect(tableToolbarCollapsed(1200, false)).toBe(false);
    expect(tableToolbarCollapsed(1200, true)).toBe(true);
    expect(tableToolbarCollapsed(1341, true)).toBe(false);
  });

  it('停靠到顶栏时按顶栏中间那一格的宽度判断', () => {
    expect(tableToolbarCollapsed(700, false, true)).toBe(false);
    expect(tableToolbarCollapsed(600, false, true)).toBe(true);
    expect(tableToolbarCollapsed(820, true, true)).toBe(true);
    expect(tableToolbarCollapsed(961, true, true)).toBe(false);
  });
});
