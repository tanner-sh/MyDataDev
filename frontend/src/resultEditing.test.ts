import { describe, expect, it } from 'vitest';
import {
  applyCommittedChanges,
  applyResultCellEdit,
  buildResultChanges,
  countEditedResultRows,
  countResultEdits,
  EMPTY_RESULT_EDIT_STATE,
  isResultColumnEditable,
  isResultEditable,
  resultEditDisabledReason,
  resultEditedValue,
  resultEditSummary,
  resultRowEdits,
  type ResultEditInfo
} from './resultEditing';

const editable: ResultEditInfo = {
  editable: true,
  schemaName: 'PUBLIC',
  tableName: 'CUSTOMERS',
  keyColumns: ['ID'],
  rowKeyTokens: ['token-0', 'token-1']
};

const columns = [{ label: 'ID' }, { label: 'NAME' }, { label: 'CITY' }];
const rows = [[1, 'Alice', '上海'], [2, 'Bob', '北京']];

describe('isResultEditable', () => {
  it('needs both the flag and actual tokens', () => {
    expect(isResultEditable(editable)).toBe(true);
    expect(isResultEditable({ ...editable, rowKeyTokens: [] })).toBe(false);
    expect(isResultEditable({ ...editable, editable: false })).toBe(false);
    expect(isResultEditable(null)).toBe(false);
    expect(isResultEditable(undefined)).toBe(false);
  });
});

describe('isResultColumnEditable', () => {
  it('refuses the row-locating columns because changing them loses the row', () => {
    expect(isResultColumnEditable(editable, 'NAME')).toBe(true);
    expect(isResultColumnEditable(editable, 'ID')).toBe(false);
    expect(isResultColumnEditable(editable, 'id')).toBe(false);
  });

  it('refuses everything when the result is not editable', () => {
    expect(isResultColumnEditable({ ...editable, editable: false }, 'NAME')).toBe(false);
    expect(isResultColumnEditable(null, 'NAME')).toBe(false);
  });
});

describe('applyResultCellEdit', () => {
  it('records an edit and reads it back', () => {
    const state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'NAME', 'Alicia', 'Alice');

    expect(countResultEdits(state)).toBe(1);
    expect(resultEditedValue(state, 0, 'NAME')?.value).toBe('Alicia');
  });

  it('drops the edit when the value is changed back to the original', () => {
    let state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'NAME', 'Alicia', 'Alice');
    state = applyResultCellEdit(state, 0, 'NAME', 'Alice', 'Alice');

    expect(countResultEdits(state)).toBe(0);
  });

  it('treats a numeric cell typed back as a string as unchanged', () => {
    // 输入框里拿到的永远是字符串，1 与 "1" 不该被当成一次修改。
    const state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'ID', '1', 1);
    expect(countResultEdits(state)).toBe(0);
  });

  it('keeps the last write for the same cell', () => {
    let state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'NAME', 'A', 'Alice');
    state = applyResultCellEdit(state, 0, 'NAME', 'B', 'Alice');

    expect(countResultEdits(state)).toBe(1);
    expect(resultEditedValue(state, 0, 'NAME')?.value).toBe('B');
  });

  it('counts cells and rows separately', () => {
    let state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'NAME', 'A', 'Alice');
    state = applyResultCellEdit(state, 0, 'CITY', '广州', '上海');
    state = applyResultCellEdit(state, 1, 'NAME', 'B', 'Bob');

    expect(countResultEdits(state)).toBe(3);
    expect(countEditedResultRows(state)).toBe(2);
    expect(resultEditSummary(state)).toBe('待提交 3 处修改 · 涉及 2 行');
    expect(resultEditSummary(EMPTY_RESULT_EDIT_STATE)).toBe('无待提交修改');
  });
});

