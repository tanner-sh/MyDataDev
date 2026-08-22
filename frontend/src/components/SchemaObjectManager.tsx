import Editor from './SqlEditor';
import {
  ApiOutlined,
  CodeOutlined,
  DeleteOutlined,
  EyeOutlined,
  FunctionOutlined,
  MoreOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  ThunderboltOutlined
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Input,
  Modal,
  Space,
  Spin,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message as antdMessage
} from 'antd';
import type { MenuProps, TableColumnsType } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { api } from '../api';
import {
  schemaObjectCapabilities,
  schemaObjectConfirmationTarget,
  schemaObjectDisplayStatus,
  schemaObjectKindLabel
} from '../schemaObjectModel';
import type { ExplorerObjectCount } from '../schemaObjectModel';
import type {
  Connection,
  DbObject,
  RoutineArgumentInput,
  RoutineInvokeResponse,
  SchemaObjectCapability,
  SchemaObjectDetail,
  SchemaObjectKind,
  SchemaObjectLifecycleOperation,
  SchemaObjectLifecycleRequest,
  SchemaObjectLifecycleResponse,
  SchemaObjectPage,
  SchemaObjectParameter,
  SchemaObjectSummary,
  SchemaObjectTemplate
} from '../types';
import { ResultGrid } from './ResultGrid';
import { SqlPreview } from './SqlPreview';
import { TypedConfirmationFields } from './TypedConfirmationFields';
import { productionConfirmationHeaders } from '../productionConfirmation';

const { Text, Title } = Typography;

type GroupState = {
  items: SchemaObjectSummary[];
  page: number;
  total: number;
  totalExact: boolean;
  hasMore: boolean;
  loading: boolean;
  cachedAt?: string;
  cacheHit?: boolean;
  error?: string;
};
export type SchemaObjectListSummary = ExplorerObjectCount & { error?: string };
type WorkspaceState = { object?: SchemaObjectSummary; creation?: SchemaObjectTemplate };
type PendingLifecycle = { operation: SchemaObjectLifecycleOperation; request: SchemaObjectLifecycleRequest; sql: string[]; confirmationTarget: string };

