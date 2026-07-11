import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  collapseObjectBranch,
  createObjectOpenIntent,
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
  afterEach(() => vi.useRealTimers());

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

  it('delays a single-click action and lets double-click replace it', () => {
    vi.useFakeTimers();
    const detail = vi.fn();
    const openTable = vi.fn();
    const intent = createObjectOpenIntent(220);

    intent.single(detail);
    vi.advanceTimersByTime(219);
    expect(detail).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(detail).toHaveBeenCalledOnce();

    detail.mockClear();
    intent.single(detail);
    intent.double(openTable);
    vi.runAllTimers();
    expect(detail).not.toHaveBeenCalled();
    expect(openTable).toHaveBeenCalledOnce();
  });
});
