import { describe, expect, it } from 'vitest';
import { createTableRequest, fullTableName, tableActionRequest } from './tableLifecycle';
import type { ObjectDetail } from './types';

const detail: ObjectDetail = {
  schemaName: 'PUBLIC',
  name: 'USERS',
  type: 'TABLE',
  columns: [],
  indexes: [],
  primaryKeys: [],
  structureVersion: 'version-1'
};

describe('tableLifecycle', () => {
  it('builds a create request without editor-only keys', () => {
    const request = createTableRequest(
      'PUBLIC',
      '  APP_USER  ',
      [{ key: 'column-1', name: 'ID', type: 'BIGINT', nullable: false, deleted: false }],
      [{ key: 'index-1', name: 'IDX_APP_USER_ID', columns: ['ID'], unique: true, deleted: false }],
      ['ID'],
      'PUBLIC.APP_USER'
    );

    expect(request).toEqual({
      operation: 'CREATE',
      schemaName: 'PUBLIC',
      tableName: 'APP_USER',
      columns: [{ name: 'ID', type: 'BIGINT', nullable: false, deleted: false }],
      indexes: [{ name: 'IDX_APP_USER_ID', columns: ['ID'], unique: true, deleted: false }],
      primaryKeys: ['ID'],
      confirmation: 'PUBLIC.APP_USER'
    });
  });

  it('keeps the live structure version for rename and drop', () => {
    expect(tableActionRequest('RENAME', detail, ' MEMBERS ', 'PUBLIC.USERS')).toMatchObject({
      operation: 'RENAME',
      schemaName: 'PUBLIC',
      tableName: 'USERS',
      newTableName: 'MEMBERS',
      structureVersion: 'version-1',
      confirmation: 'PUBLIC.USERS'
    });
    expect(tableActionRequest('DROP', detail)).toMatchObject({
      operation: 'DROP',
      tableName: 'USERS',
      structureVersion: 'version-1'
    });
  });

  it('formats qualified and unqualified confirmation names', () => {
    expect(fullTableName('PUBLIC', 'USERS')).toBe('PUBLIC.USERS');
    expect(fullTableName(undefined, 'USERS')).toBe('USERS');
  });
});