export function SchemaObjectManager({
  connection,
  schemaName,
  keyword,
  activeKind,
  createKind,
  refreshToken,
  loadMoreToken,
  onCloseCreate,
  onSummaryChange,
  onOpenViewData
}: {
  connection: Connection;
  schemaName?: string;
  keyword?: string;
  activeKind: SchemaObjectKind;
  createKind?: SchemaObjectKind;
  refreshToken: number;
  loadMoreToken: number;
  onCloseCreate: () => void;
  onSummaryChange: (kind: SchemaObjectKind, summary?: SchemaObjectListSummary) => void;
  onOpenViewData: (object: DbObject) => void;
}) {
  const capabilities = useMemo(() => schemaObjectCapabilities(connection.capabilities), [connection.capabilities]);
  const [groups, setGroups] = useState<Partial<Record<SchemaObjectKind, GroupState>>>({});
  const [workspace, setWorkspace] = useState<WorkspaceState>();
  const [createName, setCreateName] = useState('');
  const [creatingTemplate, setCreatingTemplate] = useState(false);
  const [messageApi, messageContextHolder] = antdMessage.useMessage();
  const requestScope = `${connection.id}:${schemaName || ''}:${keyword || ''}`;
  const workspaceScope = `${connection.id}:${schemaName || ''}`;
  const requestScopeRef = useRef(requestScope);
  const refreshTokenRef = useRef(refreshToken);
  const loadMoreTokenRef = useRef(loadMoreToken);
  const activeCapability = capabilities.find((capability) => capability.kind === activeKind);

  useEffect(() => {
    requestScopeRef.current = requestScope;
    setGroups({});
  }, [requestScope]);

  useEffect(() => {
    if (refreshTokenRef.current === refreshToken) return;
    refreshTokenRef.current = refreshToken;
    if (activeKind) void loadGroup(activeKind, 0, false, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshToken]);

  useEffect(() => {
    setWorkspace(undefined);
    setCreateName('');
  }, [workspaceScope]);

  useEffect(() => {
    if (activeKind && activeCapability && !groups[activeKind]) void loadGroup(activeKind);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeKind, activeCapability, groups]);

  useEffect(() => {
    if (loadMoreTokenRef.current === loadMoreToken) return;
    loadMoreTokenRef.current = loadMoreToken;
    if (!activeKind) return;
    const group = groups[activeKind];
    if (group?.hasMore && !group.loading) void loadGroup(activeKind, group.page + 1, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadMoreToken]);

  useEffect(() => {
    if (!activeKind) return;
    const group = groups[activeKind];
    onSummaryChange(activeKind, group ? {
      loaded: group.items.length,
      total: (group.loading && group.items.length === 0) || !group.totalExact ? undefined : group.total,
      hasMore: group.hasMore,
      loading: group.loading,
      cachedAt: group.cachedAt,
      cacheHit: group.cacheHit,
      error: group.error
    } : undefined);
  }, [activeKind, groups, onSummaryChange]);

  if (capabilities.length === 0) return null;

  async function loadGroup(kind: SchemaObjectKind, page = 0, append = false, refresh = false) {
    const scope = requestScopeRef.current;
    setGroups((current) => ({
      ...current,
      [kind]: { ...(current[kind] || { items: [], page: 0, total: 0, totalExact: false, hasMore: false }), loading: true, error: undefined }
    }));
    const params = new URLSearchParams({ kind, page: String(page), pageSize: '100' });
    if (schemaName) params.set('schemaName', schemaName);
    if (keyword?.trim()) params.set('keyword', keyword.trim());
    if (refresh) params.set('refresh', 'true');
    try {
      const response = await api<SchemaObjectPage>(`/metadata/${connection.id}/schema-objects?${params.toString()}`);
      if (requestScopeRef.current !== scope) return;
      setGroups((current) => ({
        ...current,
        [kind]: {
          items: append ? [...(current[kind]?.items || []), ...response.items] : response.items,
          page: response.page,
          total: response.total,
          totalExact: response.totalExact,
          hasMore: response.hasMore,
          loading: false,
          cachedAt: response.cachedAt,
          cacheHit: response.cacheHit
        }
      }));
    } catch (error) {
      if (requestScopeRef.current !== scope) return;
      setGroups((current) => ({
        ...current,
        [kind]: {
          ...(current[kind] || { items: [], page: 0, total: 0, totalExact: false, hasMore: false }),
          loading: false,
          error: error instanceof Error ? error.message : '对象加载失败'
        }
      }));
    }
  }

  async function generateTemplate() {
    if (!createKind || !createName.trim()) return;
    const params = new URLSearchParams({ kind: createKind, objectName: createName.trim() });
    if (schemaName) params.set('schemaName', schemaName);
    setCreatingTemplate(true);
    try {
      const template = await api<SchemaObjectTemplate>(`/metadata/${connection.id}/schema-objects/template?${params.toString()}`);
      onCloseCreate();
      setCreateName('');
      setWorkspace({ creation: template });
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '创建模板生成失败');
    } finally {
      setCreatingTemplate(false);
    }
  }

  return (
    <section className="schema-object-manager" aria-label="数据库对象管理">
      {messageContextHolder}
      {activeCapability && (
        <SchemaObjectGroup
          capability={activeCapability}
          state={groups[activeCapability.kind]}
          activeObjectKey={workspace?.object?.objectKey}
          onRetry={() => void loadGroup(activeCapability.kind, 0, false, true)}
          onOpen={(object) => setWorkspace({ object })}
        />
      )}

      <Modal
        title={createKind ? `新建${schemaObjectKindLabel(createKind)}` : '新建对象'}
        open={Boolean(createKind)}
        okText="生成源码模板"
        confirmLoading={creatingTemplate}
        okButtonProps={{ disabled: !createName.trim() }}
        onOk={() => void generateTemplate()}
        onCancel={() => { onCloseCreate(); setCreateName(''); }}
      >
        <Text type="secondary">模板会使用当前 {schemaName || '默认命名空间'}，创建前仍可编辑完整源码。</Text>
        <Input autoFocus className="schema-object-create-name" value={createName} placeholder="输入对象名" onChange={(event) => setCreateName(event.target.value)} onPressEnter={() => void generateTemplate()} />
      </Modal>

      <SchemaObjectWorkspace
        connection={connection}
        state={workspace}
        onClose={() => setWorkspace(undefined)}
        onChanged={(kind, close) => {
          void loadGroup(kind, 0, false, true);
          if (close) setWorkspace(undefined);
        }}
        onOpenViewData={onOpenViewData}
      />
    </section>
  );
}

function SchemaObjectGroup({ capability, state, activeObjectKey, onRetry, onOpen }: {
  capability: SchemaObjectCapability;
  state?: GroupState;
  activeObjectKey?: string;
  onRetry: () => void;
  onOpen: (object: SchemaObjectSummary) => void;
}) {
  if (!state || state.loading && state.items.length === 0) return <div className="schema-object-group-status"><Spin size="small" /> 正在加载…</div>;
  if (state.error) return <Alert type="error" showIcon message={state.error} action={<Button size="small" onClick={onRetry}>重试</Button>} />;
  if (state.items.length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`暂无${schemaObjectKindLabel(capability.kind)}`} />;
  return (
      <div className="schema-object-list" role="list">
        {state.items.map((object) => (
          <button className={`schema-object-list-item${activeObjectKey === object.objectKey ? ' is-selected' : ''}`} type="button" role="listitem" key={object.objectKey} onClick={() => onOpen(object)}>
            <span className="schema-object-list-icon"><SchemaObjectIcon kind={object.kind} /></span>
            <span className="schema-object-list-copy">
              <span className="schema-object-list-name" title={object.displayName}>{object.displayName}</span>
              {object.status && <span className="schema-object-list-status">{schemaObjectDisplayStatus(object.status)}</span>}
            </span>
            <MoreOutlined />
          </button>
        ))}
        {state.loading && <div className="schema-object-group-status"><Spin size="small" /> 正在加载更多…</div>}
      </div>
  );
}

function SchemaObjectWorkspace({ connection, state, onClose, onChanged, onOpenViewData }: {
  connection: Connection;
  state?: WorkspaceState;
  onClose: () => void;
  onChanged: (kind: SchemaObjectKind, close: boolean) => void;
  onOpenViewData: (object: DbObject) => void;
}) {
  const [detail, setDetail] = useState<SchemaObjectDetail>();
  const [source, setSource] = useState('');
  const [initialSource, setInitialSource] = useState('');
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('overview');
  const [pending, setPending] = useState<PendingLifecycle>();
  const [confirmation, setConfirmation] = useState('');
  const [productionConfirmation, setProductionConfirmation] = useState('');
  const [executing, setExecuting] = useState(false);
  const [invokeOpen, setInvokeOpen] = useState(false);
  const [invokeInputs, setInvokeInputs] = useState<Record<number, RoutineArgumentInput>>({});
  const [invokeResult, setInvokeResult] = useState<RoutineInvokeResponse>();
  const [invoking, setInvoking] = useState(false);
  const [invokeProductionConfirmation, setInvokeProductionConfirmation] = useState('');
  const [messageApi, messageContextHolder] = antdMessage.useMessage();
  const [modalApi, modalContextHolder] = Modal.useModal();
  const objectKey = state?.object?.objectKey;

  useEffect(() => {
    setDetail(undefined);
    setInvokeResult(undefined);
    setActiveTab(state?.creation ? 'source' : 'overview');
    if (state?.creation) {
      setSource(state.creation.source);
      setInitialSource(state.creation.source);
      return;
    }
    if (objectKey) void loadDetail(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [objectKey, state?.creation?.objectName]);

  if (!state) return null;
  const creation = state.creation;
  const object = detail?.object || state.object;
  const kind = creation?.kind || object?.kind;
  const dirty = source !== initialSource;
  const operations = new Set(detail?.operations || []);
  const production = connection.environment === 'prod';

  async function loadDetail(refresh: boolean) {
    if (!objectKey) return;
    setLoading(true);
    try {
      const params = new URLSearchParams({ objectKey });
      if (refresh) params.set('refresh', 'true');
      const loaded = await api<SchemaObjectDetail>(`/metadata/${connection.id}/schema-objects/detail?${params.toString()}`);
      setDetail(loaded);
      setSource(loaded.source || '');
      setInitialSource(loaded.source || '');
      setInvokeInputs(Object.fromEntries(loaded.parameters
        .filter((parameter) => parameter.mode === 'IN' || parameter.mode === 'INOUT')
        .map((parameter) => [parameter.position, { position: parameter.position, name: parameter.name, value: '', nullValue: false }])));
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '对象详情加载失败');
    } finally {
      setLoading(false);
    }
  }

  function lifecycleRequest(operation: SchemaObjectLifecycleOperation): SchemaObjectLifecycleRequest {
    if (creation) {
      return { operation, kind: creation.kind, schemaName: creation.schemaName, objectName: creation.objectName, source };
    }
    if (!detail) throw new Error('对象详情尚未加载');
    return {
      operation,
      kind: detail.object.kind,
      schemaName: detail.object.schemaName,
      objectName: detail.object.name,
      objectKey: detail.object.objectKey,
      source: operation === 'REPLACE' ? source : undefined,
      structureVersion: detail.structureVersion
    };
  }

  async function previewLifecycle(operation: SchemaObjectLifecycleOperation) {
    if (!kind) return;
    setExecuting(true);
    try {
      const request = lifecycleRequest(operation);
      const preview = await api<SchemaObjectLifecycleResponse>(`/metadata/${connection.id}/schema-objects/lifecycle/preview`, {
        method: 'POST', body: JSON.stringify(request)
      });
      const confirmationTarget = creation
        ? (creation.schemaName ? `${creation.schemaName}.${creation.objectName}` : creation.objectName)
        : schemaObjectConfirmationTarget(detail!.object);
      setPending({ operation, request, sql: preview.sql, confirmationTarget });
      setConfirmation('');
      setProductionConfirmation('');
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : 'SQL 预览失败');
    } finally {
      setExecuting(false);
    }
  }

  async function executeLifecycle() {
    if (!pending || !kind) return;
    setExecuting(true);
    try {
      const response = await api<SchemaObjectLifecycleResponse>(`/metadata/${connection.id}/schema-objects/lifecycle/execute`, {
        method: 'POST',
        headers: productionConfirmationHeaders(production ? productionConfirmation : undefined),
        body: JSON.stringify({ ...pending.request, confirmation })
      });
      messageApi.success(response.message);
      setPending(undefined);
      setConfirmation('');
      setProductionConfirmation('');
      const close = pending.operation === 'CREATE' || pending.operation === 'DROP';
      onChanged(kind, close);
      if (!close) await loadDetail(true);
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '对象操作失败');
    } finally {
      setExecuting(false);
    }
  }

  async function invokeRoutine() {
    if (!detail) return;
    setInvoking(true);
    try {
      const result = await api<RoutineInvokeResponse>(`/metadata/${connection.id}/schema-objects/invoke`, {
        method: 'POST',
        headers: productionConfirmationHeaders(production ? invokeProductionConfirmation : undefined),
        body: JSON.stringify({
          objectKey: detail.object.objectKey,
          structureVersion: detail.structureVersion,
          arguments: Object.values(invokeInputs)
        })
      });
      setInvokeResult(result);
      setInvokeOpen(false);
      setActiveTab('results');
      messageApi.success(`调用完成，耗时 ${result.elapsedMs}ms`);
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '例程调用失败');
    } finally {
      setInvoking(false);
    }
  }

  function closeWorkspace() {
    if (!dirty) {
      onClose();
      return;
    }
    modalApi.confirm({ title: '放弃未保存的源码修改？', content: '关闭后当前修改将丢失。', okText: '放弃修改', cancelText: '继续编辑', okButtonProps: { danger: true }, onOk: onClose });
  }

  const menuItems: NonNullable<MenuProps['items']> = detail ? [
    operations.has('REFRESH') ? { key: 'REFRESH', icon: <ReloadOutlined />, label: '刷新物化视图' } : null,
    operations.has('ENABLE') ? { key: 'ENABLE', icon: <PlayCircleOutlined />, label: '启用触发器' } : null,
    operations.has('DISABLE') ? { key: 'DISABLE', icon: <PauseCircleOutlined />, label: '禁用触发器' } : null,
    operations.has('DROP') ? { type: 'divider' as const } : null,
    operations.has('DROP') ? { key: 'DROP', icon: <DeleteOutlined />, label: '删除对象', danger: true } : null
  ].filter(Boolean) as NonNullable<MenuProps['items']> : [];

  const tabs = [
    !creation && { key: 'overview', label: '概览', children: detail ? <ObjectOverview detail={detail} /> : <Spin /> },
    { key: 'source', label: '源码', children: (
      <div className="schema-object-source-panel">
        {!creation && detail && !detail.sourceAvailable && <Alert type="warning" showIcon message="无法读取对象源码" description={detail.sourceUnavailableReason} />}
        <Editor
          height="52vh"
          language="sql"
          value={source}
          theme={document.documentElement.dataset.theme === 'dark' ? 'vs-dark' : 'vs'}
          options={{ minimap: { enabled: false }, fontSize: 13, automaticLayout: true, readOnly: !creation && (!detail?.sourceAvailable || !operations.has('REPLACE')) }}
          onChange={(value) => setSource(value || '')}
        />
      </div>
    ) },
    !creation && { key: 'parameters', label: `参数 ${detail?.parameters.length || 0}`, children: detail ? <ParameterTable parameters={detail.parameters} /> : null },
    !creation && { key: 'dependencies', label: `依赖 ${detail?.dependencies.length || 0}`, children: detail ? <DependencyPanel detail={detail} /> : null },
    invokeResult && { key: 'results', label: '调用结果', children: <RoutineResults response={invokeResult} /> }
  ].filter(Boolean) as { key: string; label: string; children: ReactNode }[];

  return (
    <>
      {messageContextHolder}
      {modalContextHolder}
      <Drawer
        open
        size="large"
        title={<Space><SchemaObjectIcon kind={kind!} /><span>{creation ? `新建${schemaObjectKindLabel(kind!)}` : object?.displayName}</span>{object?.status && <Tag>{schemaObjectDisplayStatus(object.status)}</Tag>}</Space>}
        extra={(
          <Space>
            {!creation && object?.kind === 'VIEW' && <Button icon={<EyeOutlined />} onClick={() => { onClose(); onOpenViewData({ schemaName: object.schemaName, name: object.name, type: 'VIEW', columns: [], indexes: [] }); }}>查看数据</Button>}
            {!creation && operations.has('INVOKE') && !connection.readonly && <Button type="primary" icon={<ThunderboltOutlined />} onClick={() => setInvokeOpen(true)}>调用</Button>}
            {creation && !connection.readonly && <Button type="primary" icon={<PlayCircleOutlined />} loading={executing} onClick={() => void previewLifecycle('CREATE')}>预览并创建</Button>}
            {!creation && operations.has('REPLACE') && detail?.sourceAvailable && !connection.readonly && <Button type="primary" icon={<CodeOutlined />} disabled={!dirty} loading={executing} onClick={() => void previewLifecycle('REPLACE')}>预览并保存</Button>}
            {!creation && <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void loadDetail(true)}>刷新</Button>}
            {!creation && menuItems.length > 0 && !connection.readonly && <Dropdown menu={{ items: menuItems, onClick: ({ key }) => void previewLifecycle(key as SchemaObjectLifecycleOperation) }}><Button icon={<MoreOutlined />} /></Dropdown>}
          </Space>
        )}
        onClose={closeWorkspace}
      >
        {loading && !detail && !creation ? <div className="schema-object-workspace-loading"><Spin /> 正在加载对象详情…</div> : <Tabs activeKey={activeTab} items={tabs} onChange={setActiveTab} />}
      </Drawer>

      <Modal
        title={`${pending ? lifecycleLabel(pending.operation) : '对象操作'}确认`}
        open={Boolean(pending)}
        width={720}
        okText="确认执行"
        okButtonProps={{
          danger: pending?.operation === 'DROP' || pending?.operation === 'DISABLE',
          disabled: !pending || confirmation !== pending.confirmationTarget || production && productionConfirmation !== connection.name
        }}
        confirmLoading={executing}
        onOk={() => void executeLifecycle()}
        onCancel={() => setPending(undefined)}
      >
        {pending && <>
          <Alert type="warning" showIcon message="请核对最终 SQL" description="对象定义会作为单条原生语句执行；删除操作不会自动添加 CASCADE。" />
          <SqlPreview sql={pending.sql} />
          <TypedConfirmationFields
            target={{ expected: pending.confirmationTarget, value: confirmation, ariaLabel: '输入完整对象名确认', onChange: setConfirmation }}
            production={production ? { expected: connection.name, value: productionConfirmation, ariaLabel: '输入生产连接名确认', onChange: setProductionConfirmation } : undefined}
          />
        </>}
      </Modal>

      <Modal
        title={`调用 ${detail?.object.displayName || ''}`}
        open={invokeOpen}
        width={640}
        okText="执行调用"
        confirmLoading={invoking}
        okButtonProps={{ disabled: production && invokeProductionConfirmation !== connection.name }}
        onOk={() => void invokeRoutine()}
        onCancel={() => setInvokeOpen(false)}
      >
        <Alert type="warning" showIcon message="例程可能包含写入或结构变更" description="调用参数值不会写入审计或 SQL 历史。" />
        <div className="routine-argument-list">
          {detail?.parameters.filter((parameter) => parameter.mode !== 'RETURN').map((parameter) => {
            const input = invokeInputs[parameter.position];
            const acceptsInput = parameter.mode === 'IN' || parameter.mode === 'INOUT';
            return (
              <div className="routine-argument-row" key={`${parameter.position}:${parameter.name}`}>
                <div><Text strong>{parameter.name || `参数 ${parameter.position}`}</Text> <Tag>{parameter.mode}</Tag><Text type="secondary">{parameter.typeName}</Text></div>
                {acceptsInput ? <Space.Compact block>
                  <Input disabled={input?.nullValue} value={input?.value || ''} placeholder="输入参数值" onChange={(event) => setInvokeInputs((current) => ({ ...current, [parameter.position]: { ...current[parameter.position], position: parameter.position, name: parameter.name, value: event.target.value, nullValue: false } }))} />
                  <span className="routine-null-toggle"><Switch checked={input?.nullValue} onChange={(checked) => setInvokeInputs((current) => ({ ...current, [parameter.position]: { ...current[parameter.position], position: parameter.position, name: parameter.name, value: current[parameter.position]?.value || '', nullValue: checked } }))} /> NULL</span>
                </Space.Compact> : <Text type="secondary">输出参数，无需填写</Text>}
              </div>
            );
          })}
          {detail?.parameters.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该例程没有参数" />}
        </div>
        {production && <TypedConfirmationFields
          autoFocus="none"
          production={{ expected: connection.name, value: invokeProductionConfirmation, ariaLabel: '输入生产连接名确认调用', onChange: setInvokeProductionConfirmation }}
        />}
      </Modal>
    </>
  );
}

