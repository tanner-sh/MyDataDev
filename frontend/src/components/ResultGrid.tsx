import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent, Ref } from 'react';
import { Button, Dropdown, Empty, Input, InputNumber, Modal, Select, Space, Spin, Table, Tag, Tooltip, Typography, message } from 'antd';
import { CheckOutlined, CopyOutlined, DownOutlined, DownloadOutlined, FilterFilled, LeftOutlined, RightOutlined, SearchOutlined, VerticalLeftOutlined } from '@ant-design/icons';
import type { ColumnsType, TableProps, TableRef } from 'antd/es/table';
import type { FilterDropdownProps, SorterResult } from 'antd/es/table/interface';
import { useTableViewportHeight } from '../hooks/useTableViewportHeight';
import type { ExportFormat, ResultCopyFormat, ResultRow, SqlPageNavigation, SqlResult } from '../types';
import { firstSqlPage, nextSqlPage, previousSqlPage, resizedSqlPage, sqlResultRangeLabel } from '../sqlResultPaging';
import { filterResultRows, MAX_RESULT_COLUMN_WIDTH, MIN_RESULT_COLUMN_WIDTH, sortResultRows, suggestedResultColumnWidth, type ResultColumnFilter, type ResultColumnFilters, type ResultFilterOperator } from '../resultGridData';
import { downloadBlob } from '../api';
import { timestamp } from '../utils';
import { inferSqlTargetParts, parseQualifiedTableName, readResultCopyFormat, serializeCopiedRows, serializeQueryResult, writeResultCopyFormat } from '../queryResultExport';
import { replaceResultRowSelection, resolveResultGridKeyboardAction, updateResultRowSelection, type ResultRowSelection } from '../resultRowSelection';

const { Text } = Typography;
type ResultRowClassName = (record: ResultRow, index: number, indent: number) => string;
const RESULT_SELECTION_COLUMN_WIDTH = 38;

