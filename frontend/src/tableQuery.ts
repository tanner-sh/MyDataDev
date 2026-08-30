export type TableFilterOperator = 'EQ' | 'NE' | 'CONTAINS' | 'NOT_CONTAINS' | 'STARTS_WITH' | 'ENDS_WITH'
  | 'GT' | 'GTE' | 'LT' | 'LTE' | 'BETWEEN' | 'IN' | 'IS_NULL' | 'IS_NOT_NULL';

export type TableFilterRule = {
  column: string;
  operator: TableFilterOperator;
  value?: string;
  secondValue?: string;
  values?: string[];
};

export type TableSortRule = { column: string; direction: 'ASC' | 'DESC' };
export type TableQuery = { filters: TableFilterRule[]; sorts: TableSortRule[]; filterLogic: 'AND' | 'OR' };

export const EMPTY_TABLE_QUERY: TableQuery = { filters: [], sorts: [], filterLogic: 'AND' };

export function tableQueryRuleCount(query: TableQuery) {
  return query.filters.length + query.sorts.length;
}
