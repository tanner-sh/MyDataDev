import { describe, expect, it } from 'vitest';
import {
  canJumpToRelation,
  foreignKeyColumns,
  relationJumpSql,
  relationJumpTooltip,
  relationTarget,
  relationTargetLabel,
  sqlLiteral
} from './relationNavigation';
import type { ObjectRelation } from './types';

const fk: ObjectRelation = {
  constraintName: 'FK_ORDERS_CUSTOMER',
  pkSchemaName: 'PUBLIC', pkTableName: 'CUSTOMERS', pkColumnName: 'ID',
  fkSchemaName: 'PUBLIC', fkTableName: 'ORDERS', fkColumnName: 'CUSTOMER_ID'
};

describe('relationTarget', () => {
  it('imported jumps to the referenced table, exported to the referencing one', () => {
    expect(relationTarget(fk, 'imported')).toEqual({
      schemaName: 'PUBLIC', tableName: 'CUSTOMERS', columnName: 'ID', sourceColumnName: 'CUSTOMER_ID'
    });
    expect(relationTarget(fk, 'exported')).toEqual({
      schemaName: 'PUBLIC', tableName: 'ORDERS', columnName: 'CUSTOMER_ID', sourceColumnName: 'ID'
    });
  });

  it('labels a target with and without a schema', () => {
    expect(relationTargetLabel(relationTarget(fk, 'imported'))).toBe('PUBLIC.CUSTOMERS');
    expect(relationTargetLabel({ tableName: 'T', columnName: 'C', sourceColumnName: 'S' })).toBe('T');
  });
});

describe('foreignKeyColumns', () => {
  it('maps each foreign key column to its target', () => {
    const map = foreignKeyColumns({ importedKeys: [fk] });
    expect(map.get('CUSTOMER_ID')?.tableName).toBe('CUSTOMERS');
    expect(map.size).toBe(1);
  });

  it('keeps the first mapping for a repeated column and copes with no relations', () => {
    const other = { ...fk, pkTableName: 'OTHER' };
    expect(foreignKeyColumns({ importedKeys: [fk, other] }).get('CUSTOMER_ID')?.tableName).toBe('CUSTOMERS');
    expect(foreignKeyColumns(null).size).toBe(0);
    expect(foreignKeyColumns(undefined).size).toBe(0);
  });
});

describe('sqlLiteral', () => {
  it('keeps numbers bare and quotes text', () => {
    expect(sqlLiteral(42)).toBe('42');
    expect(sqlLiteral('42')).toBe('42');
    expect(sqlLiteral('-1.5')).toBe('-1.5');
    expect(sqlLiteral('abc')).toBe("'abc'");
    expect(sqlLiteral(true)).toBe('TRUE');
    expect(sqlLiteral(null)).toBe('NULL');
  });

  it('escapes embedded quotes so a value cannot break out of the literal', () => {
    expect(sqlLiteral("O'Brien")).toBe("'O''Brien'");
    expect(sqlLiteral("'; DROP TABLE users; --")).toBe("'''; DROP TABLE users; --'");
  });
});

describe('relationJumpSql', () => {
  it('builds a scoped SELECT with quoted identifiers', () => {
    expect(relationJumpSql(relationTarget(fk, 'imported'), 7))
      .toBe('SELECT * FROM "PUBLIC"."CUSTOMERS" WHERE "ID" = 7');
  });

  it('omits the schema when the driver did not report one', () => {
    expect(relationJumpSql({ tableName: 'users', columnName: 'id', sourceColumnName: 'uid' }, 'a'))
      .toBe(`SELECT * FROM "users" WHERE "id" = 'a'`);
  });

  it('escapes a table name containing a double quote', () => {
    expect(relationJumpSql({ tableName: 'we"ird', columnName: 'id', sourceColumnName: 'x' }, 1))
      .toContain('"we""ird"');
  });
});

describe('canJumpToRelation / relationJumpTooltip', () => {
  it('refuses to jump on an empty foreign key', () => {
    expect(canJumpToRelation(null)).toBe(false);
    expect(canJumpToRelation('')).toBe(false);
    expect(canJumpToRelation(0)).toBe(true);
    expect(relationJumpTooltip(relationTarget(fk, 'imported'), null)).toContain('没有可跳转');
    expect(relationJumpTooltip(relationTarget(fk, 'imported'), 7)).toContain('PUBLIC.CUSTOMERS');
  });
});
