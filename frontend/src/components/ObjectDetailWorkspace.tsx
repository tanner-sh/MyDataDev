import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PanelEmpty, PanelLoading } from './PanelState';
import { Alert, Button, Dropdown, Input, Layout, Modal, Popconfirm, Space, Spin, Table, Tabs, Tag, Typography } from 'antd';
import type { MenuProps } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowLeftOutlined, ArrowRightOutlined, CloudDownloadOutlined, CopyOutlined, DeleteOutlined, EditOutlined, KeyOutlined, MoreOutlined, ReloadOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons';
import { api } from '../api';
import { useTableViewportHeight } from '../hooks/useTableViewportHeight';
import type { DatabaseCapabilities, DbObject, ObjectDdl, ObjectDetail, ObjectRelation, ObjectRelations, ObjectRowCount, TableDesignRequest, TableDesignResponse, WorkspaceStatus } from '../types';
import { localizeMessage, objectTypeLabel } from '../utils';
import {
  designColumns,
  designIndexes,
  serializeColumns,
  serializeIndexes,
  TableDefinitionEditor,
  tableDefinitionSignature
} from './TableDefinitionEditor';
import type { DesignColumnRow, DesignIndexRow } from './TableDefinitionEditor';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';
import { TypedConfirmationFields } from './TypedConfirmationFields';
import { productionConfirmationHeaders } from '../productionConfirmation';
import { compactColumnType } from '../columnTypeLabel';
import { relationTarget, relationTargetLabel } from '../relationNavigation';

const { Header } = Layout;
const { Text } = Typography;

type ColumnRow = ObjectDetail['columns'][number] & { key: string };
type IndexRow = { key: string; name: string; columns: string[]; unique: boolean; primary: boolean };

export interface ObjectDetailWorkspaceProps {
  connectionId?: number;
  readonlyConnection?: boolean;
  capabilities?: DatabaseCapabilities;
  productionConfirmationText?: string;
  target: DbObject | null;
  detail: ObjectDetail | null;
  status: WorkspaceStatus;
  loading: boolean;
  onBackToSql: () => void;
  onOpenTable: (object: DbObject) => void;
  onReloadDetail: () => void;
  onBackupTable?: (object: DbObject) => void;
  onRenameTable?: (object: DbObject) => void;
  onDropTable?: (object: DbObject) => void;
  onDesignDirtyChange?: (dirty: boolean) => void;
  /** 点开一条外键的另一端，见 relationNavigation.ts。 */
  onOpenRelation?: (relation: ObjectRelation, direction: 'imported' | 'exported') => void;
}

