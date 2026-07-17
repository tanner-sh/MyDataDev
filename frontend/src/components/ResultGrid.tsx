import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Button, Empty, Input, InputNumber, Select, Space, Spin, Table, Tooltip, Typography } from 'antd';
import { FilterFilled, LeftOutlined, RightOutlined, SearchOutlined, VerticalLeftOutlined } from '@ant-design/icons';
import type { ColumnsType, TableRef } from 'antd/es/table';
import type { FilterDropdownProps, SorterResult } from 'antd/es/table/interface';
import { useTableViewportHeight } from '../hooks/useTableViewportHeight';
import type { ResultRow, SqlPageNavigation, SqlResult } from '../types';
import { firstSqlPage, nextSqlPage, previousSqlPage, resizedSqlPage, sqlResultRangeLabel } from '../sqlResultPaging';
import { compareResultValues, filterResultRows, type ResultColumnFilter, type ResultColumnFilters, type ResultFilterOperator } from '../resultGridData';

const { Text } = Typography;

export const ResultGrid = memo(function ResultGrid({ result, fill = false, active = true, pagingLoading = false, pagingEnabled = true, onPageChange }: {
  result: SqlResult | null;
  fill?: boolean;
  active?: boolean;
  pagingLoading?: boolean;
  pagingEnabled?: boolean;
  onPageChange?: (navigation: SqlPageNavigation) => void;
}) {
  const [pageSizeDraft, setPageSizeDraft] = useState(500);
  const [columnFilters, setColumnFilters] = useState<ResultColumnFilters>({});
  const [sortState, setSortState] = useState<{ key: string; order: 'ascend' | 'descend' }>();
  const tableRef = useRef<TableRef>(null);
  const lastScrolledRowsRef = useRef<SqlResult['rows'] | null>(null);
  const { viewportRef, scrollY } = useTableViewportHeight({ enabled: Boolean(result?.resultSet), active });
  const emptyClassName = fill ? 'empty-state empty-state-fill' : 'empty-state';
  const rowCount = result?.resultSet ? result.rows.length : 0;
  const rowOffset = result?.page?.offset || 0;

  useEffect(() => {
    if (result?.page?.requestedPageSize) setPageSizeDraft(result.page.requestedPageSize);
  }, [result?.page?.requestedPageSize]);

  useEffect(() => {
    setColumnFilters({});
    setSortState(undefined);
  }, [result?.rows]);

  useLayoutEffect(() => {
    if (!result?.resultSet || scrollY === undefined || !tableRef.current) return;
    if (lastScrolledRowsRef.current === result.rows) return;
    tableRef.current.scrollTo({ top: 0 });
    lastScrolledRowsRef.current = result.rows;
  }, [result?.resultSet, result?.rows, scrollY]);

  const columns = useMemo<ColumnsType<ResultRow>>(() => {
    if (!result?.resultSet) return [];
    return [
      {
        title: '序号',
        key: '__index',
        width: 70,
        fixed: 'left',
        shouldCellUpdate: (record, previous) => record !== previous,
        render: (_value, _row, index) => rowOffset + index + 1
      },
      ...result.columns.map((column, columnIndex) => ({
        title: <span title={column.typeName}>{column.label}</span>,
        key: column.key,
        width: Math.max(140, Math.min(280, column.label.length * 14 + 48)),
        ellipsis: true,
        sorter: (left: ResultRow, right: ResultRow) => compareResultValues(left.values[columnIndex], right.values[columnIndex]),
        sortOrder: sortState?.key === column.key ? sortState.order : null,
        filteredValue: columnFilters[column.key] ? ['active'] : null,
        filterIcon: (filtered: boolean) => <FilterFilled className={filtered ? 'result-filter-icon-active' : undefined} />,
        filterDropdown: ({ confirm, close }: FilterDropdownProps) => (
          <ResultFilterDropdown
            condition={columnFilters[column.key]}
            onApply={(condition) => {
              setColumnFilters((current) => ({ ...current, [column.key]: condition }));
              confirm({ closeDropdown: true });
            }}
            onReset={() => {
              setColumnFilters((current) => {
                const next = { ...current };
                delete next[column.key];
                return next;
              });
              close();
            }}
          />
        ),
        onFilter: () => true,
        shouldCellUpdate: (record: ResultRow, previous: ResultRow) => record !== previous,
        render: (_value: unknown, row: ResultRow) => renderCellValue(row.values[columnIndex])
      }))
    ];
  }, [columnFilters, result?.columns, result?.resultSet, rowOffset, sortState]);

  const rows = useMemo<ResultRow[]>(() => {
    if (!result?.resultSet) return [];
    return filterResultRows(result.rows, result.columns, columnFilters).map((values) => ({
      values,
      key: String(rowOffset + result.rows.indexOf(values))
    }));
  }, [columnFilters, result?.columns, result?.resultSet, result?.rows, rowOffset]);

  if (!result) return <Empty className={emptyClassName} description="执行查询后查看结果。" />;
  if (!result.resultSet) return <Empty className={emptyClassName} description={`影响 ${result.affectedRows} 行。`} />;

  return (
    <div className={`result-grid-shell${fill ? ' result-grid-shell-fill' : ''}`}>
      <div ref={viewportRef} className="data-grid-viewport">
        {scrollY === undefined ? (
          <div className="table-viewport-loading"><Spin size="small" /><Text type="secondary">正在准备查询结果…</Text></div>
        ) : (
          <Table<ResultRow>
            ref={tableRef}
            size="small"
            className="data-grid data-grid-fill result-grid"
            columns={columns}
            dataSource={rows}
            pagination={false}
            loading={pagingLoading}
            virtual
            scroll={{ x: Math.max(800, result.columns.length * 180 + 70), y: scrollY }}
            onChange={(_pagination, _filters, sorter) => {
              const current = (Array.isArray(sorter) ? sorter[0] : sorter) as SorterResult<ResultRow>;
              const key = typeof current?.columnKey === 'string' ? current.columnKey : undefined;
              setSortState(key && current.order ? { key, order: current.order } : undefined);
            }}
          />
        )}
      </div>
      <div className="grid-pagination result-grid-pagination">
        {result.page ? (
          <>
            <Text type="secondary">
              {rows.length === rowCount ? `本批 ${rowCount} 行` : `筛选后 ${rows.length} / 本批 ${rowCount} 行`} · {result.elapsedMs}ms
              {result.page.effectivePageSize < result.page.requestedPageSize ? ` · 服务端单批上限 ${result.page.effectivePageSize}` : ''}
            </Text>
            <Space size={4} className="result-range-navigation">
              <Tooltip title="第一批"><span><Button size="small" icon={<VerticalLeftOutlined />} disabled={pagingLoading || !pagingEnabled || result.page.offset === 0} onClick={() => onPageChange?.(firstSqlPage(result.page!))} /></span></Tooltip>
              <Tooltip title="上一批"><span><Button size="small" icon={<LeftOutlined />} disabled={pagingLoading || !pagingEnabled || !(result.page.previousOffsets?.length)} onClick={() => {
                const navigation = previousSqlPage(result.page!);
                if (navigation) onPageChange?.(navigation);
              }} /></span></Tooltip>
              <Text className="result-range-label">{sqlResultRangeLabel(result.page, rowCount)}</Text>
              <Tooltip title="下一批"><span><Button size="small" icon={<RightOutlined />} disabled={pagingLoading || !pagingEnabled || !result.page.hasMore} onClick={() => {
                const navigation = nextSqlPage(result.page!, rowCount);
                if (navigation) onPageChange?.(navigation);
              }} /></span></Tooltip>
            </Space>
            <Space.Compact size="small">
              <Button size="small" disabled>单页行数</Button>
              <InputNumber
                size="small"
                min={1}
                step={100}
                value={pageSizeDraft}
                disabled={pagingLoading || !pagingEnabled}
                aria-label="SQL 结果单页行数"
                onChange={(value) => setPageSizeDraft(value || 500)}
                onPressEnter={(event) => event.currentTarget.blur()}
                onBlur={() => {
                  const nextSize = Math.max(1, Math.round(pageSizeDraft || 500));
                  if (nextSize !== result.page?.requestedPageSize) onPageChange?.(resizedSqlPage(nextSize));
                }}
              />
            </Space.Compact>
          </>
        ) : (
          <Text type="secondary">{rows.length === rowCount ? `共 ${rowCount} 行` : `筛选后 ${rows.length} / 共 ${rowCount} 行`} · {result.elapsedMs}ms（当前结果不支持翻页）</Text>
        )}
      </div>
    </div>
  );
});