function ObjectOverview({ detail }: { detail: SchemaObjectDetail }) {
  return (
    <Descriptions bordered size="small" column={1}>
      <Descriptions.Item label="对象名">{schemaObjectConfirmationTarget(detail.object)}</Descriptions.Item>
      <Descriptions.Item label="类型">{schemaObjectKindLabel(detail.object.kind)}</Descriptions.Item>
      <Descriptions.Item label="子类型">{detail.object.subtype || '—'}</Descriptions.Item>
      <Descriptions.Item label="状态">{schemaObjectDisplayStatus(detail.object.status) || '—'}</Descriptions.Item>
      <Descriptions.Item label="可用操作"><Space wrap>{detail.operations.map((operation) => <Tag key={operation}>{operation}</Tag>)}</Space></Descriptions.Item>
      <Descriptions.Item label="版本"><Text copyable code>{detail.structureVersion}</Text></Descriptions.Item>
    </Descriptions>
  );
}

function ParameterTable({ parameters }: { parameters: SchemaObjectParameter[] }) {
  const columns: TableColumnsType<SchemaObjectParameter> = [
    { title: '位置', dataIndex: 'position', width: 70 },
    { title: '名称', dataIndex: 'name', render: (value) => value || '—' },
    { title: '模式', dataIndex: 'mode', width: 90, render: (value) => <Tag>{value}</Tag> },
    { title: '类型', dataIndex: 'typeName' },
    { title: '可空', dataIndex: 'nullable', width: 70, render: (value) => value ? '是' : '否' }
  ];
  return <Table size="small" rowKey={(row) => `${row.position}:${row.name}`} columns={columns} dataSource={parameters} pagination={false} locale={{ emptyText: '该对象没有参数' }} />;
}