export const ResultGrid = memo(function ResultGrid({ result, fill = false, active = true, pagingLoading = false, pagingEnabled = true, dbType, sourceSql, onPageChange }: {
  result: SqlResult | null;
  fill?: boolean;
  active?: boolean;
  pagingLoading?: boolean;
  pagingEnabled?: boolean;
  dbType?: string;
  sourceSql?: string;
  onPageChange?: (navigation: SqlPageNavigation) => void;
}) {
  const [pageSizeDraft, setPageSizeDraft] = useState(500);
  const [columnFilters, setColumnFilters] = useState<ResultColumnFilters>({});
  const [sortState, setSortState] = useState<{ key: string; order: 'ascend' | 'descend' }>();
  const [visibleColumnKeys, setVisibleColumnKeys] = useState<string[]>([]);
  const [columnWidths, setColumnWidths] = useState<Record<string, number>>({});
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [selectionAnchor, setSelectionAnchor] = useState<string>();
  const [copyFormat, setCopyFormat] = useState<ResultCopyFormat>(() => readResultCopyFormat());
  const [manualTargetParts, setManualTargetParts] = useState<string[]>();
  const [targetDialogOpen, setTargetDialogOpen] = useState(false);
  const [targetDraft, setTargetDraft] = useState('');
  const [messageApi, messageContextHolder] = message.useMessage();
  const tableRef = useRef<TableRef>(null);
  const gridShellRef = useRef<HTMLDivElement>(null);
  const pendingTargetActionRef = useRef<((parts: string[]) => void) | null>(null);
  const lastDisplayedRowsRef = useRef<ResultRow[] | null>(null);
  const selectionStateRef = useRef<{
    selected: string[];
    selectedSet: ReadonlySet<string>;
    anchor?: string;
    displayed: string[];
    displayedIndex: ReadonlyMap<string, number>;
  }>({ selected: [], selectedSet: new Set(), displayed: [], displayedIndex: new Map() });
  const copySelectedRowsRef = useRef<() => void>(() => undefined);
  const resultRowCheckboxChangeRef = useRef<(rowKey: string, checked: boolean) => void>(() => undefined);
  const resultSelectAllChangeRef = useRef<(checked: boolean) => void>(() => undefined);
  const { viewportRef, scrollY } = useTableViewportHeight({ enabled: Boolean(result?.resultSet), active });
  const emptyClassName = fill ? 'empty-state empty-state-fill' : 'empty-state';
  const rowCount = result?.resultSet ? result.rows.length : 0;
  const rowOffset = result?.page?.offset || 0;
  const columnSignature = result?.resultSet ? result.columns.map((column) => `${column.key}:${column.label}:${column.typeName}`).join('|') : '';

  useEffect(() => {
    if (result?.page?.requestedPageSize) setPageSizeDraft(result.page.requestedPageSize);
  }, [result?.page?.requestedPageSize]);

  useEffect(() => {
    setColumnFilters({});
    setSortState(undefined);
    setSelectedRowKeys([]);
    setSelectionAnchor(undefined);
    setManualTargetParts(undefined);
    setTargetDialogOpen(false);
    pendingTargetActionRef.current = null;
  }, [result?.rows]);

  useEffect(() => {
    if (!result?.resultSet) {
      setVisibleColumnKeys([]);
      return;
    }
    setVisibleColumnKeys(result.columns.slice(0, 50).map((column) => column.key));
  }, [result?.columns, result?.resultSet]);

  useEffect(() => {
    if (!result?.resultSet) {
      setColumnWidths({});
      return;
    }
    setColumnWidths(Object.fromEntries(result.columns.map((column, index) => [
      column.key,
      suggestedResultColumnWidth(column, index, result.rows)
    ])));
  // Widths intentionally survive row/page/filter/sort changes and reset only for a new SQL or column structure.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceSql, columnSignature, result?.resultSet]);

  const resizeColumn = useCallback((key: string, width: number) => {
    setColumnWidths((current) => ({
      ...current,
      [key]: Math.max(MIN_RESULT_COLUMN_WIDTH, Math.min(MAX_RESULT_COLUMN_WIDTH, Math.round(width)))
    }));
  }, []);

  const beginColumnResize = useCallback((event: ReactMouseEvent, key: string, initialWidth: number) => {
    event.preventDefault();
    event.stopPropagation();
    const startX = event.clientX;
    const handleMove = (moveEvent: MouseEvent) => resizeColumn(key, initialWidth + moveEvent.clientX - startX);
    const handleUp = () => {
      document.removeEventListener('mousemove', handleMove);
      document.removeEventListener('mouseup', handleUp);
      document.body.classList.remove('is-resizing-column');
    };
    document.body.classList.add('is-resizing-column');
    document.addEventListener('mousemove', handleMove);
    document.addEventListener('mouseup', handleUp);
  }, [resizeColumn]);

  const columns = useMemo<ColumnsType<ResultRow>>(() => {
    if (!result?.resultSet) return [];
    const visible = new Set(visibleColumnKeys);
    return [
      {
        title: (
          <span className="result-selection-checkbox-wrap">
            <button
              type="button"
              role="checkbox"
              className="result-selection-checkbox result-selection-checkbox-all"
              aria-label="全选当前结果行"
              aria-checked="false"
              onClick={(event) => {
                event.stopPropagation();
                resultSelectAllChangeRef.current(event.currentTarget.getAttribute('aria-checked') !== 'true');
              }}
            />
          </span>
        ),
        key: '__selection',
        width: RESULT_SELECTION_COLUMN_WIDTH,
        fixed: 'left',
        className: 'result-selection-column',
        shouldCellUpdate: (record: ResultRow, previous: ResultRow) => record !== previous,
        render: (_value: unknown, row: ResultRow) => (
          <span className="result-selection-checkbox-wrap">
            <button
              type="button"
              role="checkbox"
              className="result-selection-checkbox result-selection-checkbox-row"
              aria-label={`选择第 ${Number(row.key) + 1} 行`}
              aria-checked={selectionStateRef.current.selectedSet.has(row.key)}
              data-result-selection-key={row.key}
              onClick={(event) => {
                event.stopPropagation();
                resultRowCheckboxChangeRef.current(row.key, event.currentTarget.getAttribute('aria-checked') !== 'true');
              }}
            />
          </span>
        )
      },
      {
        title: '序号',
        key: '__index',
        width: 58,
        fixed: 'left',
        className: 'result-index-column',
        shouldCellUpdate: (record, previous) => record !== previous,
        render: (_value, _row, index) => rowOffset + index + 1
      },
      ...result.columns.map((column, columnIndex) => ({ column, columnIndex }))
        .filter(({ column }) => visible.has(column.key))
        .map(({ column, columnIndex }) => {
          const width = columnWidths[column.key] || suggestedResultColumnWidth(column, columnIndex, result.rows);
          return {
            title: (
              <span className="resizable-column-title" title={`${column.label} · ${column.typeName}`}>
                <span>{column.label}</span>
                <span
                  className="column-resize-handle"
                  role="separator"
                  aria-label={`调整 ${column.label} 列宽`}
                  aria-orientation="vertical"
                  tabIndex={0}
                  onMouseDown={(event) => beginColumnResize(event, column.key, width)}
                  onDoubleClick={(event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    resizeColumn(column.key, suggestedResultColumnWidth(column, columnIndex, result.rows));
                  }}
                  onKeyDown={(event) => {
                    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
                    event.preventDefault();
                    event.stopPropagation();
                    resizeColumn(column.key, width + (event.key === 'ArrowRight' ? 12 : -12));
                  }}
                />
              </span>
            ),
            key: column.key,
            width,
            ellipsis: true,
            sorter: true,
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
          };
        })
    ];
  }, [beginColumnResize, columnFilters, columnWidths, resizeColumn, result?.columns, result?.resultSet, result?.rows, rowOffset, sortState, visibleColumnKeys]);

  const tableScrollWidth = useMemo(() => {
    if (!result?.resultSet) return 800;
    const visible = new Set(visibleColumnKeys);
    return Math.max(800, RESULT_SELECTION_COLUMN_WIDTH + 58 + result.columns.reduce((total, column, index) => (
      visible.has(column.key) ? total + (columnWidths[column.key] || suggestedResultColumnWidth(column, index, result.rows)) : total
    ), 0));
  }, [columnWidths, result?.columns, result?.resultSet, result?.rows, visibleColumnKeys]);

  const rows = useMemo<ResultRow[]>(() => {
    if (!result?.resultSet) return [];
    const originalIndexes = new Map(result.rows.map((values, index) => [values, index]));
    return sortResultRows(filterResultRows(result.rows, result.columns, columnFilters), result.columns, sortState).map((values) => ({
      values,
      key: String(rowOffset + (originalIndexes.get(values) ?? 0))
    }));
  }, [columnFilters, result?.columns, result?.resultSet, result?.rows, rowOffset, sortState]);

  const displayedRowKeys = useMemo(() => rows.map((row) => row.key), [rows]);
  const displayedRowIndex = useMemo(() => new Map(displayedRowKeys.map((key, index) => [key, index])), [displayedRowKeys]);
  const displayedRowKeySet = useMemo(() => new Set(displayedRowKeys), [displayedRowKeys]);
  const selectedRowKeySet = useMemo(() => new Set(selectedRowKeys), [selectedRowKeys]);

  useLayoutEffect(() => {
    if (!result?.resultSet || scrollY === undefined || !tableRef.current) return;
    if (lastDisplayedRowsRef.current === rows) return;
    tableRef.current.scrollTo({ top: 0 });
    lastDisplayedRowsRef.current = rows;
  }, [result?.resultSet, rows, scrollY]);

  selectionStateRef.current = {
    selected: selectedRowKeys,
    selectedSet: selectedRowKeySet,
    anchor: selectionAnchor,
    displayed: displayedRowKeys,
    displayedIndex: displayedRowIndex
  };

  useEffect(() => {
    setSelectedRowKeys((current) => {
      const next = current.filter((key) => displayedRowKeySet.has(key));
      return next.length === current.length ? current : next;
    });
    setSelectionAnchor((current) => current && !displayedRowKeySet.has(current) ? undefined : current);
  }, [displayedRowKeySet]);

  const selectedRows = useMemo(
    () => rows.filter((row) => selectedRowKeySet.has(row.key)),
    [rows, selectedRowKeySet]
  );

  const commitResultRowSelection = useCallback((next: ResultRowSelection) => {
    const current = selectionStateRef.current;
    selectionStateRef.current = {
      ...current,
      selected: next.selected,
      selectedSet: new Set(next.selected),
      anchor: next.anchor
    };
    setSelectedRowKeys(next.selected);
    setSelectionAnchor(next.anchor);
  }, []);

  const selectResultRow = useCallback((rowKey: string, event: ReactMouseEvent<HTMLElement>) => {
    if (isInteractiveTarget(event.target)) return;
    const current = selectionStateRef.current;
    const next = updateResultRowSelection({
      current: current.selected,
      clicked: rowKey,
      displayed: current.displayed,
      displayedIndex: current.displayedIndex,
      anchor: current.anchor,
      toggle: event.ctrlKey || event.metaKey,
      range: event.shiftKey
    });
    commitResultRowSelection(next);
    gridShellRef.current?.focus({ preventScroll: true });
  }, [commitResultRowSelection]);

  const setResultRowChecked = useCallback((rowKey: string, checked: boolean) => {
    const current = selectionStateRef.current;
    if (current.selectedSet.has(rowKey) === checked) return;
    const requested = new Set(current.selected);
    if (checked) requested.add(rowKey);
    else requested.delete(rowKey);
    commitResultRowSelection(replaceResultRowSelection(
      current.displayed,
      requested,
      requested.size > 0 ? rowKey : undefined
    ));
  }, [commitResultRowSelection]);

  const setAllResultRowsChecked = useCallback((checked: boolean) => {
    const current = selectionStateRef.current;
    if (checked && current.displayed.length === current.selected.length) return;
    if (!checked && current.selected.length === 0) return;
    commitResultRowSelection(replaceResultRowSelection(
      current.displayed,
      checked ? current.displayed : []
    ));
  }, [commitResultRowSelection]);

  resultRowCheckboxChangeRef.current = setResultRowChecked;
  resultSelectAllChangeRef.current = setAllResultRowsChecked;

  const selectAllDisplayedRows = useCallback(() => {
    setAllResultRowsChecked(true);
  }, [setAllResultRowsChecked]);

  const clearSelectedRows = useCallback(() => {
    setAllResultRowsChecked(false);
  }, [setAllResultRowsChecked]);

  const resultRowClassName = useCallback<ResultRowClassName>(
    (row) => selectionStateRef.current.selectedSet.has(row.key) ? 'result-row-selected' : '',
    []
  );
  const resultOnRow = useCallback<NonNullable<TableProps<ResultRow>['onRow']>>((row) => ({
    'aria-selected': selectionStateRef.current.selectedSet.has(row.key),
    onClick: (event) => selectResultRow(row.key, event)
  }), [selectResultRow]);
  const resultTableChange = useCallback<NonNullable<TableProps<ResultRow>['onChange']>>((_pagination, _filters, sorter) => {
    const current = (Array.isArray(sorter) ? sorter[0] : sorter) as SorterResult<ResultRow>;
    const key = typeof current?.columnKey === 'string' ? current.columnKey : undefined;
    setSortState(key && current.order ? { key, order: current.order } : undefined);
  }, []);

  useLayoutEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    const current = selectionStateRef.current;
    viewport.querySelectorAll<HTMLElement>('.ant-table-row[data-row-key]').forEach((row) => {
      const selected = current.selectedSet.has(row.dataset.rowKey || '');
      row.classList.toggle('result-row-selected', selected);
      row.setAttribute('aria-selected', String(selected));
      row.querySelectorAll<HTMLElement>('.result-selection-checkbox-row').forEach((checkbox) => {
        checkbox.setAttribute('aria-checked', String(selected));
      });
    });
    const selectedCount = current.displayed.reduce((count, key) => count + (current.selectedSet.has(key) ? 1 : 0), 0);
    const allSelected = current.displayed.length > 0 && selectedCount === current.displayed.length;
    const indeterminate = selectedCount > 0 && selectedCount < current.displayed.length;
    viewport.querySelectorAll<HTMLElement>('.result-selection-checkbox-all').forEach((selectAll) => {
      selectAll.classList.toggle('is-checked', allSelected);
      selectAll.classList.toggle('is-indeterminate', indeterminate);
      selectAll.setAttribute('aria-checked', indeterminate ? 'mixed' : String(allSelected));
    });
  }, [rows, scrollY, selectedRowKeySet, viewportRef]);

  const inferredTargetParts = useMemo(
    () => inferSqlTargetParts(sourceSql, result?.sourceTable),
    [result?.sourceTable, sourceSql]
  );

  const requireTarget = (action: (parts: string[]) => void) => {
    const target = manualTargetParts || inferredTargetParts;
    if (target) {
      action(target);
      return;
    }
    pendingTargetActionRef.current = action;
    setTargetDraft('');
    setTargetDialogOpen(true);
  };

  const exportLoadedRows = (format: ExportFormat) => {
    if (!result?.resultSet) return;
    const scopedRows = (selectedRows.length > 0 ? selectedRows : rows).map((row) => row.values);
    const perform = (targetTableParts?: string[]) => {
      try {
        const content = serializeQueryResult(format, result.columns, scopedRows, {
          dbType,
          targetTableParts,
          truncated: Boolean(result.truncated),
          maxRows: result.maxRows
        });
        const mime = format === 'json' ? 'application/json' : format === 'xml' ? 'application/xml' : 'text/plain';
        downloadBlob(new Blob([content], { type: `${mime};charset=utf-8` }), `query-result-${timestamp()}.${format}`);
        void messageApi.success(`已导出当前结果中的 ${scopedRows.length} 行`);
      } catch (error) {
        void messageApi.error((error as Error).message);
      }
    };
    if (format === 'sql') requireTarget((parts) => perform(parts));
    else perform();
  };

  const copySelectedRows = () => {
    if (!result?.resultSet || selectedRows.length === 0) {
      void messageApi.info('请先选择要复制的结果行');
      return;
    }
    const perform = async (targetTableParts?: string[]) => {
      try {
        const content = serializeCopiedRows(copyFormat, result.columns, selectedRows.map((row) => row.values), { dbType, targetTableParts });
        await copyText(content);
        void messageApi.success(`已复制 ${selectedRows.length} 行（${copyFormat === 'sql' ? 'SQL 插入' : '管道分隔'}）`);
      } catch (error) {
        void messageApi.error(`复制失败：${(error as Error).message}`);
      }
    };
    if (copyFormat === 'sql') requireTarget((parts) => void perform(parts));
    else void perform();
  };

  copySelectedRowsRef.current = copySelectedRows;
  const requestCopySelectedRows = useCallback(() => copySelectedRowsRef.current(), []);

  if (!result) return <Empty className={emptyClassName} description="执行查询后查看结果。" />;
  if (!result.resultSet) return <Empty className={emptyClassName} description={`影响 ${result.affectedRows} 行。`} />;

  return (
    <div
      ref={gridShellRef}
      className={`result-grid-shell${fill ? ' result-grid-shell-fill' : ''}`}
      tabIndex={0}
      onKeyDown={(event) => {
        const action = resolveResultGridKeyboardAction({
          key: event.key,
          ctrlKey: event.ctrlKey,
          metaKey: event.metaKey,
          textEntry: isTextEntryTarget(event.target)
        });
        if (action === 'select-all') {
          event.preventDefault();
          event.stopPropagation();
          selectAllDisplayedRows();
          return;
        }
        if (action === 'clear-selection') {
          event.preventDefault();
          event.stopPropagation();
          clearSelectedRows();
          return;
        }
        if (action === 'copy') {
          event.preventDefault();
          event.stopPropagation();
          requestCopySelectedRows();
        }
      }}
    >
      {messageContextHolder}
      <div className="result-grid-toolbar">
        <div className="result-local-actions">
          <Tooltip title={selectedRows.length > 0 ? '仅导出当前批次中已选择的行' : '仅导出当前已加载批次；如需完整数据，请使用 SQL 工作台“重新查询并导出”'}>
            <Dropdown trigger={['click']} menu={{ items: LOCAL_EXPORT_ITEMS, onClick: ({ key }) => exportLoadedRows(key as ExportFormat) }}>
              <Button size="small" icon={<DownloadOutlined />} aria-label={selectedRows.length > 0 ? `导出已选择的 ${selectedRows.length} 行` : `导出当前批次 ${rows.length} 行`}>导出{selectedRows.length > 0 ? `已选 ${selectedRows.length} 行` : `本批 ${rows.length} 行`} <DownOutlined /></Button>
            </Dropdown>
          </Tooltip>
          <Space.Compact size="small">
            <Button size="small" icon={<CopyOutlined />} aria-label={selectedRows.length > 0 ? `复制已选择的 ${selectedRows.length} 行` : '复制已选择的结果行'} onClick={requestCopySelectedRows}>复制{selectedRows.length > 0 ? ` ${selectedRows.length} 行` : ''}</Button>
            <Dropdown trigger={['click']} menu={{
              selectable: true,
              selectedKeys: [copyFormat],
              items: [
                { key: 'pipe', label: '管道分隔', icon: copyFormat === 'pipe' ? <CheckOutlined /> : undefined },
                { key: 'sql', label: 'SQL 插入', icon: copyFormat === 'sql' ? <CheckOutlined /> : undefined }
              ],
              onClick: ({ key }) => {
                const next = key as ResultCopyFormat;
                setCopyFormat(next);
                writeResultCopyFormat(next);
              }
            }}>
              <Button size="small" aria-label="选择复制格式" icon={<DownOutlined />} />
            </Dropdown>
          </Space.Compact>
          {selectedRowKeys.length > 0 && <Tag className="result-selection-tag">已选 {selectedRowKeys.length} 行</Tag>}
          <Tag className="result-scope-tag" color="blue">本地操作 · 当前批次</Tag>
          {result.columns.length > 20 && (
            <Select
              mode="multiple"
              size="small"
              maxTagCount="responsive"
              className="result-column-selector"
              aria-label="选择结果列"
              value={visibleColumnKeys}
              options={result.columns.map((column) => ({ value: column.key, label: column.label }))}
              onChange={(keys) => setVisibleColumnKeys(keys.length > 0 ? keys : [result.columns[0].key])}
            />
          )}
        </div>
        <Text type="secondary" className="result-grid-toolbar-hint">单击选择 · Shift 连选 · 结果区聚焦时 Ctrl/Cmd+C 复制 · Ctrl/Cmd+A 全选 · Esc 清空</Text>
      </div>
      <div ref={viewportRef} className="data-grid-viewport">
        {scrollY === undefined ? (
          <div className="table-viewport-loading"><Spin size="small" /><Text type="secondary">正在准备查询结果…</Text></div>
        ) : (
          <MemoizedResultTable
            tableRef={tableRef}
            columns={columns}
            rows={rows}
            pagingLoading={pagingLoading}
            scrollX={tableScrollWidth}
            scrollY={scrollY}
            rowClassName={resultRowClassName}
            onRow={resultOnRow}
            onChange={resultTableChange}
          />
        )}
      </div>
      <div className="grid-pagination result-grid-pagination">
        <div className="result-pagination-main">
          {result.page ? (
            <>
              <Text type="secondary" className="grid-pagination-summary">
                {rows.length === rowCount ? `本批 ${rowCount} 行` : `筛选后 ${rows.length} / 本批 ${rowCount} 行`} · {result.elapsedMs}ms
                {result.page.effectivePageSize < result.page.requestedPageSize ? ` · 服务端单批上限 ${result.page.effectivePageSize}` : ''}
              </Text>
              <div className="result-pagination-actions">
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
                <Space.Compact size="small" className="result-page-size-control">
                  <Button size="small" className="result-page-size-label" disabled>单页行数</Button>
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
              </div>
            </>
          ) : (
            <Text type="secondary" className="grid-pagination-summary">{rows.length === rowCount ? `共 ${rowCount} 行` : `筛选后 ${rows.length} / 共 ${rowCount} 行`} · {result.elapsedMs}ms（当前结果不支持翻页）</Text>
          )}
        </div>
      </div>
      <Modal
        title="指定 SQL 插入目标表"
        open={targetDialogOpen}
        okText="继续"
        cancelText="取消"
        onCancel={() => {
          setTargetDialogOpen(false);
          pendingTargetActionRef.current = null;
        }}
        onOk={() => {
          const parts = parseQualifiedTableName(targetDraft);
          if (!parts) {
            void messageApi.error('请输入有效的表名，例如 public.users 或 "My Schema"."User"');
            return;
          }
          setManualTargetParts(parts);
          setTargetDialogOpen(false);
          const action = pendingTargetActionRef.current;
          pendingTargetActionRef.current = null;
          action?.(parts);
        }}
      >
        <Typography.Paragraph type="secondary">当前查询包含多个来源或复杂子查询，无法安全判断 INSERT 的目标表。</Typography.Paragraph>
        <Input autoFocus value={targetDraft} placeholder="schema.table" onChange={(event) => setTargetDraft(event.target.value)} onPressEnter={() => undefined} />
      </Modal>
    </div>
  );
});