export function ObjectDetailWorkspace({
  connectionId,
  readonlyConnection,
  capabilities,
  productionConfirmationText,
  target,
  detail,
  status,
  loading,
  onBackToSql,
  onOpenTable,
  onReloadDetail,
  onBackupTable,
  onRenameTable,
  onDropTable,
  onDesignDirtyChange,
  onOpenRelation
}: ObjectDetailWorkspaceProps) {
  const [activeTabKey, setActiveTabKey] = useState('columns');
  const [designerDirty, setDesignerDirty] = useState(false);
  const displayObject = detail || target;
  const objectName = displayObject ? fullObjectName(displayObject) : '未选择对象';
  const isView = detail?.type.toUpperCase().includes('VIEW') || false;
  const isPhysicalTable = Boolean(detail && detail.type.toUpperCase().includes('TABLE') && !isView);
  const tableBrowseSupported = capabilities?.tableBrowse ?? true;
  const tableDesignSupported = capabilities?.tableDesign ?? true;
  const tableLifecycleEnabled = isPhysicalTable && tableDesignSupported && !readonlyConnection;
  const detailKey = detail ? `${detail.schemaName || ''}.${detail.name}.${detail.type}` : '';
  const columnRows = useMemo(() => detail?.columns.map((column) => ({ ...column, key: column.name })) || [], [detail]);
  const indexRows = useMemo(() => detail ? aggregateIndexRows(detail) : [], [detail]);
  const secondaryMenu: MenuProps = {
    items: [
      { key: 'refresh', icon: <ReloadOutlined />, label: '刷新对象', disabled: !detail || loading },
      ...(onBackupTable ? [{ key: 'backup', icon: <CloudDownloadOutlined />, label: '备份此表', disabled: !detail || !isPhysicalTable || loading }] : []),
      ...(onRenameTable && onDropTable ? [
        { type: 'divider' as const },
        { key: 'rename', icon: <EditOutlined />, label: '重命名表', disabled: !tableLifecycleEnabled || loading },
        { key: 'drop', icon: <DeleteOutlined />, label: '删除表', danger: true, disabled: !tableLifecycleEnabled || loading }
      ] : [])
    ],
    onClick: ({ key }) => {
      if (key === 'refresh') onReloadDetail();
      if (key === 'backup' && detail) onBackupTable?.(detail);
      if (key === 'rename' && detail) onRenameTable?.(detail);
      if (key === 'drop' && detail) onDropTable?.(detail);
    }
  };

  useEffect(() => {
    setActiveTabKey('columns');
    setDesignerDirty(false);
    onDesignDirtyChange?.(false);
  }, [detailKey]);

  const handleDesignDirtyChange = useCallback((dirty: boolean) => {
    setDesignerDirty(dirty);
    onDesignDirtyChange?.(dirty);
  }, [onDesignDirtyChange]);

  return (
    <div className="workspace object-workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Space size={8} className="object-title-line">
            <Button type="text" size="small" icon={<ArrowLeftOutlined />} aria-label="返回查询工作台" onClick={onBackToSql} />
            <Text strong>{objectName}</Text>
            {displayObject && <Tag color="blue">{objectTypeLabel(displayObject.type)}</Tag>}
            {displayObject?.schemaName && <Tag>{displayObject.schemaName}</Tag>}
            {readonlyConnection && <Tag color="orange">只读连接</Tag>}
          </Space>
          <Text type="secondary">{detail
            ? `${detail.columns.length} 个字段 · ${new Set(detail.indexes.map((index) => index.name)).size} 个索引`
            : target
              ? loading ? '正在加载对象定义…' : '对象定义尚未加载'
              : '从资源管理器中选择数据库对象'}</Text>
        </div>
        <Space size={8} className="object-toolbar-actions">
          <Dropdown trigger={['click']} menu={secondaryMenu}>
            <Button
              className="object-more-actions"
              size="small"
              icon={<MoreOutlined />}
              aria-label="更多对象操作"
              disabled={!detail}
            >
              更多
            </Button>
          </Dropdown>
          <Button
            size="small"
            type="primary"
            icon={<TableOutlined />}
            disabled={!detail || !isPhysicalTable || !tableBrowseSupported || loading}
            onClick={() => detail && onOpenTable(detail)}
          >
            打开数据
          </Button>
        </Space>
      </Header>
      {!detail ? (
        target ? (
          <div className="object-detail-placeholder">
            {loading ? (
              <><Spin size="large" /><Text type="secondary">正在读取 {objectName} 的字段、索引和主键信息…</Text></>
            ) : status.kind === 'error' ? (
              <Alert
                type="error"
                showIcon
                title="对象定义加载失败"
                description={status.text}
                action={<Button size="small" onClick={onReloadDetail}>重试</Button>}
              />
            ) : (
              <PanelEmpty title="对象定义尚未加载" />
            )}
          </div>
        ) : <PanelEmpty title="点击左侧对象查看详情" fill />
      ) : (
        <div className="object-detail-content">
          <ObjectSummary connectionId={connectionId} detail={detail} />
          <Tabs
            className="object-detail-tabs"
            activeKey={activeTabKey}
            onChange={setActiveTabKey}
            items={[
              { key: 'columns', label: `字段 (${detail.columns.length})`, children: <ColumnTable key={detailKey} rows={columnRows} primaryKeys={detail.primaryKeys} active={activeTabKey === 'columns'} /> },
              { key: 'indexes', label: `索引 (${indexRows.length})`, children: <IndexTable rows={indexRows} active={activeTabKey === 'indexes'} /> },
              { key: 'relations', label: '关系', children: <RelationsPanel connectionId={connectionId} detail={detail} active={activeTabKey === 'relations'} onOpenRelation={onOpenRelation} /> },
              { key: 'ddl', label: 'DDL', children: <DdlPanel connectionId={connectionId} detail={detail} active={activeTabKey === 'ddl'} /> },
              {
                key: 'designer',
                label: designerDirty ? '设计 *' : '设计',
                children: (
                  <TableDesigner
                    connectionId={connectionId}
                    detail={detail}
                    disabled={isView || readonlyConnection || !tableDesignSupported || loading}
                    readonlyConnection={readonlyConnection}
                    unsupported={!tableDesignSupported}
                    productionConfirmationText={productionConfirmationText}
                    onReloadDetail={onReloadDetail}
                    onDirtyChange={handleDesignDirtyChange}
                  />
                )
              }
            ]}
          />
        </div>
      )}
      <WorkspaceStatusBar status={status} />
    </div>
  );
}

