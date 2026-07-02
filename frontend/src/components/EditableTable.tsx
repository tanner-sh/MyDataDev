import { Button, Empty, Input, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined } from '@ant-design/icons';
import type { EditableRow, TableData, TableRow } from '../types';

export function EditableTable({ data, rows, onEdit, onDelete }: {
  data: TableData | null;
  rows: TableRow[];
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
        <Button
          size="small"
          danger
          icon={<DeleteOutlined />}
          disabled={row.deleted || (!data.editable && !row.inserted)}
          onClick={() => onDelete(row.id)}
        />
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
          disabled={row.deleted || (!data.editable && !row.inserted)}
          value={String(row.values[column] ?? '')}
          onChange={(event) => onEdit(row.id, column, event.target.value)}
        />
      )
    }))
  ];
  return (
    <Table<EditableRow>
      size="small"
      className="data-grid editable-grid"
      columns={columns}
      dataSource={rows}
      rowKey="id"
      pagination={false}
      rowClassName={(row) => row.deleted ? 'deleted-row' : row.inserted ? 'inserted-row' : ''}
      scroll={{ x: true, y: 'calc(100vh - 330px)' }}
    />
  );
}