const FILTER_OPTIONS: Array<{ value: ResultFilterOperator; label: string }> = [
  { value: 'contains', label: '包含' },
  { value: 'notContains', label: '不包含' },
  { value: 'equals', label: '等于' },
  { value: 'notEquals', label: '不等于' },
  { value: 'empty', label: '为空' },
  { value: 'notEmpty', label: '非空' }
];

function ResultFilterDropdown({ condition, onApply, onReset }: {
  condition?: ResultColumnFilter;
  onApply: (condition: ResultColumnFilter) => void;
  onReset: () => void;
}) {
  const [operator, setOperator] = useState<ResultFilterOperator>(condition?.operator || 'contains');
  const [value, setValue] = useState(condition?.value || '');
  const valueDisabled = operator === 'empty' || operator === 'notEmpty';

  useEffect(() => {
    setOperator(condition?.operator || 'contains');
    setValue(condition?.value || '');
  }, [condition]);

  return (
    <div className="result-filter-dropdown" onKeyDown={(event) => event.stopPropagation()}>
      <Select value={operator} options={FILTER_OPTIONS} onChange={setOperator} />
      <Input
        autoFocus
        allowClear
        disabled={valueDisabled}
        placeholder={valueDisabled ? '无需输入筛选值' : '输入筛选值'}
        value={value}
        onChange={(event) => setValue(event.target.value)}
        onPressEnter={() => onApply({ operator, value })}
      />
      <Space className="result-filter-actions">
        <Button size="small" onClick={onReset}>重置</Button>
        <Button size="small" type="primary" icon={<SearchOutlined />} onClick={() => onApply({ operator, value })}>筛选</Button>
      </Space>
    </div>
  );
}

function renderCellValue(value: unknown) {
  if (value == null) return <span className="cell-null">NULL</span>;
  if (value === '') return <span className="cell-empty">空字符串</span>;
  const text = String(value);
  const title = text.length > 2_000 ? `${text.slice(0, 2_000)}…` : text;
  return <span className="grid-cell-value" title={title}>{text}</span>;
}
