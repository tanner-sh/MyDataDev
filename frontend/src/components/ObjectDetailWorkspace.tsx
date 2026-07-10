import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Descriptions, Empty, Input, InputNumber, Layout, Modal, Select, Space, Table, Tabs, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowLeftOutlined, CopyOutlined, PlusOutlined, TableOutlined } from '@ant-design/icons';
import { api } from '../api';
import type { ColumnDesign, DbObject, IndexDesign, ObjectDetail, ObjectRelation, ObjectRelations, TableDesignRequest, TableDesignResponse, WorkspaceStatus } from '../types';
import { localizeMessage, objectTypeLabel } from '../utils';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';

const { Header } = Layout;
const { Text } = Typography;

type ColumnRow = ObjectDetail['columns'][number] & { key: string };
type IndexRow = ObjectDetail['indexes'][number] & { key: string };
type RelationRow = ObjectRelation & { key: string };
type DesignColumnRow = ColumnDesign & { key: string };
type DesignIndexRow = IndexDesign & { key: string };

export function ObjectDetailWorkspace({
  connectionId,
  readonlyConnection,
  detail,
  status,
  loading,
  onBackToSql,
  onOpenTable,
  onReloadDetail
}: {
  connectionId?: number;
  readonlyConnection?: boolean;
  detail: ObjectDetail | null;
  status: WorkspaceStatus;
  loading: boolean;
  onBackToSql: () => void;
  onOpenTable: (object: DbObject) => void;
  onReloadDetail: () => void;
}) {
  const objectName = detail ? fullObjectName(detail) : '未选择对象';
  const isView = detail?.type.toUpperCase().includes('VIEW') || false;
  const columnRows = detail?.columns.map((column) => ({ ...column, key: column.name })) || [];
  const indexRows = detail?.indexes.map((index, rowIndex) => ({ ...index, key: `${index.name}-${index.columnName}-${rowIndex}` })) || [];

  return (
    <div className="workspace object-workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Space size={8}>
            <Button type="text" size="small" icon={<ArrowLeftOutlined />} aria-label="返回查询工作台" onClick={onBackToSql} />
            <Text strong>{objectName}</Text>
          </Space>
          <Text type="secondary">{detail ? objectTypeLabel(detail.type) : '从资源管理器中选择数据库对象'}</Text>
        </div>
        <Space size={8} wrap>
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
            { key: 'relations', label: '关系', children: <RelationsPanel connectionId={connectionId} detail={detail} /> },
            { key: 'ddl', label: 'DDL', children: <DdlViewer ddl={detail.ddl} source={detail.ddlSource} /> },
            {
              key: 'designer',
              label: '设计',
              children: (
                <TableDesigner
                  connectionId={connectionId}
                  detail={detail}
                  disabled={isView || readonlyConnection || loading}
                  readonlyConnection={readonlyConnection}
                  onReloadDetail={onReloadDetail}
                />
              )
            }
          ]}
        />
      )}
      <WorkspaceStatusBar status={status} />
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
      <Descriptions.Item label="索引数">{new Set(detail.indexes.map((index) => index.name)).size}</Descriptions.Item>
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
    { title: '类型', dataIndex: 'type', key: 'type', width: 150 },
    { title: '长度', dataIndex: 'size', key: 'size', width: 80 },
    { title: '可空', dataIndex: 'nullable', key: 'nullable', width: 80, render: (value: boolean) => value ? '是' : '否' },
    { title: '默认值', dataIndex: 'defaultValue', key: 'defaultValue', width: 130, render: (value?: string) => value || '-' },
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

