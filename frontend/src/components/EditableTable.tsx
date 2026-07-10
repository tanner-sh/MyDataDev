import { Button, Empty, Input, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, UndoOutlined } from '@ant-design/icons';
import type { EditableRow, TableData, TableRow } from '../types';

export function EditableTable({ data, rows, readonly = false, loading = false, onEdit, onDelete }: {
  data: TableData | null;
  rows: TableRow[];
  readonly?: boolean;
  loading?: boolean;
  onEdit: (rowId: string, column: string, value: string) => void;
  onDelete: (rowId: string) => void;
}) {
  if (!data) return <Empty className="empty-state" description="点击左侧对象树中的表来浏览数据。" />;
  const columns: ColumnsType<EditableRow> = [
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
        <Input
          size="small"
          disabled={readonly || loading || row.deleted || (!data.editable && !row.inserted)}
          value={String(row.values[column] ?? '')}
          aria-label={`${column}，行 ${row.id}`}
          onChange={(event) => onEdit(row.id, column, event.target.value)}
        />
      )
    }))
  ];
  return (
    <Table<EditableRow>
      size="small"
      className="data-grid data-grid-fill editable-grid"
      columns={columns}
      dataSource={rows}
      loading={loading}
      rowKey="id"
      pagination={false}
      rowClassName={(row) => row.deleted ? 'deleted-row' : row.inserted ? 'inserted-row' : isUpdated(row) ? 'updated-row' : ''}
      scroll={{ x: true, y: '100%' }}
    />
  );
}

function isUpdated(row: TableRow) {
  if (!row.original) return false;
  return Object.keys(row.values).some((column) => row.values[column] !== row.original?.[column]);
}