const MemoizedResultTable = memo(function MemoizedResultTable({ tableRef, columns, rows, pagingLoading, scrollX, scrollY, rowClassName, onRow, onChange }: {
  tableRef: Ref<TableRef>;
  columns: ColumnsType<ResultRow>;
  rows: ResultRow[];
  pagingLoading: boolean;
  scrollX: number;
  scrollY: number;
  rowClassName: ResultRowClassName;
  onRow: NonNullable<TableProps<ResultRow>['onRow']>;
  onChange: NonNullable<TableProps<ResultRow>['onChange']>;
}) {
  return (
    <Table<ResultRow>
      ref={tableRef}
      size="small"
      className="data-grid data-grid-fill result-grid"
      columns={columns}
      dataSource={rows}
      pagination={false}
      loading={pagingLoading}
      virtual
      scroll={{ x: scrollX, y: scrollY }}
      rowClassName={rowClassName}
      onRow={onRow}
      onChange={onChange}
    />
  );
});

const LOCAL_EXPORT_ITEMS = [
  { key: 'csv', label: 'CSV' },
  { key: 'json', label: 'JSON' },
  { key: 'sql', label: 'SQL 插入' },
  { key: 'xml', label: 'XML' }
];

function isTextEntryTarget(target: EventTarget | null): boolean {
  return target instanceof Element && Boolean(target.closest('input, textarea, [contenteditable="true"]'));
}

function isInteractiveTarget(target: EventTarget | null): boolean {
  return target instanceof Element && Boolean(target.closest('button, a, input, textarea, select, .ant-checkbox, .ant-checkbox-wrapper, [role="button"], [role="checkbox"], [contenteditable="true"]'));
}

async function copyText(content: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(content);
    return;
  }
  const textarea = document.createElement('textarea');
  textarea.value = content;
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  const copied = document.execCommand('copy');
  textarea.remove();
  if (!copied) throw new Error('浏览器未允许访问剪贴板');
}

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
      <Text type="secondary" className="result-filter-scope">仅筛选当前已加载批次，不会重新查询数据库。</Text>
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
