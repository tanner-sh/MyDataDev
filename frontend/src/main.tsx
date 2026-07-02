import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import Editor from '@monaco-editor/react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Collapse,
  ConfigProvider,
  Empty,
  Form,
  Input,
  Layout,
  List,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import zhCN from 'antd/locale/zh_CN';
import {
  CopyOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  TableOutlined
} from '@ant-design/icons';
import 'antd/dist/reset.css';
import './styles.css';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

const API = 'http://localhost:8080/api';

const DB_TYPE_OPTIONS = [
  { value: 'h2', label: 'H2', url: 'jdbc:h2:mem:testdb' },
  { value: 'mysql', label: 'MySQL', url: 'jdbc:mysql://localhost:3306/demo' },
  { value: 'postgresql', label: 'PostgreSQL', url: 'jdbc:postgresql://localhost:5432/demo' },
  { value: 'oracle', label: 'Oracle', url: 'jdbc:oracle:thin:@//localhost:1521/ORCLPDB1' },
  { value: 'sqlserver', label: 'SQL Server', url: 'jdbc:sqlserver://localhost:1433;databaseName=demo' },
  { value: 'sqlite', label: 'SQLite', url: 'jdbc:sqlite:/tmp/demo.db' },
  { value: 'mariadb', label: 'MariaDB', url: 'jdbc:mariadb://localhost:3306/demo' },
  { value: 'clickhouse', label: 'ClickHouse', url: 'jdbc:clickhouse://localhost:8123/default' }
];

const ENVIRONMENT_OPTIONS = [
  { value: 'dev', label: '开发' },
  { value: 'test', label: '测试' },
  { value: 'prod', label: '生产' }
];

const PASSWORD_MASK = '******';

type Connection = {
  id: number;
  name: string;
  dbType: string;
  jdbcUrl: string;
  username?: string;
  environment: string;
  readonly: boolean;
};

type DbObject = {
  schemaName?: string;
  name: string;
  type: string;
  columns: { name: string; type: string; size: number; nullable: boolean }[];
  indexes: { name: string; columnName: string; unique: boolean }[];
};

type Metadata = { schemas: string[]; objects: DbObject[] };
type SqlResult = { columns: string[]; rows: Record<string, unknown>[]; affectedRows: number; elapsedMs: number; resultSet: boolean };
type BackupTask = { id: number; name: string; connectionId: number; scope: string; cron?: string; lastStatus?: string; lastMessage?: string };
type ActiveTable = { schemaName?: string; tableName: string };
type TableRow = { id: string; values: Record<string, unknown>; original?: Record<string, unknown>; deleted?: boolean; inserted?: boolean };
type TableData = { columns: string[]; rows: Record<string, unknown>[]; keyColumns: string[]; editable: boolean };
type RowChange = { type: 'INSERT' | 'UPDATE' | 'DELETE'; key?: Record<string, unknown>; values?: Record<string, unknown> };
type ConnectionForm = { name: string; dbType: string; jdbcUrl: string; username: string; password: string; environment: string; readonly: boolean };
type ResultRow = { key: string } & Record<string, unknown>;
type EditableRow = TableRow;

const EMPTY_FORM: ConnectionForm = {
  name: '本地 H2',
  dbType: 'h2',
  jdbcUrl: 'jdbc:h2:mem:testdb',
  username: 'sa',
  password: '',
  environment: 'dev',
  readonly: false
};

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', 'X-User': 'admin', ...(init?.headers || {}) }
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText }));
    throw new Error(err.message || res.statusText);
  }
  return res.json();
}

