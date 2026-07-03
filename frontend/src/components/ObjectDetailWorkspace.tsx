import { Button, Descriptions, Empty, Layout, Space, Table, Tabs, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { CopyOutlined, TableOutlined } from '@ant-design/icons';
import type { DbObject, ObjectDetail } from '../types';
import { objectTypeLabel } from '../utils';

const { Header } = Layout;
const { Text } = Typography;

type ColumnRow = ObjectDetail['columns'][number] & { key: string };
type IndexRow = ObjectDetail['indexes'][number] & { key: string };

export function ObjectDetailWorkspace({ detail, statusMessage, loading, onBackToSql, onOpenTable }: {
  detail: ObjectDetail | null;
  statusMessage: string;
  loading: boolean;
  onBackToSql: () => void;
  onOpenTable: (object: DbObject) => void;
}) {
  const objectName = detail ? `${detail.schemaName ? `${detail.schemaName}.` : ''}${detail.name}` : '未选择对象';
  const isView = detail?.type.toUpperCase().includes('VIEW') || false;
  const columnRows = detail?.columns.map((column) => ({ ...column, key: column.name })) || [];
  const indexRows = detail?.indexes.map((index, rowIndex) => ({ ...index, key: `${index.name}-${index.columnName}-${rowIndex}` })) || [];

  return (
    <div className="workspace object-workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Text strong>{objectName}</Text>
          <Text type="secondary">{detail ? `${objectTypeLabel(detail.type)} · ${statusMessage}` : statusMessage}</Text>
        </div>
        <Space size={8} wrap>
          <Button size="small" onClick={onBackToSql}>查询工作台</Button>
          <Button
            size="small"
            type="primary"
            icon={<TableOutlined />}
            disabled={!detail || isView || loading}
            onClick={() => detail && onOpenTable(detail)}
          >
            打开数据
          </Button>
        </Space>
      </Header>
      {!detail ? (
        <Empty className="empty-state" description="点击左侧对象查看详情。" />
      ) : (
        <Tabs
          className="object-detail-tabs"
          defaultActiveKey="overview"
          items={[
            { key: 'overview', label: '概览', children: <ObjectOverview detail={detail} /> },
            { key: 'columns', label: '字段', children: <ColumnTable rows={columnRows} primaryKeys={detail.primaryKeys} /> },
            { key: 'indexes', label: '索引', children: <IndexTable rows={indexRows} /> },
            { key: 'ddl', label: 'DDL', children: <DdlViewer ddl={detail.ddl} /> }
          ]}
        />
      )}
    </div>
  );
}

function ObjectOverview({ detail }: { detail: ObjectDetail }) {
  return (
    <Descriptions size="small" bordered column={2} className="object-overview">
      <Descriptions.Item label="对象名">{detail.name}</Descriptions.Item>
      <Descriptions.Item label="Schema">{detail.schemaName || '-'}</Descriptions.Item>
      <Descriptions.Item label="类型">{objectTypeLabel(detail.type)}</Descriptions.Item>
      <Descriptions.Item label="行数">{detail.rowCount == null ? '-' : detail.rowCount}</Descriptions.Item>
      <Descriptions.Item label="字段数">{detail.columns.length}</Descriptions.Item>
      <Descriptions.Item label="索引数">{detail.indexes.length}</Descriptions.Item>
      <Descriptions.Item label="主键" span={2}>
        {detail.primaryKeys.length === 0 ? '-' : detail.primaryKeys.map((column) => <Tag key={column}>{column}</Tag>)}
      </Descriptions.Item>
    </Descriptions>
  );
}

function ColumnTable({ rows, primaryKeys }: { rows: ColumnRow[]; primaryKeys: string[] }) {
  const columns: ColumnsType<ColumnRow> = [
    {
      title: '字段名',
      dataIndex: 'name',
      key: 'name',
      render: (value: string) => (
        <Space size={6}>
          <Text>{value}</Text>
          {primaryKeys.includes(value) && <Tag color="blue">主键</Tag>}
        </Space>
      )
    },
    { title: '类型', dataIndex: 'type', key: 'type', width: 180 },
    { title: '长度', dataIndex: 'size', key: 'size', width: 90 },
    { title: '可空', dataIndex: 'nullable', key: 'nullable', width: 90, render: (value: boolean) => value ? '是' : '否' },
    { title: '备注', dataIndex: 'remarks', key: 'remarks', ellipsis: true, render: (value?: string) => value || '-' }
  ];
  return <Table size="small" className="data-grid object-detail-grid" columns={columns} dataSource={rows} pagination={false} scroll={{ x: true, y: 'calc(100vh - 220px)' }} />;
}

function IndexTable({ rows }: { rows: IndexRow[] }) {
  const columns: ColumnsType<IndexRow> = [
    { title: '索引名', dataIndex: 'name', key: 'name' },
    { title: '字段', dataIndex: 'columnName', key: 'columnName' },
    { title: '唯一', dataIndex: 'unique', key: 'unique', width: 90, render: (value: boolean) => value ? '是' : '否' }
  ];
  return <Table size="small" className="data-grid object-detail-grid" columns={columns} dataSource={rows} pagination={false} scroll={{ x: true, y: 'calc(100vh - 220px)' }} />;
}

function DdlViewer({ ddl }: { ddl: string }) {
  return (
    <div className="ddl-viewer">
      <div className="ddl-actions">
        <Button size="small" icon={<CopyOutlined />} onClick={() => navigator.clipboard?.writeText(ddl)}>复制 DDL</Button>
      </div>
      <pre>{ddl}</pre>
    </div>
  );
}