function DependencyPanel({ detail }: { detail: SchemaObjectDetail }) {
  if (!detail.dependenciesAvailable) return <Alert type="info" showIcon message="依赖信息不可用" description={detail.dependenciesUnavailableReason} />;
  if (detail.dependencies.length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未发现可靠的对象依赖" />;
  return <div className="schema-object-dependency-list">{detail.dependencies.map((item, index) => <div className="schema-object-dependency-item" key={`${item.schemaName}:${item.name}:${index}`}><Space><Tag>{item.kind}</Tag><Text>{item.schemaName ? `${item.schemaName}.` : ''}{item.name}</Text><Text type="secondary">{item.direction}</Text></Space></div>)}</div>;
}

function RoutineResults({ response }: { response: RoutineInvokeResponse }) {
  return (
    <div className="routine-results">
      <Alert type={response.truncated ? 'warning' : 'success'} showIcon message={`调用成功 · ${response.elapsedMs}ms${response.truncated ? ' · 结果已截断' : ''}`} />
      {(response.returnValue !== undefined || response.outParameters.length > 0) && (
        <Descriptions bordered size="small" column={1}>
          {response.returnValue !== undefined && <Descriptions.Item label="返回值"><Text code>{renderValue(response.returnValue)}</Text></Descriptions.Item>}
          {response.outParameters.map((parameter, index) => <Descriptions.Item key={`${parameter.name}:${index}`} label={`${parameter.name || `OUT ${index + 1}`} · ${parameter.typeName || ''}`}><Text code>{renderValue(parameter.value)}</Text></Descriptions.Item>)}
        </Descriptions>
      )}
      {response.results.map((item, index) => item.kind === 'RESULT_SET'
        ? <div className="routine-result-set" key={index}><Title level={5}>结果集 {index + 1}</Title><ResultGrid result={item.result || null} pagingEnabled={false} /></div>
        : <Alert key={index} type="info" message={`更新计数：${item.updateCount ?? 0}`} />)}
      {response.results.length === 0 && response.returnValue === undefined && response.outParameters.length === 0 && <Empty description="调用没有返回结果" />}
    </div>
  );
}

function SchemaObjectIcon({ kind }: { kind: SchemaObjectKind }) {
  if (kind === 'VIEW' || kind === 'MATERIALIZED_VIEW') return <EyeOutlined />;
  if (kind === 'FUNCTION') return <FunctionOutlined />;
  if (kind === 'PROCEDURE') return <ApiOutlined />;
  if (kind === 'TRIGGER') return <ThunderboltOutlined />;
  return <CodeOutlined />;
}

function lifecycleLabel(operation: SchemaObjectLifecycleOperation) {
  return ({ CREATE: '创建', REPLACE: '保存', DROP: '删除', REFRESH: '刷新', ENABLE: '启用', DISABLE: '禁用' } as const)[operation];
}

function renderValue(value: unknown) {
  if (value == null) return 'NULL';
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value); } catch { return String(value); }
}