describe('buildResultChanges', () => {
  it('folds each row into one UPDATE carrying its token and original values', () => {
    let state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'NAME', 'Alicia', 'Alice');
    state = applyResultCellEdit(state, 0, 'CITY', '广州', '上海');

    const changes = buildResultChanges(state, rows, columns, editable.rowKeyTokens);

    expect(changes).toEqual([{
      type: 'UPDATE',
      keyToken: 'token-0',
      values: { NAME: 'Alicia', CITY: '广州' },
      originalValues: { NAME: 'Alice', CITY: '上海' }
    }]);
  });

  it('emits the rows in index order so the preview reads top to bottom', () => {
    let state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 1, 'NAME', 'B', 'Bob');
    state = applyResultCellEdit(state, 0, 'NAME', 'A', 'Alice');

    expect(buildResultChanges(state, rows, columns, editable.rowKeyTokens).map((change) => change.keyToken))
      .toEqual(['token-0', 'token-1']);
  });

  it('skips a row whose token is missing rather than risk editing the wrong row', () => {
    const state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 1, 'NAME', 'B', 'Bob');

    expect(buildResultChanges(state, rows, columns, ['token-0'])).toEqual([]);
  });

  it('ignores edits on columns that are no longer in the result', () => {
    const state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'GONE', 'x', 'y');

    expect(buildResultChanges(state, rows, columns, editable.rowKeyTokens)).toEqual([]);
  });
});

describe('resultEditDisabledReason', () => {
  it('prefers the reason the backend gave', () => {
    expect(resultEditDisabledReason({ ...editable, editable: false, reason: '查询结果缺少行定位字段：ID' }))
      .toBe('查询结果缺少行定位字段：ID');
    expect(resultEditDisabledReason(null)).toBe('当前结果不支持就地编辑');
    expect(resultEditDisabledReason({ ...editable, rowKeyTokens: [] })).toBe('当前结果没有可用的行定位令牌');
  });
});

describe('resultRowEdits', () => {
  it('collects only the edits belonging to one row', () => {
    let state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'NAME', 'A', 'Alice');
    state = applyResultCellEdit(state, 0, 'CITY', '广州', '上海');
    state = applyResultCellEdit(state, 1, 'NAME', 'B', 'Bob');

    expect(resultRowEdits(state, 0)).toEqual({ NAME: 'A', CITY: '广州' });
    expect(resultRowEdits(state, 1)).toEqual({ NAME: 'B' });
  });

  it('returns undefined for an untouched row so the record identity can stay stable', () => {
    expect(resultRowEdits(EMPTY_RESULT_EDIT_STATE, 0)).toBeUndefined();
  });
});

describe('applyCommittedChanges', () => {
  const columns = [{ label: 'ID' }, { label: 'NAME' }, { label: 'CITY' }];
  const rows: unknown[][] = [[1, 'Alice', '上海'], [2, 'Bob', '北京']];
  const tokens = ['tok-1', 'tok-2'];

  it('按行定位令牌写回已提交的值', () => {
    const next = applyCommittedChanges(rows, columns, tokens, [
      { type: 'UPDATE', keyToken: 'tok-2', values: { NAME: 'Bobby' }, originalValues: { NAME: 'Bob' } }
    ]);

    expect(next[1]).toEqual([2, 'Bobby', '北京']);
    // 未涉及的行保持原引用，表格不会白白重绘。
    expect(next[0]).toBe(rows[0]);
    expect(rows[1]).toEqual([2, 'Bob', '北京']);
  });

  it('令牌对不上就不动任何一行', () => {
    // 令牌是后端签发的行身份；对不上说明结果已经和令牌不是同一批，宁可不写。
    const next = applyCommittedChanges(rows, columns, tokens, [
      { type: 'UPDATE', keyToken: 'tok-9', values: { NAME: 'X' } }
    ]);
    expect(next).toBe(rows);
  });

  it('忽略结果集里没有的列与非 UPDATE 变更', () => {
    expect(applyCommittedChanges(rows, columns, tokens, [
      { type: 'UPDATE', keyToken: 'tok-1', values: { MISSING: 'x' } }
    ])).toBe(rows);
    expect(applyCommittedChanges(rows, columns, tokens, [
      { type: 'DELETE', keyToken: 'tok-1' }
    ])).toBe(rows);
    expect(applyCommittedChanges(rows, columns, tokens, [])).toBe(rows);
  });

  it('写回后的值就是下一次编辑的原值，乐观校验才不会用过期数据', () => {
    const committed = applyCommittedChanges(rows, columns, tokens, [
      { type: 'UPDATE', keyToken: 'tok-1', values: { CITY: '广州' } }
    ]);
    const state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'CITY', '深圳', '广州');
    const changes = buildResultChanges(state, committed, columns, tokens);

    expect(changes[0].originalValues).toEqual({ CITY: '广州' });
  });
});
