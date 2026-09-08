import { describe, expect, it } from 'vitest';
import { cellValueMenuItems, emptyStringMeansNull } from './cellValueMenu';

const actions = (items: ReturnType<typeof cellValueMenuItems>) => items.map((item) => item.action);

describe('emptyStringMeansNull', () => {
  it('flags the Oracle family, where the two values are indistinguishable in storage', () => {
    expect(emptyStringMeansNull('oracle')).toBe(true);
    expect(emptyStringMeansNull('oceanbase-oracle')).toBe(true);
    expect(emptyStringMeansNull('dm')).toBe(true);
    expect(emptyStringMeansNull('ORACLE')).toBe(true);
  });

  it('leaves the databases that keep them apart alone', () => {
    expect(emptyStringMeansNull('mysql')).toBe(false);
    expect(emptyStringMeansNull('postgresql')).toBe(false);
    expect(emptyStringMeansNull(undefined)).toBe(false);
  });
});

describe('cellValueMenuItems', () => {
  it('offers both explicit values on an editable cell', () => {
    expect(actions(cellValueMenuItems({ editable: true, displayKind: 'value', selectedRowCount: 0 })))
      .toEqual(['set-null', 'set-empty', 'copy']);
  });

  it('greys out the value the cell already holds', () => {
    const onNull = cellValueMenuItems({ editable: true, displayKind: 'null', selectedRowCount: 0 });
    expect(onNull.find((item) => item.action === 'set-null')?.disabled).toBe(true);
    expect(onNull.find((item) => item.action === 'set-empty')?.disabled).toBeFalsy();
  });

  it('warns on Oracle that the empty string will come back as NULL', () => {
    const oracle = cellValueMenuItems({ editable: true, displayKind: 'value', dbType: 'oracle', selectedRowCount: 0 });
    expect(oracle.find((item) => item.action === 'set-empty')?.hint).toContain('NULL');
    const mysql = cellValueMenuItems({ editable: true, displayKind: 'value', dbType: 'mysql', selectedRowCount: 0 });
    expect(mysql.find((item) => item.action === 'set-empty')?.hint).toBeUndefined();
  });

  it('only offers the bulk action when more than one row is selected', () => {
    expect(actions(cellValueMenuItems({ editable: true, displayKind: 'value', selectedRowCount: 1 })))
      .not.toContain('set-null-in-selection');
    expect(actions(cellValueMenuItems({ editable: true, displayKind: 'value', selectedRowCount: 4 })))
      .toContain('set-null-in-selection');
  });

  it('keeps copy as the only action on a column that cannot be edited', () => {
    expect(actions(cellValueMenuItems({ editable: false, displayKind: 'value', selectedRowCount: 3 })))
      .toEqual(['copy']);
  });
});
