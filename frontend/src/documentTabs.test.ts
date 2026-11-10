import { describe, expect, it } from 'vitest';
import { documentTabLabels, middleEllipsis, tabScrollState, tabScrollTarget, wheelToHorizontal, tabConnectionBadge, tabsSpanConnections } from './documentTabs';

describe('document tabs', () => {
  it('keeps both ends when a name is too long, so shared prefixes do not hide the difference', () => {
    expect(middleEllipsis('SHORT', 28)).toBe('SHORT');
    const a = middleEllipsis('ODS_VIS_CH_BOND_INTER_B3A0963D_BONDINFO', 20);
    const b = middleEllipsis('ODS_VIS_CH_BOND_INTER_B3A0963D_CREDIT', 20);
    expect(Array.from(a)).toHaveLength(20);
    expect(a.startsWith('ODS_VIS')).toBe(true);
    expect(a.endsWith('BONDINFO')).toBe(true);
    expect(b.endsWith('CREDIT')).toBe(true);
    expect(a).not.toBe(b);
  });

  it('counts characters, not UTF-16 units', () => {
    expect(middleEllipsis('客户订单明细表历史归档', 6)).toBe('客户…史归档');
    expect(Array.from(middleEllipsis('𠀀𠀁𠀂𠀃𠀄𠀅𠀆𠀇', 5))).toHaveLength(5);
  });

  it('drops the schema unless the same name is open in more than one schema', () => {
    const labels = documentTabLabels([
      { key: '1', kind: 'table', schemaName: 'YY_FBIP_DATACENTER', name: 'BOND_BA_BONDINFO' },
      { key: '2', kind: 'table', schemaName: 'YY_FBIP_DATACENTER', name: 'BOND_BA_CREDIT' },
      { key: '3', kind: 'object', schemaName: 'YY_FBIP_DATACENTER', name: 'BOND_BA_CREDIT' },
      { key: '4', kind: 'table', schemaName: 'A', name: 'orders' },
      { key: '5', kind: 'table', schemaName: 'b', name: 'ORDERS' }
    ]);
    expect(labels[0]).toMatchObject({ name: 'BOND_BA_BONDINFO', schema: undefined, kindLabel: '数据', title: 'YY_FBIP_DATACENTER.BOND_BA_BONDINFO · 数据' });
    // 同一个 Schema 下的「数据」和「结构」不算歧义，靠类型标记区分。
    expect(labels[2]).toMatchObject({ schema: undefined, kindLabel: '结构' });
    expect(labels[3].schema).toBe('A');
    expect(labels[4].schema).toBe('b');
  });

  it('reports which arrows are useful', () => {
    expect(tabScrollState(0, 500, 500)).toEqual({ overflowing: false, canScrollLeft: false, canScrollRight: false });
    expect(tabScrollState(0, 500, 900)).toEqual({ overflowing: true, canScrollLeft: false, canScrollRight: true });
    expect(tabScrollState(200, 500, 900)).toEqual({ overflowing: true, canScrollLeft: true, canScrollRight: true });
    // 缩放后的亚像素余量不应让右箭头一直亮着。
    expect(tabScrollState(399.6, 500, 900)).toEqual({ overflowing: true, canScrollLeft: true, canScrollRight: false });
  });

  it('pages by most of the visible width and clamps to the ends', () => {
    expect(tabScrollTarget(0, 500, 2000, 'right')).toBe(400);
    expect(tabScrollTarget(1400, 500, 2000, 'right')).toBe(1500);
    expect(tabScrollTarget(100, 500, 2000, 'left')).toBe(0);
    expect(tabScrollTarget(0, 100, 2000, 'right')).toBe(120);
  });

  it('turns a vertical mouse wheel into horizontal scrolling and leaves horizontal gestures alone', () => {
    expect(wheelToHorizontal(0, 100)).toBe(100);
    expect(wheelToHorizontal(0, 3, 1)).toBe(120);
    expect(wheelToHorizontal(60, 10)).toBe(0);
  });
});

describe('tab connection badges', () => {
  const connections = [{ id: 1, name: 'eastmoney', environment: 'dev' }, { id: 2, name: '生产核心库-交易与清算', environment: 'prod' }];

  it('names the connection only when tabs come from more than one', () => {
    expect(tabsSpanConnections([1, 1, undefined])).toBe(false);
    expect(tabsSpanConnections([1, 2])).toBe(true);
    expect(tabConnectionBadge(1, connections, false)).toEqual({ fullName: 'eastmoney', production: false, unavailable: false, name: undefined });
    expect(tabConnectionBadge(1, connections, true).name).toBe('eastmoney');
  });

  it('always marks production and unavailable connections', () => {
    expect(tabConnectionBadge(2, connections, false).production).toBe(true);
    expect(tabConnectionBadge(9, connections, false)).toMatchObject({ unavailable: true, production: false });
    expect(tabConnectionBadge(9, connections, true).name).toBe('连接不可用');
    expect(tabConnectionBadge(undefined, connections, true)).toEqual({ production: false, unavailable: false });
  });
});
