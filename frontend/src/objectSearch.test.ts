import { describe, expect, it } from 'vitest';
import {
  groupObjectSearchHits,
  isClassicObject,
  moveObjectSearchSelection,
  objectKindLabel,
  objectSearchHitKey,
  objectSearchRequestParams,
  objectSearchSummary,
  opensTableData,
  orderObjectSearchHits,
  OBJECT_SEARCH_LIMIT,
  type ObjectSearchHit
} from './objectSearch';

const hit = (kind: string, name: string, objectKey?: string): ObjectSearchHit =>
  ({ kind, name, displayName: name, schemaName: 'PUBLIC', objectKey });

describe('objectKindLabel', () => {
  it('translates the kinds the backend returns and passes through unknown ones', () => {
    expect(objectKindLabel('MATERIALIZED_VIEW')).toBe('物化视图');
    expect(objectKindLabel('PROCEDURE')).toBe('存储过程');
    expect(objectKindLabel('SOMETHING')).toBe('SOMETHING');
  });
});

describe('hit classification', () => {
  it('knows which hits can open data and which are classic objects', () => {
    expect(opensTableData(hit('TABLE', 'users'))).toBe(true);
    expect(opensTableData(hit('VIEW', 'v_users'))).toBe(false);
    expect(isClassicObject(hit('VIEW', 'v_users'))).toBe(true);
    expect(isClassicObject(hit('FUNCTION', 'f', 'k'))).toBe(false);
  });

  it('keys schema objects by objectKey and tables by schema+name', () => {
    expect(objectSearchHitKey(hit('FUNCTION', 'f', 'key-1'))).toBe('key-1');
    expect(objectSearchHitKey(hit('TABLE', 'users'))).toBe('TABLE:PUBLIC.users');
  });
});

describe('orderObjectSearchHits', () => {
  it('puts tables and views first and keeps the backend order inside a kind', () => {
    const ordered = orderObjectSearchHits([
      hit('SEQUENCE', 's1', 'k1'),
      hit('TABLE', 't1'),
      hit('FUNCTION', 'f1', 'k2'),
      hit('TABLE', 't2'),
      hit('VIEW', 'v1')
    ]);

    expect(ordered.map((item) => item.name)).toEqual(['t1', 't2', 'v1', 'f1', 's1']);
  });

  it('keeps unknown kinds at the end rather than dropping them', () => {
    const ordered = orderObjectSearchHits([hit('WEIRD', 'w'), hit('TABLE', 't')]);
    expect(ordered.map((item) => item.name)).toEqual(['t', 'w']);
  });
});

describe('groupObjectSearchHits', () => {
  it('groups in the same order the flat list uses', () => {
    const groups = groupObjectSearchHits([hit('FUNCTION', 'f', 'k'), hit('TABLE', 't'), hit('TABLE', 't2')]);

    expect(groups.map((group) => group.kind)).toEqual(['TABLE', 'FUNCTION']);
    expect(groups[0].hits.map((item) => item.name)).toEqual(['t', 't2']);
    expect(groups[0].label).toBe('表');
  });
});

describe('moveObjectSearchSelection', () => {
  it('wraps around both ends', () => {
    expect(moveObjectSearchSelection(0, 3, -1)).toBe(2);
    expect(moveObjectSearchSelection(2, 3, 1)).toBe(0);
    expect(moveObjectSearchSelection(1, 3, 1)).toBe(2);
  });

  it('starts from either end when nothing is selected yet', () => {
    expect(moveObjectSearchSelection(-1, 3, 1)).toBe(0);
    expect(moveObjectSearchSelection(-1, 3, -1)).toBe(2);
  });

  it('stays unselected when there is nothing to select', () => {
    expect(moveObjectSearchSelection(-1, 0, 1)).toBe(-1);
    expect(moveObjectSearchSelection(2, 0, 1)).toBe(-1);
  });
});

describe('objectSearchRequestParams', () => {
  it('omits a blank keyword and includes the schema when scoped', () => {
    expect(objectSearchRequestParams('')).toBe(`limit=${OBJECT_SEARCH_LIMIT}`);
    expect(objectSearchRequestParams('  ')).toBe(`limit=${OBJECT_SEARCH_LIMIT}`);
    const params = new URLSearchParams(objectSearchRequestParams(' orders ', 'PUBLIC'));
    expect(params.get('keyword')).toBe('orders');
    expect(params.get('schema')).toBe('PUBLIC');
  });
});

describe('objectSearchSummary', () => {
  it('distinguishes empty, complete and truncated results', () => {
    expect(objectSearchSummary(0, false, '')).toContain('输入关键字');
    expect(objectSearchSummary(0, false, 'zzz')).toBe('没有匹配的数据库对象');
    expect(objectSearchSummary(12, false, 'a')).toBe('共 12 个匹配对象');
    expect(objectSearchSummary(40, true, 'a')).toContain('更精确');
  });
});