function ObjectSummary({ connectionId, detail }: { connectionId?: number; detail: ObjectDetail }) {
  const indexCount = new Set(detail.indexes.map((index) => index.name)).size;
  const [rowCount, setRowCount] = useState<ObjectRowCount | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const requestIdRef = useRef(0);
  const requestAbortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    requestIdRef.current += 1;
    requestAbortRef.current?.abort();
    requestAbortRef.current = null;
    setRowCount(null);
    setError('');
    setLoading(false);
    return () => requestAbortRef.current?.abort();
  }, [connectionId, detail]);

  async function loadRowCount() {
    if (!connectionId) return;
    requestAbortRef.current?.abort();
    const controller = new AbortController();
    requestAbortRef.current = controller;
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError('');
    try {
      const params = new URLSearchParams({ objectName: detail.name });
      if (detail.schemaName) params.set('schemaName', detail.schemaName);
      const result = await api<ObjectRowCount>(`/metadata/${connectionId}/objects/row-count?${params.toString()}`, { signal: controller.signal });
      if (requestId === requestIdRef.current) setRowCount(result);
    } catch (e) {
      if ((e as Error).name !== 'AbortError' && requestId === requestIdRef.current) setError(localizeMessage((e as Error).message));
    } finally {
      if (requestId === requestIdRef.current) setLoading(false);
      if (requestAbortRef.current === controller) requestAbortRef.current = null;
    }
  }

  return (
    <div className="object-summary" aria-label="对象摘要">
      <div className="object-summary-metric">
        <Text type="secondary">行数</Text>
        {rowCount?.value != null ? (
          <Text strong title={`精确统计，用时 ${rowCount.elapsedMs}ms`}>{rowCount.value.toLocaleString('zh-CN')}</Text>
        ) : (
          <Popconfirm
            title="执行精确行数统计？"
            description="大表执行 COUNT(*) 可能较慢，超过 15 秒会自动取消。"
            okText="开始统计"
            cancelText="取消"
            onConfirm={loadRowCount}
          >
            <Button size="small" type="link" loading={loading}>点击统计</Button>
          </Popconfirm>
        )}
        {error && <Text type="danger" title={error}>统计失败</Text>}
      </div>
      <SummaryMetric label="字段" value={String(detail.columns.length)} />
      <SummaryMetric label="索引" value={String(indexCount)} />
      <div className="object-summary-primary">
        <Text type="secondary"><KeyOutlined /> 主键</Text>
        <div>{detail.primaryKeys.length === 0 ? <Text type="secondary">未定义</Text> : detail.primaryKeys.map((column) => <Tag key={column} color="blue">{column}</Tag>)}</div>
      </div>
    </div>
  );
}

function SummaryMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="object-summary-metric">
      <Text type="secondary">{label}</Text>
      <Text strong>{value}</Text>
    </div>
  );
}

