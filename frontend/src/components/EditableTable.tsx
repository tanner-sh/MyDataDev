import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { Button, Empty, Input, Spin, Table, Tooltip, Typography } from 'antd';
import type { ColumnsType, TableRef } from 'antd/es/table';
import { DeleteOutlined, UndoOutlined } from '@ant-design/icons';
import { useTableViewportHeight } from '../hooks/useTableViewportHeight';
import type { EditableRow, TableColumn, TableData, TableRow } from '../types';
import { sameCellValue } from '../utils';

type EditableTableProps = {
  data: TableData | null;
  rows: TableRow[];
  readonly?: boolean;
  loading?: boolean;
  onEdit: (rowId: string, column: string, value: unknown) => void;
  onDelete: (rowId: string) => void;
};

export const EditableTable = memo(function EditableTable({ data, rows, readonly = false, loading = false, onEdit, onDelete }: EditableTableProps) {
  const tableRef = useRef<TableRef>(null);
  const lastScrolledDataRef = useRef<TableData | null>(null);
  const [activeCell, setActiveCell] = useState<string | null>(null);
  const [columnWidths, setColumnWidths] = useState<Record<string, number>>({});
  const { viewportRef, scrollY } = useTableViewportHeight({ enabled: Boolean(data) });

  useEffect(() => {
    setActiveCell(null);
    setColumnWidths({});
  }, [data]);

  const suggestedWidths = useMemo(() => new Map((data?.columns || []).map((column) => [column.name, suggestedColumnWidth(column, data?.rows || [])])), [data]);
  const setColumnWidth = useCallback((columnName: string, width: number) => {
    setColumnWidths((current) => ({ ...current, [columnName]: Math.max(88, Math.min(520, width)) }));
  }, []);
  const beginResize = useCallback((event: ReactMouseEvent, columnName: string, initialWidth: number) => {
    event.preventDefault();
    event.stopPropagation();
    const startX = event.clientX;
    const move = (moveEvent: MouseEvent) => setColumnWidth(columnName, initialWidth + moveEvent.clientX - startX);
    const stop = () => {
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', stop);
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', stop);
  }, [setColumnWidth]);

  const columns = useMemo<ColumnsType<EditableRow>>(() => {
    if (!data) return [];
    return [
      {
        title: '操作',
        key: 'action',
        fixed: 'left',
        width: 58,
        align: 'center',
        render: (_, row, rowIndex) => (
          <Tooltip title={row.deleted ? '撤销删除' : row.inserted ? '移除新增行' : '标记为删除'}>
            <Button
              size="small"
              type="text"
              danger={!row.deleted}
              icon={row.deleted ? <UndoOutlined /> : <DeleteOutlined />}
              disabled={readonly || loading || (!data.editable && !row.inserted)}
              aria-label={row.deleted ? `撤销删除第 ${rowIndex + 1} 行` : row.inserted ? `移除新增的第 ${rowIndex + 1} 行` : `标记删除第 ${rowIndex + 1} 行`}
              onClick={() => onDelete(row.id)}
            />
          </Tooltip>
        )
      },
      ...data.columns.map((column) => ({
        title: (
          <div className="resizable-column-title" title={column.truncated ? `${column.typeName} · 本页存在超长值，已截断并禁用该列编辑` : column.typeName}>
            <span>{column.name}{column.truncated ? ' ⚠' : ''}</span>
            <span
              className="column-resize-handle"
              role="separator"
              aria-orientation="vertical"
              aria-label={`调整 ${column.name} 列宽`}
              tabIndex={0}
              onMouseDown={(event) => beginResize(event, column.name, columnWidths[column.name] || suggestedWidths.get(column.name) || 156)}
              onDoubleClick={() => setColumnWidths((current) => {
                const next = { ...current };
                delete next[column.name];
                return next;
              })}
              onKeyDown={(event) => {
                if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
                event.preventDefault();
                const current = columnWidths[column.name] || suggestedWidths.get(column.name) || 156;
                setColumnWidth(column.name, current + (event.key === 'ArrowRight' ? 12 : -12));
              }}
            />
          </div>
        ),
        key: column.name,
        width: columnWidths[column.name] || suggestedWidths.get(column.name),
        ellipsis: true,
        render: (_: unknown, row: EditableRow, rowIndex: number) => {
          const cellKey = `${row.id}\u0000${column.name}`;
          return (
            <EditableCell
              rowId={row.id}
              rowNumber={rowIndex + 1}
              column={column}
              value={row.values[column.name]}
              inserted={Boolean(row.inserted)}
              touched={!row.inserted || Boolean(row.touchedColumns?.includes(column.name))}
              disabled={readonly || loading || row.deleted || column.editable === false || (!data.editable && !row.inserted)}
              editing={activeCell === cellKey}
              onActivate={() => setActiveCell(cellKey)}
              onDeactivate={() => setActiveCell((current) => current === cellKey ? null : current)}
              onCommit={onEdit}
            />
          );
        }
      }))
    ];
  }, [activeCell, beginResize, columnWidths, data, loading, onDelete, onEdit, readonly, setColumnWidth, suggestedWidths]);

  useLayoutEffect(() => {
    if (!data || scrollY === undefined || !tableRef.current) return;
    if (lastScrolledDataRef.current === data) return;
    tableRef.current.scrollTo({ top: 0 });
    lastScrolledDataRef.current = data;
  }, [data, scrollY]);

  if (!data) return <Empty className="empty-state empty-state-fill" description="点击左侧对象树中的表来浏览数据。" />;

  const scrollX = data.columns.reduce((total, column) => total + (columnWidths[column.name] || suggestedWidths.get(column.name) || 160), 58);
  return (
    <div ref={viewportRef} className="editable-table-viewport">
      {scrollY === undefined ? (
        <div className="table-viewport-loading"><Spin size="small" /><Typography.Text type="secondary">正在准备表格…</Typography.Text></div>
      ) : (
        <Table<EditableRow>
          ref={tableRef}
          size="small"
          className="data-grid data-grid-fill editable-grid"
          columns={columns}
          dataSource={rows}
          loading={loading}
          rowKey="id"
          pagination={false}
          virtual
          rowClassName={(row) => row.deleted ? 'deleted-row' : row.inserted ? 'inserted-row' : isUpdated(row) ? 'updated-row' : ''}
          scroll={{ x: Math.max(800, scrollX), y: scrollY }}
        />
      )}
    </div>
  );
});

const EditableCell = memo(function EditableCell({ rowId, rowNumber, column, value, inserted, touched, disabled, editing, onActivate, onDeactivate, onCommit }: {
  rowId: string;
  rowNumber: number;
  column: TableColumn;
  value: unknown;
  inserted: boolean;
  touched: boolean;
  disabled: boolean;
  editing: boolean;
  onActivate: () => void;
  onDeactivate: () => void;
  onCommit: (rowId: string, column: string, value: unknown) => void;
}) {
  const normalizedValue = String(value ?? '');
  const [draft, setDraft] = useState(normalizedValue);
  const inputRef = useRef<React.ComponentRef<typeof Input>>(null);
  const mode = inserted && !touched ? 'default' : value == null ? 'null' : 'value';

  useEffect(() => setDraft(normalizedValue), [column.name, normalizedValue, rowId, touched]);

  useEffect(() => {
    if (!editing) return;
    const timer = requestAnimationFrame(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    });
    return () => cancelAnimationFrame(timer);
  }, [editing]);

  const commit = () => {
    if (draft !== normalizedValue && (mode === 'value' || draft.length > 0)) onCommit(rowId, column.name, draft);
    onDeactivate();
  };

  if (!editing) {
    const displayValue = mode === 'null' ? 'NULL' : mode === 'default' ? 'DEFAULT' : normalizedValue;
    return (
      <div
        className={`editable-cell-display editable-cell-${mode}${disabled ? ' is-disabled' : ''}`}
        title={displayValue}
        tabIndex={disabled ? undefined : 0}
        role={disabled ? undefined : 'button'}
        aria-label={`${column.name}，第 ${rowNumber} 行，${displayValue || '空字符串'}${disabled ? '' : '，按回车编辑'}`}
        onClick={disabled ? undefined : onActivate}
        onKeyDown={disabled ? undefined : (event) => {
          if (event.key === 'Enter' || event.key === 'F2') {
            event.preventDefault();
            onActivate();
          }
        }}
      >
        {mode === 'null' || mode === 'default'
          ? <span className="editable-cell-state">{displayValue}</span>
          : <span className="editable-cell-value">{normalizedValue || <span className="editable-cell-empty">空字符串</span>}</span>}
      </div>
    );
  }

  return (
    <div className="editable-cell-control">
      <Input
        ref={inputRef}
        size="small"
        value={draft}
        placeholder={mode === 'default' ? 'DEFAULT' : mode === 'null' ? 'NULL' : undefined}
        aria-label={`编辑 ${column.name}，第 ${rowNumber} 行`}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={commit}
        onPressEnter={(event) => event.currentTarget.blur()}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault();
            setDraft(normalizedValue);
            onDeactivate();
          }
        }}
      />
      {inserted && touched && (
        <Tooltip title="使用数据库默认值">
          <Button size="small" type="text" aria-label={`将第 ${rowNumber} 行的 ${column.name} 恢复为默认值`} onMouseDown={(event) => event.preventDefault()} onClick={() => { onCommit(rowId, column.name, undefined); onDeactivate(); }}>默认</Button>
        </Tooltip>
      )}
      {column.nullable && (
        <Tooltip title={mode === 'null' ? '改为空字符串' : '设为 NULL'}>
          <Button size="small" type="text" aria-label={`将第 ${rowNumber} 行的 ${column.name} ${mode === 'null' ? '改为空字符串' : '设为 NULL'}`} onMouseDown={(event) => event.preventDefault()} onClick={() => { onCommit(rowId, column.name, mode === 'null' ? '' : null); onDeactivate(); }}>{mode === 'null' ? '空串' : 'NULL'}</Button>
        </Tooltip>
      )}
    </div>
  );
});

function suggestedColumnWidth(column: TableColumn, rows: Record<string, unknown>[]) {
  const type = column.typeName.toLocaleUpperCase();
  const base = /BOOL|BIT/.test(type) ? 104 : /DATE|TIME/.test(type) ? 176 : /INT|DECIMAL|NUMERIC|FLOAT|DOUBLE/.test(type) ? 132 : 156;
  const longest = rows.slice(0, 30).reduce(
    (length, row) => Math.max(length, String(row[column.name] ?? '').length),
    Math.max(column.name.length, column.typeName.length)
  );
  return Math.min(300, Math.max(base, longest * 8 + 32));
}

function isUpdated(row: TableRow) {
  if (!row.original) return false;
  return Object.keys(row.values).some((column) => !sameCellValue(row.values[column], row.original?.[column]));
}