function RelationsPanel({ connectionId, detail }: { connectionId?: number; detail: ObjectDetail }) {
  const [relations, setRelations] = useState<ObjectRelations | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!connectionId || !detail) return;
    const params = new URLSearchParams({ objectName: detail.name });
    if (detail.schemaName) params.set('schemaName', detail.schemaName);
    api<ObjectRelations>(`/metadata/${connectionId}/objects/relations?${params.toString()}`)
      .then((data) => {
        setRelations(data);
        setError('');
      })
      .catch((e) => setError(localizeMessage((e as Error).message)));
  }, [connectionId, detail.schemaName, detail.name]);

  if (error) return <Alert type="error" showIcon message="关系加载失败" description={error} />;
  if (!relations) return <Empty className="empty-state" description="正在加载关系..." />;
  return (
    <Space direction="vertical" size={12} className="full-width">
      <RelationTable title="引用的表" rows={relations.importedKeys} direction="imported" />
      <RelationTable title="被引用关系" rows={relations.exportedKeys} direction="exported" />
    </Space>
  );
}

function RelationTable({ title, rows, direction }: { title: string; rows: ObjectRelation[]; direction: 'imported' | 'exported' }) {
  const columns: ColumnsType<RelationRow> = [
    { title: '约束名', dataIndex: 'constraintName', key: 'constraintName', render: (value?: string) => value || '-' },
    {
      title: direction === 'imported' ? '引用表' : '来源表',
      key: 'from',
      render: (_, row) => direction === 'imported' ? relationName(row.pkSchemaName, row.pkTableName, row.pkColumnName) : relationName(row.fkSchemaName, row.fkTableName, row.fkColumnName)
    },
    {
      title: direction === 'imported' ? '当前字段' : '目标字段',
      key: 'to',
      render: (_, row) => direction === 'imported' ? relationName(row.fkSchemaName, row.fkTableName, row.fkColumnName) : relationName(row.pkSchemaName, row.pkTableName, row.pkColumnName)
    }
  ];
  return (
    <div>
      <Text strong>{title}</Text>
      <Table size="small" className="data-grid object-detail-grid" columns={columns} dataSource={rows.map((row, index) => ({ ...row, key: `${title}-${index}` }))} pagination={false} locale={{ emptyText: '暂无关系' }} />
    </div>
  );
}

function DdlViewer({ ddl, source }: { ddl: string; source?: string }) {
  return (
    <div className="ddl-viewer">
      <div className="ddl-actions">
        <Space>
          <Tag color={source === 'NATIVE' ? 'green' : 'default'}>{source === 'NATIVE' ? '原生 DDL' : '生成 DDL'}</Tag>
          <Button size="small" icon={<CopyOutlined />} onClick={() => navigator.clipboard?.writeText(ddl)}>复制 DDL</Button>
        </Space>
      </div>
      <pre>{ddl}</pre>
    </div>
  );
}

