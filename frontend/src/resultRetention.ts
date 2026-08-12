import type { SqlStatementResult, SqlTab } from './types';

export const MAX_RETAINED_RESULT_UNITS = 400_000;

function calculateResultUnits(results: SqlStatementResult[]) {
  return results.reduce((total, statement) => {
    const result = statement.result;
    if (!result?.resultSet) return total;
    let units = result.rows.length * Math.max(result.columns.length, 1);
    for (const row of result.rows) {
      for (const value of row) {
        if (typeof value === 'string') units += Math.ceil(value.length / 100);
      }
    }
    return total + units;
  }, 0);
}

export function createResultUnitCounter(
  calculate: (results: SqlStatementResult[]) => number = calculateResultUnits
) {
  const cache = new WeakMap<SqlStatementResult[], number>();
  return (results: SqlStatementResult[]) => {
    const cached = cache.get(results);
    if (cached !== undefined) return cached;
    const units = calculate(results);
    cache.set(results, units);
    return units;
  };
}

const resultUnits = createResultUnitCounter();

export function enforceResultBudget(
  tabs: SqlTab[],
  protectedTabId: string,
  maxRetainedUnits = MAX_RETAINED_RESULT_UNITS
) {
  let retained = 0;
  const keep = new Set<string>();
  const candidates = [
    ...tabs.filter((tab) => tab.id === protectedTabId),
    ...tabs.filter((tab) => tab.id !== protectedTabId).reverse()
  ];
  for (const tab of candidates) {
    const units = resultUnits(tab.results);
    if (tab.id === protectedTabId || retained + units <= maxRetainedUnits) {
      keep.add(tab.id);
      retained += units;
    }
  }
  return tabs.map((tab) => {
    if (keep.has(tab.id) || tab.results.length === 0) return tab;
    return {
      ...tab,
      results: [],
      activeResultKey: undefined,
      message: '为控制浏览器内存，已释放该标签页的旧结果；SQL 文本仍保留，可重新执行。'
    };
  });
}
