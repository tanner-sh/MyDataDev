import { describe, expect, it } from 'vitest';
import {
  collapseObjectBranch,
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

});