function ColumnTable({ rows, primaryKeys, active }: { rows: ColumnRow[]; primaryKeys: string[]; active: boolean }) {
  const [query, setQuery] = useState('');
  const { viewportRef, scrollY } = useTableViewportHeight({ active });
  const filteredRows = useMemo(() => {
    const keyword = query.trim().toLocaleLowerCase();
    if (!keyword) return rows;
    return rows.filter((row) => [row.name, row.type, row.remarks, row.defaultValue]
      .some((value) => String(value || '').toLocaleLowerCase().includes(keyword)));
  }, [query, rows]);
  const columns: ColumnsType<ColumnRow> = [
    { title: '#', key: 'position', width: 56, render: (_value, row, index) => row.ordinalPosition || index + 1 },
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
    // 驱动返回的是 CHARACTER VARYING 这类全称，在 150px 里必然折行，把整行撑成两倍高，
    // 行高参差不齐。缩写后配合长度列就够读了，完整原文放 title。
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 150,
      render: (value: string) => <span title={value}>{compactColumnType(value)}</span>
    },
    { title: '长度', dataIndex: 'size', key: 'size', width: 80 },
    { title: '约束', dataIndex: 'nullable', key: 'nullable', width: 92, render: (value: boolean) => value ? <Tag>可空</Tag> : <Tag color="orange">非空</Tag> },
    // 整列都空时不占版面：多数表没有默认值和备注，两列常年只显示「-」却吃掉三成宽度。
    ...(rows.some((row) => row.defaultValue != null && row.defaultValue !== '')
      ? [{ title: '默认值', dataIndex: 'defaultValue', key: 'defaultValue', width: 150, render: (value?: string) => value == null || value === '' ? '-' : <Text code>{value}</Text> }]
      : []),
    ...(rows.some((row) => Boolean(row.remarks))
      ? [{ title: '备注', dataIndex: 'remarks', key: 'remarks', ellipsis: true, render: (value?: string) => value || '-' }]
      : [])
  ];
  return (
    <div className="object-tab-panel object-column-panel">
      <div className="object-table-toolbar">
        <Input
          allowClear
          size="small"
          prefix={<SearchOutlined />}
          value={query}
          placeholder="搜索字段名、类型、默认值或备注"
          aria-label="搜索字段"
          onChange={(event) => setQuery(event.target.value)}
        />
        <Text type="secondary">显示 {filteredRows.length} / {rows.length} 个字段</Text>
      </div>
      <div ref={viewportRef} className="object-table-pane">
        {scrollY === undefined ? <TableViewportLoading /> : (
          <Table
            size="small"
            className="data-grid object-detail-grid"
            columns={columns}
            dataSource={filteredRows}
            pagination={false}
            virtual
            scroll={{ x: 1000, y: scrollY }}
            locale={{ emptyText: query ? '没有匹配的字段' : '暂无字段' }}
          />
        )}
      </div>
    </div>
  );
}

function IndexTable({ rows, active }: { rows: IndexRow[]; active: boolean }) {
  const { viewportRef, scrollY } = useTableViewportHeight({ active });
  const columns: ColumnsType<IndexRow> = [
    {
      title: '索引名', dataIndex: 'name', key: 'name',
      render: (value: string, row) => <Space size={6}><Text>{value}</Text>{row.primary && <Tag color="blue">主键</Tag>}</Space>
    },
    {
      title: '字段顺序', dataIndex: 'columns', key: 'columns',
      render: (columns: string[]) => <Space size={[4, 4]} wrap>{columns.map((column, index) => <Tag key={`${column}-${index}`}>{index + 1}. {column}</Tag>)}</Space>
    },
    { title: '类型', dataIndex: 'unique', key: 'unique', width: 110, render: (value: boolean) => value ? <Tag color="green">唯一索引</Tag> : <Tag>普通索引</Tag> }
  ];
  return (
    <div ref={viewportRef} className="object-table-pane object-index-panel">
      {scrollY === undefined ? <TableViewportLoading /> : (
        <Table
          size="small"
          className="data-grid object-detail-grid"
          columns={columns}
          dataSource={rows}
          pagination={false}
          virtual
          scroll={{ x: 720, y: scrollY }}
          locale={{ emptyText: '当前对象没有索引' }}
        />
      )}
    </div>
  );
}

function TableViewportLoading() {
  return <div className="table-viewport-loading"><Spin size="small" /><Text type="secondary">正在准备表格…</Text></div>;
}