function TableDesigner({ connectionId, detail, disabled, readonlyConnection, onReloadDetail }: {
  connectionId?: number;
  detail: ObjectDetail;
  disabled?: boolean;
  readonlyConnection?: boolean;
  onReloadDetail: () => void;
}) {
  const [columns, setColumns] = useState<DesignColumnRow[]>([]);
  const [indexes, setIndexes] = useState<DesignIndexRow[]>([]);
  const [primaryKeys, setPrimaryKeys] = useState<string[]>([]);
  const [preview, setPreview] = useState<string[]>([]);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const [message, setMessage] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const activeColumns = columns.filter((column) => !column.deleted);
  const tableName = fullObjectName(detail);

  useEffect(() => {
    setColumns(detail.columns.map((column) => ({
      key: column.name,
      name: column.name,
      type: column.type,
      size: column.size,
      nullable: column.nullable,
      defaultValue: column.defaultValue || '',
      originalName: column.name,
      deleted: false
    })));
    setIndexes(groupIndexes(detail.indexes));
    setPrimaryKeys(detail.primaryKeys);
    setPreview([]);
    setConfirmation('');
    setMessage('');
  }, [detail]);

  const columnOptions = useMemo(() => activeColumns.map((column) => ({ value: column.name, label: column.name })), [activeColumns]);

  async function previewDesign() {
    if (!connectionId) return;
    setSubmitting(true);
    try {
      const response = await api<TableDesignResponse>(`/metadata/${connectionId}/objects/design/preview`, {
        method: 'POST',
        body: JSON.stringify(designRequest(detail, columns, indexes, primaryKeys))
      });
      setPreview(response.sql);
      setMessage(response.message);
      setConfirmOpen(true);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setSubmitting(false);
    }
  }

  async function executeDesign() {
    if (!connectionId) return;
    setSubmitting(true);
    try {
      const response = await api<TableDesignResponse>(`/metadata/${connectionId}/objects/design/execute`, {
        method: 'POST',
        body: JSON.stringify({ ...designRequest(detail, columns, indexes, primaryKeys), confirmation })
      });
      setPreview(response.sql);
      setMessage(response.message);
      setConfirmOpen(false);
      onReloadDetail();
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="table-designer">
      {readonlyConnection && <Alert type="warning" showIcon message="当前连接为只读连接，不能执行结构变更。" />}
      {detail.type.toUpperCase().includes('VIEW') && <Alert type="info" showIcon message="视图暂不支持表设计器。" />}
      {message && <Alert type={message.includes('失败') || message.includes('不') ? 'error' : 'info'} showIcon message={message} />}
      <div className="designer-toolbar">
        <Text strong>字段</Text>
        <Button size="small" icon={<PlusOutlined />} disabled={disabled} onClick={() => setColumns((rows) => [...rows, newColumnRow(rows.length)])}>新增字段</Button>
      </div>
      <Table<DesignColumnRow>
        size="small"
        className="data-grid object-detail-grid"
        rowClassName={(row) => row.deleted ? 'deleted-row' : ''}
        pagination={false}
        dataSource={columns}
        scroll={{ x: true }}
        columns={[
          { title: '字段名', dataIndex: 'name', key: 'name', width: 150, render: (value, row) => <Input size="small" disabled={disabled || row.deleted} value={value} onChange={(event) => updateColumn(row.key, { name: event.target.value }, setColumns)} /> },
          { title: '类型', dataIndex: 'type', key: 'type', width: 130, render: (value, row) => <Input size="small" disabled={disabled || row.deleted} value={value} onChange={(event) => updateColumn(row.key, { type: event.target.value }, setColumns)} /> },
          { title: '长度', dataIndex: 'size', key: 'size', width: 90, render: (value, row) => <InputNumber size="small" min={0} disabled={disabled || row.deleted} value={value || undefined} onChange={(next) => updateColumn(row.key, { size: next || null }, setColumns)} /> },
          { title: '可空', dataIndex: 'nullable', key: 'nullable', width: 80, render: (value, row) => <Checkbox disabled={disabled || row.deleted} checked={value} onChange={(event) => updateColumn(row.key, { nullable: event.target.checked }, setColumns)} /> },
          { title: '默认值', dataIndex: 'defaultValue', key: 'defaultValue', width: 150, render: (value, row) => <Input size="small" disabled={disabled || row.deleted} value={value} onChange={(event) => updateColumn(row.key, { defaultValue: event.target.value }, setColumns)} /> },
          { title: '主键', key: 'pk', width: 70, render: (_, row) => <Checkbox disabled={disabled || row.deleted} checked={primaryKeys.includes(row.name)} onChange={(event) => setPrimaryKeys((keys) => event.target.checked ? [...keys, row.name] : keys.filter((key) => key !== row.name))} /> },
          { title: '操作', key: 'action', width: 90, render: (_, row) => <Button size="small" danger disabled={disabled} onClick={() => setColumns((rows) => rows.map((item) => item.key === row.key ? { ...item, deleted: !item.deleted } : item))}>{row.deleted ? '恢复' : '删除'}</Button> }
        ]}
      />
      <div className="designer-toolbar">
        <Text strong>索引</Text>
        <Button size="small" icon={<PlusOutlined />} disabled={disabled} onClick={() => setIndexes((rows) => [...rows, newIndexRow(rows.length)])}>新增索引</Button>
      </div>
      <Table<DesignIndexRow>
        size="small"
        className="data-grid object-detail-grid"
        rowClassName={(row) => row.deleted ? 'deleted-row' : ''}
        pagination={false}
        dataSource={indexes}
        scroll={{ x: true }}
        columns={[
          { title: '索引名', dataIndex: 'name', key: 'name', width: 170, render: (value, row) => <Input size="small" disabled={disabled || row.deleted} value={value} onChange={(event) => updateIndex(row.key, { name: event.target.value }, setIndexes)} /> },
          { title: '字段', dataIndex: 'columns', key: 'columns', render: (value, row) => <Select size="small" mode="multiple" className="full-width" disabled={disabled || row.deleted} value={value} options={columnOptions} onChange={(next) => updateIndex(row.key, { columns: next }, setIndexes)} /> },
          { title: '唯一', dataIndex: 'unique', key: 'unique', width: 80, render: (value, row) => <Checkbox disabled={disabled || row.deleted} checked={value} onChange={(event) => updateIndex(row.key, { unique: event.target.checked }, setIndexes)} /> },
          { title: '操作', key: 'action', width: 90, render: (_, row) => <Button size="small" danger disabled={disabled} onClick={() => setIndexes((rows) => rows.map((item) => item.key === row.key ? { ...item, deleted: !item.deleted } : item))}>{row.deleted ? '恢复' : '删除'}</Button> }
        ]}
      />
      <div className="designer-actions">
        <Button type="primary" disabled={disabled} loading={submitting} onClick={previewDesign}>预览 DDL</Button>
      </div>
      <Modal
        title="确认执行结构变更"
        open={confirmOpen}
        confirmLoading={submitting}
        okButtonProps={{ disabled: confirmation !== tableName || preview.length === 0 }}
        okText="执行 DDL"
        onOk={executeDesign}
        onCancel={() => setConfirmOpen(false)}
      >
        <Alert type="warning" showIcon message={`请输入完整表名 ${tableName} 后执行。DDL 可能不可回滚。`} />
        <Input className="design-confirm-input" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} placeholder={tableName} />
        <pre className="design-preview">{preview.length === 0 ? '没有待执行 DDL。' : preview.join('\n')}</pre>
      </Modal>
    </div>
  );
}

