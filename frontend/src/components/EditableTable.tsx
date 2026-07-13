import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Button, Empty, Input, Spin, Table, Tooltip, Typography } from 'antd';
import type { ColumnsType, TableRef } from 'antd/es/table';
import { DeleteOutlined, UndoOutlined } from '@ant-design/icons';
import { useTableViewportHeight } from '../hooks/useTableViewportHeight';
import type { EditableRow, TableColumn, TableData, TableRow } from '../types';
import { sameCellValue } from '../utils';

export function EditableTable({ data, rows, readonly = false, loading = false, onEdit, onDelete }: {
  data: TableData | null;
  rows: TableRow[];
  readonly?: boolean;
  loading?: boolean;
  onEdit: (rowId: string, column: string, value: unknown) => void;
  onDelete: (rowId: string) => void;
}) {
  const tableRef = useRef<TableRef>(null);
  const lastScrolledDataRef = useRef<TableData | null>(null);
  const { viewportRef, scrollY } = useTableViewportHeight({ enabled: Boolean(data) });
  const columns = useMemo<ColumnsType<EditableRow>>(() => {
    if (!data) return [];
    return [
      {
        title: '操作',
        key: 'action',
        fixed: 'left',
        width: 74,
        render: (_, row) => (
          <Tooltip title={row.deleted ? '撤销删除' : row.inserted ? '移除新增行' : '标记为删除'}>
            <Button
              size="small"
              danger={!row.deleted}
              icon={row.deleted ? <UndoOutlined /> : <DeleteOutlined />}
              disabled={readonly || loading || (!data.editable && !row.inserted)}
              aria-label={row.deleted ? '撤销删除' : row.inserted ? '移除新增行' : '标记为删除'}
              onClick={() => onDelete(row.id)}
            />
          </Tooltip>
        )
      },
      ...data.columns.map((column) => ({
        title: <span title={column.truncated ? `${column.typeName} · 本页存在超长值，已截断并禁用该列编辑` : column.typeName}>{column.name}{column.truncated ? ' ⚠' : ''}</span>,
        key: column.name,
        width: 180,
        render: (_: unknown, row: EditableRow) => (
          <EditableCell
            rowId={row.id}
            column={column}
            value={row.values[column.name]}
            inserted={Boolean(row.inserted)}
            touched={!row.inserted || Boolean(row.touchedColumns?.includes(column.name))}
            disabled={readonly || loading || row.deleted || column.editable === false || (!data.editable && !row.inserted)}
            onCommit={onEdit}
          />
        )
      }))
    ];
  }, [data, loading, onDelete, onEdit, readonly]);

  useLayoutEffect(() => {
    if (!data || scrollY === undefined || !tableRef.current) return;
    if (lastScrolledDataRef.current === data) return;
    tableRef.current.scrollTo({ top: 0 });
    lastScrolledDataRef.current = data;
  }, [data, scrollY]);

  if (!data) return <Empty className="empty-state empty-state-fill" description="点击左侧对象树中的表来浏览数据。" />;

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
          scroll={{ x: Math.max(800, data.columns.length * 180 + 74), y: scrollY }}
        />
      )}
    </div>
  );
}

function EditableCell({ rowId, column, value, inserted, touched, disabled, onCommit }: {
  rowId: string;
  column: TableColumn;
  value: unknown;
  inserted: boolean;
  touched: boolean;
  disabled: boolean;
  onCommit: (rowId: string, column: string, value: unknown) => void;
}) {
  const normalizedValue = String(value ?? '');
  const [draft, setDraft] = useState(normalizedValue);
  const mode = inserted && !touched ? 'default' : value == null ? 'null' : 'value';

  useEffect(() => {
    setDraft(normalizedValue);
  }, [column.name, normalizedValue, rowId, touched]);

  const commit = () => {
    if (mode === 'value' && draft !== normalizedValue) onCommit(rowId, column.name, draft);
  };

  return (
    <div className="editable-cell-control">
      <Input
        size="small"
        disabled={disabled}
        value={mode === 'value' ? draft : ''}
        placeholder={mode === 'default' ? 'DEFAULT' : mode === 'null' ? 'NULL' : undefined}
        aria-label={`${column.name}，行 ${rowId}`}
        onChange={(event) => {
          setDraft(event.target.value);
          if (mode !== 'value') onCommit(rowId, column.name, event.target.value);
        }}
        onBlur={commit}
        onPressEnter={(event) => event.currentTarget.blur()}
        onKeyDown={(event) => {
          if (event.key === 'Escape') setDraft(normalizedValue);
        }}
      />
      {!disabled && inserted && touched && (
        <Tooltip title="使用数据库默认值">
          <Button size="small" type="text" onMouseDown={(event) => event.preventDefault()} onClick={() => onCommit(rowId, column.name, undefined)}>默认</Button>
        </Tooltip>
      )}
      {!disabled && column.nullable && (
        <Tooltip title={mode === 'null' ? '改为空字符串' : '设为 NULL'}>
          <Button size="small" type="text" onMouseDown={(event) => event.preventDefault()} onClick={() => onCommit(rowId, column.name, mode === 'null' ? '' : null)}>{mode === 'null' ? '空串' : 'NULL'}</Button>
        </Tooltip>
      )}
    </div>
  );
}

function isUpdated(row: TableRow) {
  if (!row.original) return false;
  return Object.keys(row.values).some((column) => !sameCellValue(row.values[column], row.original?.[column]));
}