function RelationsPanel({ connectionId, detail, active, onOpenRelation }: {
  connectionId?: number;
  detail: ObjectDetail;
  active: boolean;
  onOpenRelation?: (relation: ObjectRelation, direction: 'imported' | 'exported') => void;
}) {
  const [relations, setRelations] = useState<ObjectRelations | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setRelations(null);
    setError('');
  }, [connectionId, detail]);

  useEffect(() => {
    if (!connectionId || !detail || !active || relations) return;
    let cancelled = false;
    setRelations(null);
    setError('');
    const params = new URLSearchParams({ objectName: detail.name });
    if (detail.schemaName) params.set('schemaName', detail.schemaName);
    api<ObjectRelations>(`/metadata/${connectionId}/objects/relations?${params.toString()}`)
      .then((data) => {
        if (cancelled) return;
        setRelations(data);
        setError('');
      })
      .catch((e) => {
        if (!cancelled) setError(localizeMessage((e as Error).message));
      });
    return () => {
      cancelled = true;
    };
  }, [active, connectionId, detail, relations]);

  if (error) return <Alert type="error" showIcon title="关系加载失败" description={error} />;
  if (!relations) return <PanelLoading text="正在加载对象关系…" compact />;
  return (
    <div className="object-tab-scroll relations-panel">
      <RelationCards title="引用的对象" rows={relations.importedKeys} direction="imported" onOpen={onOpenRelation} />
      <RelationCards title="引用当前对象" rows={relations.exportedKeys} direction="exported" onOpen={onOpenRelation} />
    </div>
  );
}

