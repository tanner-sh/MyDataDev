import { useEffect, useMemo, useState } from 'react';
import { Button, Empty, Input, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, UndoOutlined } from '@ant-design/icons';
import { useTableViewportHeight } from '../hooks/useTableViewportHeight';
import type { EditableRow, TableData, TableRow } from '../types';

export function EditableTable({ data, rows, readonly = false, loading = false, onEdit, onDelete }: {
  data: TableData | null;
  rows: TableRow[];
  readonly?: boolean;
  loading?: boolean;
  onEdit: (rowId: string, column: string, value: string) => void;
  onDelete: (rowId: string) => void;
}) {
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
        title: column,
        dataIndex: ['values', column],
        key: column,
        width: 180,
        render: (_: unknown, row: EditableRow) => (
          <EditableCell
            rowId={row.id}
            column={column}
            value={row.values[column]}
            disabled={readonly || loading || row.deleted || (!data.editable && !row.inserted)}
            onCommit={onEdit}
          />
        )
      }))
    ];
  }, [data, loading, onDelete, onEdit, readonly]);

  if (!data) return <Empty className="empty-state empty-state-fill" description="点击左侧对象树中的表来浏览数据。" />;

  return (
    <div ref={viewportRef} className="editable-table-viewport">
      <Table<EditableRow>
        size="small"
        className="data-grid data-grid-fill editable-grid"
        columns={columns}
        dataSource={rows}
        loading={loading}
        rowKey="id"
        pagination={false}
        rowClassName={(row) => row.deleted ? 'deleted-row' : row.inserted ? 'inserted-row' : isUpdated(row) ? 'updated-row' : ''}
        scroll={{ x: 'max-content', ...(scrollY ? { y: scrollY } : {}) }}
      />
    </div>
  );
}

function EditableCell({ rowId, column, value, disabled, onCommit }: {
  rowId: string;
  column: string;
  value: unknown;
  disabled: boolean;
  onCommit: (rowId: string, column: string, value: string) => void;
}) {
  const normalizedValue = String(value ?? '');
  const [draft, setDraft] = useState(normalizedValue);

  useEffect(() => {
    setDraft(normalizedValue);
  }, [column, normalizedValue, rowId]);

  const commit = () => {
    if (draft !== normalizedValue) onCommit(rowId, column, draft);
  };

  return (
    <Input
      size="small"
      disabled={disabled}
      value={draft}
      aria-label={`${column}，行 ${rowId}`}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={commit}
      onPressEnter={(event) => event.currentTarget.blur()}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          setDraft(normalizedValue);
        }
      }}
    />
  );
}

function isUpdated(row: TableRow) {
  if (!row.original) return false;
  return Object.keys(row.values).some((column) => row.values[column] !== row.original?.[column]);
}