function App() {
  const [connections, setConnections] = useState<Connection[]>([]);
  const [selected, setSelected] = useState<Connection | null>(null);
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [sql, setSql] = useState('select 1 as val');
  const [result, setResult] = useState<SqlResult | null>(null);
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [backups, setBackups] = useState<BackupTask[]>([]);
  const [form, setForm] = useState<ConnectionForm>(EMPTY_FORM);
  const [editingConnectionId, setEditingConnectionId] = useState<number | null>(null);
  const [mode, setMode] = useState<'sql' | 'table'>('sql');
  const [activeTable, setActiveTable] = useState<ActiveTable | null>(null);
  const [tableData, setTableData] = useState<TableData | null>(null);
  const [tableRows, setTableRows] = useState<TableRow[]>([]);
  const [previewSql, setPreviewSql] = useState<string[]>([]);

  const objects = useMemo(() => metadata?.objects || [], [metadata]);
  const pendingChanges = useMemo(() => buildChanges(tableRows, tableData?.keyColumns || []), [tableRows, tableData]);

  useEffect(() => {
    refreshConnections();
    refreshBackups();
  }, []);

  async function refreshConnections() {
    const rows = await api<Connection[]>('/connections');
    setConnections(rows);
    setSelected((current: Connection | null) => current || rows[0] || null);
  }

  async function refreshBackups() {
    setBackups(await api<BackupTask[]>('/backups'));
  }

  async function saveConnection() {
    setLoading(true);
    try {
      const saved = editingConnectionId
        ? await api<Connection>(`/connections/${editingConnectionId}`, { method: 'PUT', body: JSON.stringify(form) })
        : await api<Connection>('/connections', { method: 'POST', body: JSON.stringify(form) });
      setSelected(saved);
      setEditingConnectionId(saved.id);
      setMessage(editingConnectionId ? `已更新连接：${saved.name}` : `已创建连接：${saved.name}`);
      await refreshConnections();
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  async function testConnection(target = form) {
    setLoading(true);
    try {
      if (editingConnectionId) {
        await api<{ ok: boolean; message: string }>(`/connections/${editingConnectionId}/test`, {
          method: 'POST',
          body: JSON.stringify(target)
        });
      } else {
        await api<{ ok: boolean; message: string }>('/connections/test', {
          method: 'POST',
          body: JSON.stringify({ jdbcUrl: target.jdbcUrl, username: target.username, password: target.password })
        });
      }
      setMessage(`连接测试成功：${target.name || target.jdbcUrl}`);
    } catch (e) {
      setMessage(`连接测试失败：${localizeMessage((e as Error).message)}`);
    } finally {
      setLoading(false);
    }
  }

  async function testSavedConnection(connection: Connection) {
    setLoading(true);
    try {
      await api<{ ok: boolean; message: string }>(`/connections/${connection.id}/test`, { method: 'POST' });
      setMessage(`连接测试成功：${connection.name}`);
    } catch (e) {
      setMessage(`连接测试失败：${localizeMessage((e as Error).message)}`);
    } finally {
      setLoading(false);
    }
  }

  async function deleteConnection(connection: Connection) {
    setLoading(true);
    try {
      await api<{ ok: boolean; message: string }>(`/connections/${connection.id}`, { method: 'DELETE' });
      setMessage(`已删除连接：${connection.name}`);
      setConnections((rows) => rows.filter((row) => row.id !== connection.id));
      if (selected?.id === connection.id) {
        setSelected(null);
        setMetadata(null);
        setMode('sql');
      }
      if (editingConnectionId === connection.id) {
        resetConnectionForm();
      }
      await refreshConnections();
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  function selectConnection(connection: Connection) {
    setSelected(connection);
    setMetadata(null);
    setMode('sql');
  }

  function editConnection(connection: Connection) {
    selectConnection(connection);
    setEditingConnectionId(connection.id);
    setForm({
      name: connection.name,
      dbType: connection.dbType,
      jdbcUrl: connection.jdbcUrl,
      username: connection.username || '',
      password: PASSWORD_MASK,
      environment: normalizeEnvironment(connection.environment),
      readonly: connection.readonly
    });
    setMessage(`正在编辑连接：${connection.name}。密码显示为 ${PASSWORD_MASK} 表示沿用已保存密码。`);
  }

  function duplicateConnection(connection: Connection) {
    setEditingConnectionId(null);
    setForm({
      name: `${connection.name} 副本`,
      dbType: connection.dbType,
      jdbcUrl: connection.jdbcUrl,
      username: connection.username || '',
      password: '',
      environment: normalizeEnvironment(connection.environment),
      readonly: connection.readonly
    });
    setMessage('已复制连接配置，请输入密码后保存为新连接。');
  }

  function resetConnectionForm() {
    setEditingConnectionId(null);
    setForm(EMPTY_FORM);
  }

  async function loadMetadata(conn = selected) {
    if (!conn) return;
    setLoading(true);
    try {
      const data = await api<Metadata>(`/metadata/${conn.id}`);
      setMetadata(data);
      setMessage(`已加载 ${data.objects.length} 个数据库对象`);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  async function execute(path = '/sql/execute') {
    if (!selected) {
      setMessage('请先选择一个数据库连接');
      return;
    }
    setMode('sql');
    setLoading(true);
    try {
      const data = await api<SqlResult>(path, { method: 'POST', body: JSON.stringify({ connectionId: selected.id, sql, maxRows: 500 }) });
      setResult(data);
      setMessage(data.resultSet ? `返回 ${data.rows.length} 行，用时 ${data.elapsedMs}ms` : `影响 ${data.affectedRows} 行`);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  async function formatSql() {
    const data = await api<{ sql: string }>('/sql/format', { method: 'POST', body: JSON.stringify({ sql }) });
    setSql(data.sql);
  }

  async function openTable(object: DbObject) {
    if (!selected || object.type.toUpperCase().includes('VIEW')) {
      return;
    }
    const next = { schemaName: object.schemaName, tableName: object.name };
    setActiveTable(next);
    setMode('table');
    await loadTable(next);
  }

  async function loadTable(table = activeTable) {
    if (!selected || !table) return;
    setLoading(true);
    try {
      const params = new URLSearchParams({
        connectionId: String(selected.id),
        tableName: table.tableName,
        page: '0',
        pageSize: '100'
      });
      if (table.schemaName) params.set('schemaName', table.schemaName);
      const data = await api<TableData>(`/data/table?${params.toString()}`);
      setTableData(data);
      setTableRows(data.rows.map((row, index) => ({ id: `row-${index}`, values: { ...row }, original: { ...row } })));
      setPreviewSql([]);
      setMessage(`已从 ${table.tableName} 加载 ${data.rows.length} 行数据`);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  function editCell(rowId: string, column: string, value: string) {
    setTableRows((rows) => rows.map((row) => row.id === rowId ? { ...row, values: { ...row.values, [column]: value } } : row));
    setPreviewSql([]);
  }

  function changeDbType(dbType: string) {
    const preset = DB_TYPE_OPTIONS.find((option) => option.value === dbType);
    setForm((current) => ({
      ...current,
      dbType,
      jdbcUrl: preset ? preset.url : current.jdbcUrl
    }));
  }

  function addRow() {
    if (!tableData) return;
    const empty = Object.fromEntries(tableData.columns.map((column) => [column, '']));
    setTableRows((rows) => [{ id: `new-${Date.now()}`, values: empty, inserted: true }, ...rows]);
    setPreviewSql([]);
  }

  function deleteRow(rowId: string) {
    setTableRows((rows) => rows.flatMap((row) => {
      if (row.id !== rowId) return [row];
      return row.inserted ? [] : [{ ...row, deleted: true }];
    }));
    setPreviewSql([]);
  }

  async function previewChanges() {
    if (!selected || !activeTable) return;
    if (!pendingChanges.length) {
      setMessage('没有待提交的变更');
      return;
    }
    setLoading(true);
    try {
      const data = await api<{ sql: string[] }>('/data/preview', { method: 'POST', body: JSON.stringify(dataChangePayload()) });
      setPreviewSql(data.sql);
      setMessage(`已生成 ${data.sql.length} 条变更语句`);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  async function commitChanges() {
    if (!selected || !activeTable) return;
    if (!pendingChanges.length) {
      setMessage('没有待提交的变更');
      return;
    }
    setLoading(true);
    try {
      const data = await api<{ sql: string[]; affectedRows: number }>('/data/commit', { method: 'POST', body: JSON.stringify(dataChangePayload()) });
      setPreviewSql(data.sql);
      setMessage(`已提交，影响 ${data.affectedRows} 行`);
      await loadTable(activeTable);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
  }

  function dataChangePayload() {
    return {
      connectionId: selected?.id,
      schemaName: activeTable?.schemaName,
      tableName: activeTable?.tableName,
      changes: pendingChanges
    };
  }

  async function createBackup() {
    if (!selected) return;
    const task = await api<BackupTask>('/backups', {
      method: 'POST',
      body: JSON.stringify({ name: `${selected.name} 全量备份`, connectionId: selected.id, scope: 'DATABASE', cron: '0 0 2 * * *', enabled: true })
    });
    setMessage(`已创建备份任务：${task.name}`);
    await refreshBackups();
  }

  async function runBackup(id: number) {
    const task = await api<BackupTask>(`/backups/${id}/run`, { method: 'POST' });
    setMessage(localizeMessage(task.lastMessage || '备份任务已执行'));
    await refreshBackups();
  }

  const statusMessage = loading ? '处理中...' : message || '就绪';

  return (
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#1f6feb', borderRadius: 6 } }}>
      <Layout className="app-shell">
        <Sider width={320} className="app-sider" theme="light">
          <Space direction="vertical" size={12} className="full-width">
            <div className="brand">
              <DatabaseOutlined />
              <span>数据库管理工具</span>
            </div>
            <Button type="primary" icon={<ReloadOutlined />} block loading={loading} onClick={refreshConnections}>
              刷新连接
            </Button>
            <ConnectionList
              connections={connections}
              selectedId={selected?.id}
              loading={loading}
              onEdit={editConnection}
              onTest={testSavedConnection}
              onDuplicate={duplicateConnection}
              onDelete={deleteConnection}
            />
            <Card size="small" title="数据库对象" className="panel-card">
              <Space direction="vertical" size={10} className="full-width">
                <Button icon={<TableOutlined />} block disabled={!selected || loading} onClick={() => loadMetadata()}>
                  加载对象
                </Button>
                <ObjectTree objects={objects} onOpenTable={openTable} />
              </Space>
            </Card>
          </Space>
        </Sider>

        <Content className="app-content">
          {mode === 'sql' ? (
            <SqlWorkspace
              selected={selected}
              sql={sql}
              result={result}
              statusMessage={statusMessage}
              loading={loading}
              onSqlChange={setSql}
              onFormat={formatSql}
              onExplain={() => execute('/sql/explain')}
              onExecute={() => execute()}
            />
          ) : (
            <TableWorkspace
              activeTable={activeTable}
              tableData={tableData}
              tableRows={tableRows}
              previewSql={previewSql}
              pendingCount={pendingChanges.length}
              statusMessage={`${statusMessage} · 待提交变更：${pendingChanges.length}`}
              loading={loading}
              onBackToSql={() => setMode('sql')}
              onReload={() => loadTable()}
              onAddRow={addRow}
              onPreview={previewChanges}
              onCommit={commitChanges}
              onEdit={editCell}
              onDelete={deleteRow}
            />
          )}
        </Content>

        <Sider width={360} className="app-inspector" theme="light">
          <Space direction="vertical" size={12} className="full-width">
            <ConnectionFormPanel
              form={form}
              editing={Boolean(editingConnectionId)}
              loading={loading}
              onChange={setForm}
              onDbTypeChange={changeDbType}
              onReset={resetConnectionForm}
              onTest={() => testConnection()}
              onSave={saveConnection}
            />
            <BackupPanel backups={backups} selected={selected} loading={loading} onCreate={createBackup} onRun={runBackup} />
          </Space>
        </Sider>
      </Layout>
    </ConfigProvider>
  );
}

function ConnectionList({ connections, selectedId, loading, onEdit, onTest, onDuplicate, onDelete }: {
  connections: Connection[];
  selectedId?: number;
  loading: boolean;
  onEdit: (connection: Connection) => void;
  onTest: (connection: Connection) => void;
  onDuplicate: (connection: Connection) => void;
  onDelete: (connection: Connection) => void;
}) {
  if (connections.length === 0) {
    return <Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据库连接" /></Card>;
  }
  return (
    <List
      className="connection-list"
      dataSource={connections}
      renderItem={(connection) => (
        <List.Item className={selectedId === connection.id ? 'connection-item selected' : 'connection-item'}>
          <Space direction="vertical" size={8} className="full-width">
            <button className="connection-title-button" onClick={() => onEdit(connection)}>
              <Space direction="vertical" size={2} className="full-width">
                <Space size={6} wrap>
                  <Text strong>{connection.name}</Text>
                  <Tag color="blue">{dbTypeLabel(connection.dbType)}</Tag>
                  <Tag>{environmentLabel(connection.environment)}</Tag>
                  {connection.readonly && <Tag color="orange">只读</Tag>}
                </Space>
                <Text type="secondary" className="ellipsis-text">{connection.jdbcUrl}</Text>
              </Space>
            </button>
            <Space size={4} wrap>
              <Button size="small" loading={loading} onClick={() => onTest(connection)}>测试</Button>
              <Button size="small" icon={<EditOutlined />} onClick={() => onEdit(connection)}>编辑</Button>
              <Button size="small" icon={<CopyOutlined />} onClick={() => onDuplicate(connection)}>复制</Button>
              <Popconfirm
                title="删除连接"
                description="确定删除该连接吗？有关联备份任务的连接会被后端拒绝删除。"
                okText="删除"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => onDelete(connection)}
              >
                <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            </Space>
          </Space>
        </List.Item>
      )}
    />
  );
}

function ObjectTree({ objects, onOpenTable }: { objects: DbObject[]; onOpenTable: (object: DbObject) => void }) {
  if (objects.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未加载对象" />;
  }
  return (
    <Collapse
      size="small"
      className="object-collapse"
      items={objects.map((object) => ({
        key: `${object.schemaName}.${object.name}`,
        label: (
          <Space size={6} className="object-label">
            <Button
              type="link"
              size="small"
              className="object-open-button"
              disabled={object.type.toUpperCase().includes('VIEW')}
              onClick={(event) => {
                event.stopPropagation();
                onOpenTable(object);
              }}
            >
              {object.schemaName ? `${object.schemaName}.` : ''}{object.name}
            </Button>
            <Tag>{objectTypeLabel(object.type)}</Tag>
          </Space>
        ),
        children: (
          <List
            size="small"
            dataSource={object.columns}
            renderItem={(column) => (
              <List.Item className="column-item">
                <Text>{column.name}</Text>
                <Text type="secondary">{column.type}</Text>
              </List.Item>
            )}
          />
        )
      }))}
    />
  );
}

function SqlWorkspace({ selected, sql, result, statusMessage, loading, onSqlChange, onFormat, onExplain, onExecute }: {
  selected: Connection | null;
  sql: string;
  result: SqlResult | null;
  statusMessage: string;
  loading: boolean;
  onSqlChange: (sql: string) => void;
  onFormat: () => void;
  onExplain: () => void;
  onExecute: () => void;
}) {
  return (
    <div className="workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Text strong>{selected ? selected.name : '未选择连接'}</Text>
          <Text type="secondary" className="ellipsis-text">{selected?.jdbcUrl || '请选择左侧数据库连接'}</Text>
        </div>
        <Space>
          <Button onClick={onFormat}>格式化</Button>
          <Button disabled={!selected || loading} onClick={onExplain}>执行计划</Button>
          <Button type="primary" icon={<PlayCircleOutlined />} disabled={!selected || loading} loading={loading} onClick={onExecute}>执行</Button>
        </Space>
      </Header>
      <div className="editor">
        <Editor height="100%" language="sql" value={sql} onChange={(value) => onSqlChange(value || '')} theme="vs-dark" options={{ minimap: { enabled: false }, fontSize: 14 }} />
      </div>
      <Alert className="status-alert" type={loading ? 'info' : 'success'} message={statusMessage} showIcon />
      <ResultGrid result={result} />
    </div>
  );
}

function TableWorkspace({ activeTable, tableData, tableRows, previewSql, pendingCount, statusMessage, loading, onBackToSql, onReload, onAddRow, onPreview, onCommit, onEdit, onDelete }: {
  activeTable: ActiveTable | null;
  tableData: TableData | null;
  tableRows: TableRow[];
  previewSql: string[];
  pendingCount: number;
  statusMessage: string;
  loading: boolean;
  onBackToSql: () => void;
  onReload: () => void;
  onAddRow: () => void;
  onPreview: () => void;
  onCommit: () => void;
  onEdit: (rowId: string, column: string, value: string) => void;
  onDelete: (rowId: string) => void;
}) {
  const tableName = activeTable ? `${activeTable.schemaName ? `${activeTable.schemaName}.` : ''}${activeTable.tableName}` : '未选择表';
  return (
    <div className="workspace table-workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Text strong>{tableName}</Text>
          <Text type="secondary">{tableData?.editable ? `可编辑，行定位字段：${tableData.keyColumns.join(', ')}` : '当前表没有主键或唯一索引，只允许新增数据'}</Text>
        </div>
        <Space wrap>
          <Button onClick={onBackToSql}>查询工作台</Button>
          <Button icon={<ReloadOutlined />} disabled={!activeTable || loading} onClick={onReload}>重新加载</Button>
          <Button icon={<PlusOutlined />} disabled={!tableData || loading} onClick={onAddRow}>新增行</Button>
          <Button disabled={!pendingCount || loading} onClick={onPreview}>预览语句</Button>
          <Button type="primary" icon={<SaveOutlined />} disabled={!pendingCount || loading} loading={loading} onClick={onCommit}>提交</Button>
        </Space>
      </Header>
      <Alert className="status-alert" type={loading ? 'info' : 'success'} message={statusMessage} showIcon />
      <EditableTable data={tableData} rows={tableRows} onEdit={onEdit} onDelete={onDelete} />
      <SqlPreview sql={previewSql} />
    </div>
  );
}

function ConnectionFormPanel({ form, editing, loading, onChange, onDbTypeChange, onReset, onTest, onSave }: {
  form: ConnectionForm;
  editing: boolean;
  loading: boolean;
  onChange: (form: ConnectionForm) => void;
  onDbTypeChange: (dbType: string) => void;
  onReset: () => void;
  onTest: () => void;
  onSave: () => void;
}) {
  return (
    <Card
      size="small"
      title={editing ? '编辑连接' : '新建连接'}
      extra={<Button size="small" icon={<PlusOutlined />} onClick={onReset}>新建</Button>}
      className="panel-card"
    >
      <Form layout="vertical" size="middle">
        <Form.Item label="连接名称">
          <Input value={form.name} onChange={(event) => onChange({ ...form, name: event.target.value })} />
        </Form.Item>
        <Form.Item label="数据库类型">
          <Select value={form.dbType} options={DB_TYPE_OPTIONS.map(({ value, label }) => ({ value, label }))} onChange={onDbTypeChange} />
        </Form.Item>
        <Form.Item label="数据库地址">
          <Input value={form.jdbcUrl} onChange={(event) => onChange({ ...form, jdbcUrl: event.target.value })} />
        </Form.Item>
        <Form.Item label="用户名">
          <Input value={form.username} onChange={(event) => onChange({ ...form, username: event.target.value })} />
        </Form.Item>
        <Form.Item label="密码">
          <Input.Password value={form.password} onChange={(event) => onChange({ ...form, password: event.target.value })} />
        </Form.Item>
        <Form.Item label="环境">
          <Select value={normalizeEnvironment(form.environment)} options={ENVIRONMENT_OPTIONS} onChange={(value) => onChange({ ...form, environment: value })} />
        </Form.Item>
        <Form.Item>
          <Checkbox checked={form.readonly} onChange={(event) => onChange({ ...form, readonly: event.target.checked })}>只读连接</Checkbox>
        </Form.Item>
        {form.dbType === 'oracle' && (
          <Alert
            className="form-hint"
            type="info"
            showIcon
            message="Oracle 连接示例"
            description="Service Name：jdbc:oracle:thin:@//localhost:1521/ORCLPDB1；SID：jdbc:oracle:thin:@localhost:1521:ORCL"
          />
        )}
        {editing && <Alert className="form-hint" type="warning" showIcon message="编辑已有连接时，密码为 ****** 或留空都表示沿用原密码。" />}
        <Space className="form-actions" size={8}>
          <Button block onClick={onTest} loading={loading}>测试连接</Button>
          <Button block type="primary" icon={<SaveOutlined />} onClick={onSave} loading={loading}>{editing ? '保存修改' : '保存连接'}</Button>
        </Space>
      </Form>
    </Card>
  );
}

function BackupPanel({ backups, selected, loading, onCreate, onRun }: {
  backups: BackupTask[];
  selected: Connection | null;
  loading: boolean;
  onCreate: () => void;
  onRun: (id: number) => void;
}) {
  return (
    <Card size="small" title="备份任务" className="panel-card">
      <Space direction="vertical" size={10} className="full-width">
        <Button block disabled={!selected || loading} onClick={onCreate}>创建全量备份任务</Button>
        <List
          size="small"
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无备份任务" /> }}
          dataSource={backups}
          renderItem={(backup) => (
            <List.Item
              actions={[<Button key="run" size="small" onClick={() => onRun(backup.id)}>执行</Button>]}
            >
              <List.Item.Meta
                title={backup.name}
                description={(
                  <Space direction="vertical" size={2}>
                    <Text type="secondary">{backupScopeLabel(backup.scope)} · {backup.cron || '手动执行'}</Text>
                    <Tag color={backup.lastStatus === 'SUCCESS' ? 'green' : backup.lastStatus === 'FAILED' ? 'red' : 'default'}>{backupStatusLabel(backup.lastStatus)}</Tag>
                  </Space>
                )}
              />
            </List.Item>
          )}
        />
      </Space>
    </Card>
  );
}

function buildChanges(rows: TableRow[], keyColumns: string[]): RowChange[] {
  const changes: RowChange[] = [];
  for (const row of rows) {
    if (row.inserted) {
      changes.push({ type: 'INSERT', values: removeEmptyValues(row.values) });
      continue;
    }
    if (!row.original) continue;
    if (row.deleted) {
      changes.push({ type: 'DELETE', key: key(row.original, keyColumns) });
      continue;
    }
    const changedValues = diff(row.original, row.values);
    if (Object.keys(changedValues).length > 0) {
      changes.push({ type: 'UPDATE', key: key(row.original, keyColumns), values: changedValues });
    }
  }
  return changes;
}

function key(row: Record<string, unknown>, keyColumns: string[]) {
  return Object.fromEntries(keyColumns.map((column) => [column, row[column]]));
}

function diff(original: Record<string, unknown>, values: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(values).filter(([column, value]) => String(original[column] ?? '') !== String(value ?? '')));
}

function removeEmptyValues(values: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== ''));
}

function ResultGrid({ result }: { result: SqlResult | null }) {
  if (!result) return <Empty className="empty-state" description="执行查询后查看结果。" />;
  if (!result.resultSet) return <Empty className="empty-state" description={`影响 ${result.affectedRows} 行。`} />;
  const columns: ColumnsType<ResultRow> = result.columns.map((column) => ({
    title: column,
    dataIndex: column,
    key: column,
    ellipsis: true,
    render: (value: unknown) => String(value ?? '')
  }));
  const rows = result.rows.map((row, index) => ({ key: String(index), ...row }));
  return <Table<ResultRow> size="small" className="data-grid" columns={columns} dataSource={rows} pagination={false} scroll={{ x: true, y: 'calc(100vh - 500px)' }} />;
}

function EditableTable({ data, rows, onEdit, onDelete }: {
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
      scroll={{ x: true, y: 'calc(100vh - 380px)' }}
    />
  );
}

function SqlPreview({ sql }: { sql: string[] }) {
  return (
    <Card size="small" title="变更语句预览" className="sql-preview-card">
      {sql.length === 0 ? <Text type="secondary">尚未生成预览。</Text> : <pre>{sql.join('\n')}</pre>}
    </Card>
  );
}

function backupScopeLabel(scope: string) {
  if (scope === 'DATABASE') return '全库';
  if (scope === 'TABLE') return '单表';
  return scope;
}

function backupStatusLabel(status?: string) {
  if (!status) return '尚未执行';
  if (status === 'SUCCESS') return '执行成功';
  if (status === 'FAILED') return '执行失败';
  return status;
}

function dbTypeLabel(dbType: string) {
  return DB_TYPE_OPTIONS.find((option) => option.value === dbType)?.label || dbType;
}

function normalizeEnvironment(environment?: string) {
  return ENVIRONMENT_OPTIONS.some((option) => option.value === environment) ? environment as string : 'dev';
}

function environmentLabel(environment?: string) {
  return ENVIRONMENT_OPTIONS.find((option) => option.value === environment)?.label || '开发';
}

function objectTypeLabel(type: string) {
  const normalized = type.toUpperCase();
  if (normalized.includes('TABLE')) return '表';
  if (normalized.includes('VIEW')) return '视图';
  return type;
}

function localizeMessage(message: string) {
  if (!message) return '';
  if (message.includes('Backup framework executed')) return '备份任务已执行。当前数据库类型暂未实现物理备份适配器。';
  if (message.includes('Physical backup adapter is not implemented')) return '当前数据库类型暂未实现物理备份适配器。';
  if (message.includes('MySQL backup task prepared')) return 'MySQL 备份任务已准备完成，请在服务端配置物理备份命令。';
  if (message.includes('Connection not found')) return '未找到数据库连接。';
  if (message.includes('backup task')) return '该连接存在关联备份任务，请先处理备份任务后再删除。';
  if (message.includes('connection ok')) return '连接测试成功。';
  if (message.includes('No pending changes')) return '没有待提交的变更。';
  return message;
}

createRoot(document.getElementById('root')!).render(<App />);