function RelationCards({ title, rows, direction, onOpen }: {
  title: string;
  rows: ObjectRelation[];
  direction: 'imported' | 'exported';
  onOpen?: (relation: ObjectRelation, direction: 'imported' | 'exported') => void;
}) {
  return (
    <div className="relation-section">
      <div className="relation-section-title">
        <Text strong>{title}</Text>
        <Tag>{rows.length}</Tag>
      </div>
      {rows.length === 0 ? (
        <div className="relation-empty"><Text type="secondary">暂无关系</Text></div>
      ) : (
        <div className="relation-card-grid">
          {rows.map((row, index) => {
            const source = relationName(row.fkSchemaName, row.fkTableName, row.fkColumnName);
            const target = relationName(row.pkSchemaName, row.pkTableName, row.pkColumnName);
            return (
              <div className="relation-card" key={`${row.constraintName || 'relation'}-${index}`}>
                <div className="relation-card-heading">
                  <Text strong>{row.constraintName || '未命名外键'}</Text>
                  <Tag color={direction === 'imported' ? 'blue' : 'purple'}>{direction === 'imported' ? '外键引用' : '被引用'}</Tag>
                </div>
                <div className="relation-flow">
                  <Text code title={source}>{source}</Text>
                  <ArrowRightOutlined />
                  <Text code title={target}>{target}</Text>
                </div>
                {onOpen && (
                  <Button
                    size="small"
                    type="link"
                    className="relation-card-open"
                    onClick={() => onOpen(row, direction)}
                  >
                    打开 {relationTargetLabel(relationTarget(row, direction))}
                  </Button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function DdlPanel({ connectionId, detail, active }: { connectionId?: number; detail: ObjectDetail; active: boolean }) {
  const [ddl, setDdl] = useState<ObjectDdl | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setDdl(null);
    setError('');
  }, [connectionId, detail]);

  useEffect(() => {
    if (!active || !connectionId || ddl) return;
    let cancelled = false;
    const params = new URLSearchParams({ objectName: detail.name });
    if (detail.schemaName) params.set('schemaName', detail.schemaName);
    api<ObjectDdl>(`/metadata/${connectionId}/objects/ddl?${params.toString()}`)
      .then((result) => {
        if (!cancelled) setDdl(result);
      })
      .catch((e) => {
        if (!cancelled) setError(localizeMessage((e as Error).message));
      });
    return () => {
      cancelled = true;
    };
  }, [active, connectionId, ddl, detail.name, detail.schemaName]);

  if (error) return <Alert type="error" showIcon title="DDL 加载失败" description={error} />;
  if (!ddl) return <div className="object-lazy-loading"><Spin size="small" /><Text type="secondary">正在按需加载 DDL…</Text></div>;
  return <DdlViewer ddl={ddl.ddl} source={ddl.source} />;
}

function DdlViewer({ ddl, source }: { ddl: string; source?: string }) {
  return (
    <div className="ddl-viewer">
      <div className="ddl-actions">
        <Space size={6}>
          <Tag color={source === 'NATIVE' ? 'green' : 'default'}>{source === 'NATIVE' ? '原生 DDL' : '生成 DDL'}</Tag>
          <Text type="secondary">{source === 'NATIVE' ? '由数据库原生元数据提供' : '根据 JDBC 元数据生成'}</Text>
        </Space>
        <Button size="small" icon={<CopyOutlined />} onClick={() => navigator.clipboard?.writeText(ddl)}>复制 DDL</Button>
      </div>
      <pre>{ddl}</pre>
    </div>
  );
}

function TableDesigner({ connectionId, detail, disabled, readonlyConnection, unsupported, productionConfirmationText, onReloadDetail, onDirtyChange }: {
  connectionId?: number;
  detail: ObjectDetail;
  disabled?: boolean;
  readonlyConnection?: boolean;
  unsupported?: boolean;
  productionConfirmationText?: string;
  onReloadDetail: () => void;
  onDirtyChange: (dirty: boolean) => void;
}) {
  const [columns, setColumns] = useState<DesignColumnRow[]>([]);
  const [indexes, setIndexes] = useState<DesignIndexRow[]>([]);
  const [primaryKeys, setPrimaryKeys] = useState<string[]>([]);
  const [baselineSignature, setBaselineSignature] = useState('');
  const [preview, setPreview] = useState<string[]>([]);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const [productionConfirmation, setProductionConfirmation] = useState('');
  const [message, setMessage] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const requestIdRef = useRef(0);
  const requestAbortRef = useRef<AbortController | null>(null);
  const tableName = fullObjectName(detail);
  const currentSignature = useMemo(() => tableDefinitionSignature(columns, indexes, primaryKeys), [columns, indexes, primaryKeys]);
  const dirty = baselineSignature !== '' && baselineSignature !== currentSignature;

  useEffect(() => {
    requestIdRef.current += 1;
    requestAbortRef.current?.abort();
    requestAbortRef.current = null;
    const nextColumns = designColumns(detail);
    const nextIndexes = designIndexes(detail);
    const nextPrimaryKeys = [...detail.primaryKeys];
    setColumns(nextColumns);
    setIndexes(nextIndexes);
    setPrimaryKeys(nextPrimaryKeys);
    setBaselineSignature(tableDefinitionSignature(nextColumns, nextIndexes, nextPrimaryKeys));
    setPreview([]);
    setConfirmOpen(false);
    setConfirmation('');
    setProductionConfirmation('');
    setMessage('');
    setSubmitting(false);
  }, [detail]);

  useEffect(() => () => requestAbortRef.current?.abort(), []);

  useEffect(() => {
    onDirtyChange(dirty);
  }, [dirty, onDirtyChange]);

  useEffect(() => () => onDirtyChange(false), [onDirtyChange]);

  async function previewDesign() {
    if (!connectionId) return;
    requestAbortRef.current?.abort();
    const controller = new AbortController();
    requestAbortRef.current = controller;
    const requestId = ++requestIdRef.current;
    setSubmitting(true);
    try {
      const response = await api<TableDesignResponse>(`/metadata/${connectionId}/objects/design/preview`, {
        method: 'POST',
        signal: controller.signal,
        body: JSON.stringify(designRequest(detail, columns, indexes, primaryKeys))
      });
      if (requestId !== requestIdRef.current) return;
      setPreview(response.sql);
      setMessage(response.message);
      setConfirmOpen(true);
    } catch (e) {
      if ((e as Error).name !== 'AbortError' && requestId === requestIdRef.current) setMessage(localizeMessage((e as Error).message));
    } finally {
      if (requestId === requestIdRef.current) setSubmitting(false);
      if (requestAbortRef.current === controller) requestAbortRef.current = null;
    }
  }

  async function executeDesign() {
    if (!connectionId) return;
    requestAbortRef.current?.abort();
    const controller = new AbortController();
    requestAbortRef.current = controller;
    const requestId = ++requestIdRef.current;
    setSubmitting(true);
    try {
      const response = await api<TableDesignResponse>(`/metadata/${connectionId}/objects/design/execute`, {
        method: 'POST',
        signal: controller.signal,
        headers: productionConfirmationHeaders(productionConfirmationText ? productionConfirmation : undefined),
        body: JSON.stringify({ ...designRequest(detail, columns, indexes, primaryKeys), confirmation })
      });
      if (requestId !== requestIdRef.current) return;
      setPreview(response.sql);
      setMessage(response.message);
      setConfirmOpen(false);
      setBaselineSignature(currentSignature);
      onDirtyChange(false);
      onReloadDetail();
    } catch (e) {
      if ((e as Error).name !== 'AbortError' && requestId === requestIdRef.current) setMessage(localizeMessage((e as Error).message));
    } finally {
      if (requestId === requestIdRef.current) setSubmitting(false);
      if (requestAbortRef.current === controller) requestAbortRef.current = null;
    }
  }

  function resetDesign() {
    const nextColumns = designColumns(detail);
    const nextIndexes = designIndexes(detail);
    const nextPrimaryKeys = [...detail.primaryKeys];
    setColumns(nextColumns);
    setIndexes(nextIndexes);
    setPrimaryKeys(nextPrimaryKeys);
    setPreview([]);
    setMessage('已撤销未保存的结构修改');
  }

  return (
    <div className="table-designer">
      <div className="designer-notices">
        {readonlyConnection && <Alert type="warning" showIcon title="当前连接为只读连接，不能执行结构变更。" />}
        {unsupported && <Alert type="info" showIcon title="当前数据库方言尚未通过表设计器契约验证，请使用数据库原生工具执行 DDL。" />}
        {detail.type.toUpperCase().includes('VIEW') && <Alert type="info" showIcon title="视图暂不支持表设计器。" />}
        {message && <Alert type={message.includes('失败') || message.includes('不') ? 'error' : 'info'} showIcon title={message} />}
      </div>
      <TableDefinitionEditor
        mode="edit"
        columns={columns}
        indexes={indexes}
        primaryKeys={primaryKeys}
        disabled={disabled}
        dirty={dirty}
        onReset={resetDesign}
        setColumns={setColumns}
        setIndexes={setIndexes}
        setPrimaryKeys={setPrimaryKeys}
      />
      <div className="designer-actions">
        <Button type="primary" disabled={disabled} loading={submitting} onClick={previewDesign}>预览 DDL</Button>
      </div>
      <Modal
        title="确认执行结构变更"
        open={confirmOpen}
        confirmLoading={submitting}
        mask={{ closable: !submitting }}
        keyboard={!submitting}
        cancelButtonProps={{ disabled: submitting }}
        okButtonProps={{ disabled: confirmation !== tableName || preview.length === 0 || Boolean(productionConfirmationText && productionConfirmation !== productionConfirmationText) }}
        okText="执行 DDL"
        onOk={executeDesign}
        onCancel={() => { if (!submitting) setConfirmOpen(false); }}
      >
        <Alert type="warning" showIcon title="请核对最终 DDL" description="结构变更可能不可回滚。" />
        <TypedConfirmationFields
          target={{ expected: tableName, value: confirmation, ariaLabel: '表结构变更确认文本', onChange: setConfirmation }}
          production={productionConfirmationText ? { expected: productionConfirmationText, value: productionConfirmation, ariaLabel: '表结构变更生产确认', onChange: setProductionConfirmation } : undefined}
        />
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

function aggregateIndexRows(detail: ObjectDetail): IndexRow[] {
  const grouped = new Map<string, Omit<IndexRow, 'primary'>>();
  detail.indexes.slice().sort((left, right) => (left.ordinalPosition || 0) - (right.ordinalPosition || 0)).forEach((index) => {
    const current = grouped.get(index.name);
    if (current) {
      current.columns.push(index.columnName);
    } else {
      grouped.set(index.name, {
        key: index.name,
        name: index.name,
        columns: [index.columnName],
        unique: index.unique
      });
    }
  });
  return [...grouped.values()].map((index) => ({
    ...index,
    primary: detail.primaryKeyName
      ? index.name === detail.primaryKeyName
      : detail.primaryKeys.length > 0 && sameColumns(index.columns, detail.primaryKeys)
  }));
}

function sameColumns(left: string[], right: string[]) {
  return left.length === right.length && left.every((column, index) => column === right[index]);
}

function designRequest(detail: ObjectDetail, columns: DesignColumnRow[], indexes: DesignIndexRow[], primaryKeys: string[]): TableDesignRequest {
  return {
    schemaName: detail.schemaName,
    tableName: detail.name,
    columns: serializeColumns(columns),
    indexes: serializeIndexes(indexes),
    primaryKeys,
    structureVersion: detail.structureVersion
  };
}
