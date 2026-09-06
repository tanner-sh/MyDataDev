import { describe, expect, it } from 'vitest';
import {
  collapseObjectBranch,
  clampObjectTreeScrollTop,
  nextObjectTreeViewportHeight,
  databaseObjectNodeKey,
  findMatchingDatabaseObject,
  groupDatabaseObjects,
  keepOnlyObjectBranch,
  sameDatabaseObject,
  withLoadedObjectStructure
} from './objectTreeModel';
import type { DbObject } from './types';

function object(name: string, type = 'TABLE', schemaName = 'PUBLIC'): DbObject {
  return { schemaName, name, type, columns: [], indexes: [] };
}

describe('object tree model', () => {
  it('groups tables before views and sorts names naturally', () => {
    const groups = groupDatabaseObjects([
      object('view_a', 'VIEW'),
      object('table10'),
      object('table2'),
      object('sequence_a', 'SEQUENCE')
    ]);

    expect(groups.map((group) => group.key)).toEqual(['TABLE', 'VIEW', 'SEQUENCE']);
    expect(groups[0].objects.map((item) => item.name)).toEqual(['table2', 'table10']);
  });

  it('creates stable encoded keys and matches driver-normalized names', () => {
    expect(databaseObjectNodeKey(object('order/items'))).toContain('order%2Fitems');
    expect(sameDatabaseObject(object('Orders', 'TABLE', 'Sales'), { schemaName: 'SALES', name: 'orders' })).toBe(true);
    expect(findMatchingDatabaseObject([object('Foo'), object('foo')], { schemaName: 'PUBLIC', name: 'foo' })?.name).toBe('foo');
    expect(findMatchingDatabaseObject([object('Foo'), object('foo')], { schemaName: 'public', name: 'FOO' })).toBeUndefined();
  });

  it('keeps only the newly expanded object branch', () => {
    const first = databaseObjectNodeKey(object('first'));
    const second = databaseObjectNodeKey(object('second'));
    const group = 'object-type:PUBLIC:TABLE';
    const expanded = keepOnlyObjectBranch([group, first, `${first}:columns`, second, `${second}:indexes`], second);

    expect(expanded).toEqual([group, second, `${second}:indexes`]);
    expect(collapseObjectBranch(expanded, second)).toEqual([group]);
  });

  it('keeps loaded fields and indexes when a filtered object list is refreshed', () => {
    const summaryObject = object('customers');
    const loadedObject: DbObject = {
      ...summaryObject,
      columns: [{ name: 'customer_id', type: 'BIGINT', size: 20, nullable: false }],
      indexes: [{ name: 'PRIMARY', columnName: 'customer_id', unique: true }]
    };

    expect(withLoadedObjectStructure(summaryObject, loadedObject)).toEqual(loadedObject);
    expect(withLoadedObjectStructure(summaryObject)).toBe(summaryObject);
  });

  it('clamps remembered virtual scroll positions to the current list height', () => {
    expect(clampObjectTreeScrollTop(480, 100, 300, 30)).toBe(480);
    expect(clampObjectTreeScrollTop(480, 12, 300, 30)).toBe(60);
    expect(clampObjectTreeScrollTop(480, 5, 300, 30)).toBe(0);
    expect(clampObjectTreeScrollTop(-20, 100, 300, 30)).toBe(0);
  });

  it('includes the loading row when calculating the virtual scroll range', () => {
    expect(clampObjectTreeScrollTop(100, 10, 300, 30)).toBe(0);
    expect(clampObjectTreeScrollTop(100, 10, 300, 30, true)).toBe(30);
  });

});

describe('nextObjectTreeViewportHeight', () => {
  const node = (clientHeight: number, isConnected = true) => ({ clientHeight, isConnected });

  it('挂着的元素按实际高度量，但不低于一行', () => {
    expect(nextObjectTreeViewportHeight(30, node(560), 30)).toBe(560);
    expect(nextObjectTreeViewportHeight(560, node(0), 30)).toBe(30);
  });

  /**
   * 这条是「切一下收藏页签，回来只剩十来行」那个故障的根因。
   *
   * ResizeObserver 在被观察元素卸载时会补一次 0×0 的回调。照单全收的话视口高度会被记成一行，
   * 而虚拟列表渲染多少行正是由它决定的 —— 回到「全部」时列表只剩 11 行，后面全是空白，
   * 滚动条却仍按 200 行撑开，看起来就像数据丢了。
   */
  it('元素已经被移出文档时保留上一次量到的高度', () => {
    expect(nextObjectTreeViewportHeight(560, node(0, false), 30)).toBe(560);
    expect(nextObjectTreeViewportHeight(560, node(560, false), 30)).toBe(560);
  });

  it('元素还没挂上时同样保留上一次的高度', () => {
    expect(nextObjectTreeViewportHeight(560, null, 30)).toBe(560);
  });
});
