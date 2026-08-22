import { describe, expect, it } from 'vitest';
import {
  countSqlLines,
  EDITOR_RATIO_WITH_RESULTS_MAX,
  EDITOR_RATIO_WITH_RESULTS_MIN,
  EDITOR_RATIO_WITHOUT_RESULTS,
  resolveEditorSplitRatio
} from './editorSplit';

describe('countSqlLines', () => {
  it('counts a single line, an empty string and trailing newlines', () => {
    expect(countSqlLines('')).toBe(1);
    expect(countSqlLines('select 1')).toBe(1);
    expect(countSqlLines('select 1\nfrom t')).toBe(2);
    expect(countSqlLines('select 1\n')).toBe(2);
  });
});

describe('resolveEditorSplitRatio', () => {
  const base = { touched: false, storedRatio: 0.52, hasResults: true, sql: 'select 1', containerHeight: 800 };

  it('hands the whole decision back to the user once they drag the splitter', () => {
    expect(resolveEditorSplitRatio({ ...base, touched: true, storedRatio: 0.7 })).toBe(0.7);
    expect(resolveEditorSplitRatio({ ...base, touched: true, storedRatio: 0.7, hasResults: false })).toBe(0.7);
  });

  it('gives the editor most of the space while there is nothing to show below', () => {
    expect(resolveEditorSplitRatio({ ...base, hasResults: false })).toBe(EDITOR_RATIO_WITHOUT_RESULTS);
  });

  it('shrinks to the floor for a one-line query so results get the room', () => {
    expect(resolveEditorSplitRatio(base)).toBe(EDITOR_RATIO_WITH_RESULTS_MIN);
  });

  it('grows with the statement but never takes more than the cap', () => {
    const tall = resolveEditorSplitRatio({ ...base, sql: Array(12).fill('select 1').join('\n') });
    expect(tall).toBeGreaterThan(EDITOR_RATIO_WITH_RESULTS_MIN);
    expect(tall).toBeLessThanOrEqual(EDITOR_RATIO_WITH_RESULTS_MAX);

    const huge = resolveEditorSplitRatio({ ...base, sql: Array(200).fill('select 1').join('\n') });
    expect(huge).toBe(EDITOR_RATIO_WITH_RESULTS_MAX);
  });

  it('falls back to the floor before the container has been measured', () => {
    expect(resolveEditorSplitRatio({ ...base, containerHeight: undefined })).toBe(EDITOR_RATIO_WITH_RESULTS_MIN);
    expect(resolveEditorSplitRatio({ ...base, containerHeight: 0 })).toBe(EDITOR_RATIO_WITH_RESULTS_MIN);
  });

  it('never returns a ratio outside the usable range', () => {
    for (const sql of ['', 'select 1', Array(500).fill('x').join('\n')]) {
      for (const containerHeight of [0, 1, 400, 2000]) {
        const ratio = resolveEditorSplitRatio({ ...base, sql, containerHeight });
        expect(ratio).toBeGreaterThanOrEqual(EDITOR_RATIO_WITH_RESULTS_MIN);
        expect(ratio).toBeLessThanOrEqual(EDITOR_RATIO_WITH_RESULTS_MAX);
      }
    }
  });
});