function fullObjectName(detail: Pick<ObjectDetail, 'schemaName' | 'name'>) {
  return detail.schemaName ? `${detail.schemaName}.${detail.name}` : detail.name;
}

function relationName(schema: string | undefined, table: string, column: string) {
  return `${schema ? `${schema}.` : ''}${table}.${column}`;
}

function groupIndexes(indexes: ObjectDetail['indexes']): DesignIndexRow[] {
  const grouped = new Map<string, DesignIndexRow>();
  indexes.forEach((index) => {
    const current = grouped.get(index.name);
    if (current) {
      current.columns.push(index.columnName);
    } else {
      grouped.set(index.name, {
        key: index.name,
        name: index.name,
        originalName: index.name,
        columns: [index.columnName],
        unique: index.unique,
        deleted: false
      });
    }
  });
  return [...grouped.values()];
}

function newColumnRow(index: number): DesignColumnRow {
  return { key: `new-column-${Date.now()}-${index}`, name: '', type: 'VARCHAR', size: 255, nullable: true, defaultValue: '', deleted: false };
}

function newIndexRow(index: number): DesignIndexRow {
  return { key: `new-index-${Date.now()}-${index}`, name: '', columns: [], unique: false, deleted: false };
}

function updateColumn(key: string, patch: Partial<DesignColumnRow>, setter: React.Dispatch<React.SetStateAction<DesignColumnRow[]>>) {
  setter((rows) => rows.map((row) => row.key === key ? { ...row, ...patch } : row));
}

function updateIndex(key: string, patch: Partial<DesignIndexRow>, setter: React.Dispatch<React.SetStateAction<DesignIndexRow[]>>) {
  setter((rows) => rows.map((row) => row.key === key ? { ...row, ...patch } : row));
}

function designRequest(detail: ObjectDetail, columns: DesignColumnRow[], indexes: DesignIndexRow[], primaryKeys: string[]): TableDesignRequest {
  return {
    schemaName: detail.schemaName,
    tableName: detail.name,
    columns: columns.map(({ key: _key, ...column }) => column),
    indexes: indexes.map(({ key: _key, ...index }) => index),
    primaryKeys
  };
}
