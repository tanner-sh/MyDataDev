import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import Editor from '@monaco-editor/react';
import type { OnMount } from '@monaco-editor/react';
import type * as Monaco from 'monaco-editor';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Collapse,
  ConfigProvider,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  Layout,
  List,
  Popconfirm,
  Select,
  Skeleton,
  Space,
  Table,
  Tabs,
  Tag,
  Typography
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import zhCN from 'antd/locale/zh_CN';
import {
  CopyOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  HistoryOutlined,
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
type SqlTab = { id: string; title: string; sql: string; result: SqlResult | null; message: string };
type SqlHistory = { id: number; connectionId: number; sql: string; type: string; status: string; elapsedMs: number; errorMessage?: string; actor?: string; createdAt: string };
type SqlCompletionItem = { label: string; kind: string; insertText: string; detail: string };
type ResultRow = { key: string } & Record<string, unknown>;
type EditableRow = TableRow;
type RefreshConnectionsOptions = { retry?: boolean };

const EMPTY_FORM: ConnectionForm = {
  name: '本地 H2',
  dbType: 'h2',
  jdbcUrl: 'jdbc:h2:mem:testdb',
  username: 'sa',
  password: '',
  environment: 'dev',
  readonly: false
};

function createSqlTab(index: number): SqlTab {
  return { id: `query-${Date.now()}-${index}`, title: `查询 ${index}`, sql: 'select 1 as val', result: null, message: '' };
}

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

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function timestamp() {
  const pad = (value: number) => String(value).padStart(2, '0');
  const now = new Date();
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

function App() {
  const [connections, setConnections] = useState<Connection[]>([]);
  const [selected, setSelected] = useState<Connection | null>(null);
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [sqlTabs, setSqlTabs] = useState<SqlTab[]>([{ id: 'query-1', title: '查询 1', sql: 'select 1 as val', result: null, message: '' }]);
  const [activeSqlTabId, setActiveSqlTabId] = useState('query-1');
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [sqlLoading, setSqlLoading] = useState(false);
  const [connectionsLoading, setConnectionsLoading] = useState(false);
  const [connectionsError, setConnectionsError] = useState('');
  const [connectionsReady, setConnectionsReady] = useState(false);
  const [testingConnectionId, setTestingConnectionId] = useState<number | null>(null);
  const [backups, setBackups] = useState<BackupTask[]>([]);
  const [form, setForm] = useState<ConnectionForm>(EMPTY_FORM);
  const [editingConnectionId, setEditingConnectionId] = useState<number | null>(null);
  const [mode, setMode] = useState<'sql' | 'table'>('sql');
  const [activeTable, setActiveTable] = useState<ActiveTable | null>(null);
  const [tableData, setTableData] = useState<TableData | null>(null);
  const [tableRows, setTableRows] = useState<TableRow[]>([]);
  const [previewSql, setPreviewSql] = useState<string[]>([]);
  const [sqlHistory, setSqlHistory] = useState<SqlHistory[]>([]);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [inspectorCollapsed, setInspectorCollapsed] = useState(false);
  const selectedIdRef = useRef<number | null>(null);
  const editorRef = useRef<Monaco.editor.IStandaloneCodeEditor | null>(null);
  const executeRef = useRef<() => void>(() => undefined);
  const formatRef = useRef<() => void>(() => undefined);
  const sqlTabSeqRef = useRef(1);

  const objects = useMemo(() => metadata?.objects || [], [metadata]);
  const pendingChanges = useMemo(() => buildChanges(tableRows, tableData?.keyColumns || []), [tableRows, tableData]);
  const activeSqlTab = useMemo(() => sqlTabs.find((tab) => tab.id === activeSqlTabId) || sqlTabs[0], [activeSqlTabId, sqlTabs]);

  useEffect(() => {
    refreshConnections({ retry: true });
    refreshBackups().catch(() => setMessage('备份任务加载失败，可稍后刷新。'));
  }, []);

  useEffect(() => {
    selectedIdRef.current = selected?.id || null;
  }, [selected]);

  async function refreshConnections(options: RefreshConnectionsOptions = {}) {
    const delays = options.retry ? [0, 500, 1000, 1500, 2000, 3000] : [0];
    setConnectionsLoading(true);
    setConnectionsError('');
    for (let attempt = 0; attempt < delays.length; attempt++) {
      if (delays[attempt] > 0) {
        setConnectionsError('后端服务可能还在启动，正在重试...');
        await sleep(delays[attempt]);
      }
      try {
        const rows = await api<Connection[]>('/connections');
        setConnections(rows);
        setSelected((current: Connection | null) => current || rows[0] || null);
        setConnectionsError('');
        setConnectionsReady(true);
        setConnectionsLoading(false);
        return;
      } catch (e) {
        if (attempt === delays.length - 1) {
          setConnectionsError(`连接后端失败，请确认服务已启动：${localizeMessage((e as Error).message)}`);
          setConnectionsReady(true);
          setConnectionsLoading(false);
          return;
        }
      }
    }
  }

  async function refreshBackups() {
    setBackups(await api<BackupTask[]>('/backups'));
  }

  async function refreshSqlHistory(conn = selected) {
    if (!conn) {
      setSqlHistory([]);
      return;
    }
    const rows = await api<SqlHistory[]>(`/sql/history?connectionId=${conn.id}&limit=50`);
    setSqlHistory(rows);
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
    setTestingConnectionId(connection.id);
    try {
      await api<{ ok: boolean; message: string }>(`/connections/${connection.id}/test`, { method: 'POST' });
      setMessage(`连接测试成功：${connection.name}`);
    } catch (e) {
      setMessage(`连接测试失败：${localizeMessage((e as Error).message)}`);
    } finally {
      setTestingConnectionId(null);
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
      await refreshSqlHistory(selected);
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
    const target = sqlExecutionTarget();
    if (!target.sql.trim()) {
      updateActiveSqlTab({ message: '请输入要执行的 SQL' });
      return;
    }
    setMode('sql');
    setSqlLoading(true);
    try {
      const data = await api<SqlResult>(path, { method: 'POST', body: JSON.stringify({ connectionId: selected.id, sql: target.sql, maxRows: 500 }) });
      const nextMessage = data.resultSet
        ? `${target.selected ? '已执行选中 SQL，' : '已执行全部 SQL，'}返回 ${data.rows.length} 行，用时 ${data.elapsedMs}ms`
        : `${target.selected ? '已执行选中 SQL，' : '已执行全部 SQL，'}影响 ${data.affectedRows} 行`;
      updateActiveSqlTab({ result: data, message: nextMessage });
      await refreshSqlHistory(selected);
    } catch (e) {
      updateActiveSqlTab({ message: localizeMessage((e as Error).message) });
      await refreshSqlHistory(selected);
    } finally {
      setSqlLoading(false);
    }
  }

  async function formatSql() {
    const data = await api<{ sql: string }>('/sql/format', { method: 'POST', body: JSON.stringify({ sql: activeSqlTab.sql }) });
    updateActiveSqlTab({ sql: data.sql });
  }

  async function openSqlHistory() {
    await refreshSqlHistory();
    setHistoryOpen(true);
  }

  async function exportSql(format: 'csv' | 'json') {
    if (!selected) {
      updateActiveSqlTab({ message: '请先选择一个数据库连接' });
      return;
    }
    const target = sqlExecutionTarget();
    if (!target.sql.trim()) {
      updateActiveSqlTab({ message: '请输入要导出的 SQL' });
      return;
    }
    setSqlLoading(true);
    try {
      const response = await fetch(`${API}/sql/export`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-User': 'admin' },
        body: JSON.stringify({ connectionId: selected.id, sql: target.sql, format })
      });
      if (!response.ok) {
        const err = await response.json().catch(() => ({ message: response.statusText }));
        throw new Error(err.message || response.statusText);
      }
      const blob = await response.blob();
      downloadBlob(blob, `query-result-${timestamp()}.${format}`);
      updateActiveSqlTab({ message: `已导出 ${format.toUpperCase()}：${target.selected ? '选中 SQL' : '全部 SQL'}` });
    } catch (e) {
      updateActiveSqlTab({ message: `导出失败：${localizeMessage((e as Error).message)}` });
    } finally {
      setSqlLoading(false);
    }
  }

  function updateActiveSqlTab(patch: Partial<SqlTab>) {
    setSqlTabs((tabs) => tabs.map((tab) => tab.id === activeSqlTab.id ? { ...tab, ...patch } : tab));
  }

  function addSqlTab() {
    const nextIndex = sqlTabSeqRef.current + 1;
    sqlTabSeqRef.current = nextIndex;
    const tab = createSqlTab(nextIndex);
    setSqlTabs((tabs) => [...tabs, tab]);
    setActiveSqlTabId(tab.id);
  }

  function closeSqlTab(targetId: string) {
    setSqlTabs((tabs) => {
      if (tabs.length === 1) {
        return tabs;
      }
      const targetIndex = tabs.findIndex((tab) => tab.id === targetId);
      const nextTabs = tabs.filter((tab) => tab.id !== targetId);
      if (targetId === activeSqlTabId) {
        const nextActive = nextTabs[Math.max(0, targetIndex - 1)] || nextTabs[0];
        setActiveSqlTabId(nextActive.id);
      }
      return nextTabs;
    });
  }

  function sqlExecutionTarget() {
    const selection = editorRef.current?.getSelection();
    const model = editorRef.current?.getModel();
    const selectedText = selection && model ? model.getValueInRange(selection).trim() : '';
    return { sql: selectedText || activeSqlTab.sql, selected: Boolean(selectedText) };
  }

  useEffect(() => {
    executeRef.current = () => execute();
    formatRef.current = () => formatSql();
  });

  const handleEditorMount: OnMount = (editor, monaco) => {
    editorRef.current = editor;
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => executeRef.current());
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyF, () => formatRef.current());
    monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters: ['.', ' '],
      provideCompletionItems: async (model: Monaco.editor.ITextModel, position: Monaco.Position) => {
        const word = model.getWordUntilPosition(position);
        const range = {
          startLineNumber: position.lineNumber,
          endLineNumber: position.lineNumber,
          startColumn: word.startColumn,
          endColumn: word.endColumn
        };
        const connectionId = selectedIdRef.current;
        const fallbackItems = sqlKeywordCompletionItems(monaco, range);
        if (!connectionId) {
          return { suggestions: fallbackItems };
        }
        try {
          const items = await api<SqlCompletionItem[]>('/sql/completions', {
            method: 'POST',
            body: JSON.stringify({ connectionId, sql: model.getValue(), cursorPosition: model.getOffsetAt(position) })
          });
          return {
            suggestions: items.map((item) => ({
              label: item.label,
              kind: completionKind(monaco, item.kind),
              insertText: item.insertText,
              detail: item.detail,
              range
            }))
          };
        } catch {
          return { suggestions: fallbackItems };
        }
      }
    });
  };

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

  const baseStatusMessage = activeSqlTab.message || message || '就绪';
  const sqlStatusMessage = sqlLoading ? '处理中...' : baseStatusMessage;
  const operationStatusMessage = loading ? '处理中...' : baseStatusMessage;

  return (
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#1f6feb', borderRadius: 6 } }}>
      <Layout className="app-shell">
        <Sider width={280} className="app-sider" theme="light">
          <Space direction="vertical" size={10} className="full-width">
            <div className="brand">
              <DatabaseOutlined />
              <span>数据库管理工具</span>
            </div>
            <Button type="primary" size="small" icon={<ReloadOutlined />} block loading={connectionsLoading} onClick={() => refreshConnections()}>
              刷新连接
            </Button>
            <ConnectionList
              connections={connections}
              selectedId={selected?.id}
              connectionsLoading={connectionsLoading}
              connectionsError={connectionsError}
              connectionsReady={connectionsReady}
              testingConnectionId={testingConnectionId}
              onEdit={editConnection}
              onTest={testSavedConnection}
              onDuplicate={duplicateConnection}
              onDelete={deleteConnection}
            />
            <Card size="small" title="数据库对象" className="panel-card">
              <Space direction="vertical" size={8} className="full-width">
                <Button size="small" icon={<TableOutlined />} block disabled={!selected || loading} onClick={() => loadMetadata()}>
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
              tabs={sqlTabs}
              activeTabId={activeSqlTab.id}
              activeTab={activeSqlTab}
              statusMessage={sqlStatusMessage}
              loading={sqlLoading}
              onTabChange={setActiveSqlTabId}
              onTabAdd={addSqlTab}
              onTabClose={closeSqlTab}
              onSqlChange={(sql) => updateActiveSqlTab({ sql })}
              onEditorMount={handleEditorMount}
              onFormat={formatSql}
              onExplain={() => execute('/sql/explain')}
              onExecute={() => execute()}
              onExport={exportSql}
              onOpenHistory={openSqlHistory}
            />
          ) : (
            <TableWorkspace
              activeTable={activeTable}
              tableData={tableData}
              tableRows={tableRows}
              previewSql={previewSql}
              pendingCount={pendingChanges.length}
              statusMessage={`${operationStatusMessage} · 待提交变更：${pendingChanges.length}`}
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

        <Sider
          width={340}
          className="app-inspector"
          theme="light"
          collapsible
          collapsed={inspectorCollapsed}
          collapsedWidth={0}
          onCollapse={setInspectorCollapsed}
        >
          <Tabs
            className="inspector-tabs"
            defaultActiveKey="connection"
            items={[
              {
                key: 'connection',
                label: '连接配置',
                children: (
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
                )
              },
              {
                key: 'backup',
                label: '备份任务',
                children: <BackupPanel backups={backups} selected={selected} loading={loading} onCreate={createBackup} onRun={runBackup} />
              }
            ]}
          />
        </Sider>
      </Layout>
      <SqlHistoryDrawer
        open={historyOpen}
        history={sqlHistory}
        onClose={() => setHistoryOpen(false)}
        onPick={(historyItem) => {
          updateActiveSqlTab({ sql: historyItem.sql });
          setHistoryOpen(false);
        }}
      />
    </ConfigProvider>
  );
}

function ConnectionList({ connections, selectedId, connectionsLoading, connectionsError, connectionsReady, testingConnectionId, onEdit, onTest, onDuplicate, onDelete }: {
  connections: Connection[];
  selectedId?: number;
  connectionsLoading: boolean;
  connectionsError: string;
  connectionsReady: boolean;
  testingConnectionId: number | null;
  onEdit: (connection: Connection) => void;
  onTest: (connection: Connection) => void;
  onDuplicate: (connection: Connection) => void;
  onDelete: (connection: Connection) => void;
}) {
  if (connectionsLoading && connections.length === 0) {
    return (
      <Card size="small">
        <Skeleton active paragraph={{ rows: 4 }} title={{ width: '60%' }} />
      </Card>
    );
  }
  if (connectionsError && connections.length === 0) {
    return <Alert type="warning" showIcon message={connectionsError} />;
  }
  if (!connectionsReady) {
    return <Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="正在准备连接列表" /></Card>;
  }
  if (connections.length === 0) {
    return <Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据库连接" /></Card>;
  }
  return (
    <Space direction="vertical" size={8} className="full-width">
      {connectionsError && <Alert type="warning" showIcon message={connectionsError} />}
      <List
        className="connection-list"
        dataSource={connections}
        renderItem={(connection) => (
          <List.Item className={selectedId === connection.id ? 'connection-item selected' : 'connection-item'}>
            <div className="connection-row">
              <button className="connection-title-button connection-main-info" onClick={() => onEdit(connection)}>
                <div className="connection-name-row">
                  <Text strong className="ellipsis-text">{connection.name}</Text>
                  {connection.readonly && <Tag color="orange">只读</Tag>}
                </div>
                <Space size={4} wrap className="connection-tags">
                  <Tag color="blue">{dbTypeLabel(connection.dbType)}</Tag>
                  <Tag>{environmentLabel(connection.environment)}</Tag>
                </Space>
                <Text type="secondary" className="ellipsis-text connection-url">{connection.jdbcUrl}</Text>
              </button>
              <Space size={4} wrap className="connection-actions">
                <Button size="small" loading={testingConnectionId === connection.id} onClick={() => onTest(connection)}>测试</Button>
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
            </div>
          </List.Item>
        )}
      />
    </Space>
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

function SqlWorkspace({ selected, tabs, activeTabId, activeTab, statusMessage, loading, onTabChange, onTabAdd, onTabClose, onSqlChange, onEditorMount, onFormat, onExplain, onExecute, onExport, onOpenHistory }: {
  selected: Connection | null;
  tabs: SqlTab[];
  activeTabId: string;
  activeTab: SqlTab;
  statusMessage: string;
  loading: boolean;
  onTabChange: (tabId: string) => void;
  onTabAdd: () => void;
  onTabClose: (tabId: string) => void;
  onSqlChange: (sql: string) => void;
  onEditorMount: OnMount;
  onFormat: () => void;
  onExplain: () => void;
  onExecute: () => void;
  onExport: (format: 'csv' | 'json') => void;
  onOpenHistory: () => void;
}) {
  return (
    <div className="workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Text strong>{selected ? selected.name : '未选择连接'}</Text>
          <Text type="secondary" className="ellipsis-text">{selected?.jdbcUrl || '请选择左侧数据库连接'}</Text>
        </div>
        <Space size={8} wrap>
          <Button size="small" onClick={onFormat}>格式化</Button>
          <Button size="small" icon={<HistoryOutlined />} disabled={!selected} onClick={onOpenHistory}>历史</Button>
          <Dropdown
            menu={{
              items: [
                { key: 'csv', label: '导出 CSV' },
                { key: 'json', label: '导出 JSON' }
              ],
              onClick: ({ key }) => onExport(key as 'csv' | 'json')
            }}
          >
            <Button size="small" icon={<DownloadOutlined />} disabled={!selected || loading}>导出</Button>
          </Dropdown>
          <Button size="small" disabled={!selected || loading} onClick={onExplain}>执行计划</Button>
          <Button size="small" type="primary" icon={<PlayCircleOutlined />} disabled={!selected || loading} loading={loading} onClick={onExecute}>执行</Button>
        </Space>
      </Header>
      <Tabs
        className="sql-tabs"
        type="editable-card"
        activeKey={activeTabId}
        onChange={onTabChange}
        onEdit={(targetKey, action) => {
          if (action === 'add') {
            onTabAdd();
          } else {
            onTabClose(String(targetKey));
          }
        }}
        hideAdd={false}
        items={tabs.map((tab) => ({ key: tab.id, label: tab.title, closable: tabs.length > 1 }))}
      />
      <div className="editor">
        <Editor height="100%" language="sql" value={activeTab.sql} onMount={onEditorMount} onChange={(value) => onSqlChange(value || '')} theme="vs-dark" options={{ minimap: { enabled: false }, fontSize: 14 }} />
      </div>
      <Alert className="status-alert" type={loading ? 'info' : 'success'} message={statusMessage} showIcon />
      <ResultGrid result={activeTab.result} />
    </div>
  );
}

function SqlHistoryDrawer({ open, history, onClose, onPick }: {
  open: boolean;
  history: SqlHistory[];
  onClose: () => void;
  onPick: (history: SqlHistory) => void;
}) {
  return (
    <Drawer title="SQL 执行历史" width={520} open={open} onClose={onClose}>
      <List
        dataSource={history}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 SQL 历史" /> }}
        renderItem={(item) => (
          <List.Item
            actions={[<Button key="use" size="small" onClick={() => onPick(item)}>回填</Button>]}
          >
            <List.Item.Meta
              title={(
                <Space size={6} wrap>
                  <Tag color={item.type === 'EXPLAIN' ? 'purple' : 'blue'}>{item.type === 'EXPLAIN' ? '执行计划' : '执行'}</Tag>
                  <Tag color={item.status === 'SUCCESS' ? 'green' : 'red'}>{item.status === 'SUCCESS' ? '成功' : '失败'}</Tag>
                  <Text type="secondary">{formatHistoryTime(item.createdAt)} · {item.elapsedMs}ms</Text>
                </Space>
              )}
              description={(
                <Space direction="vertical" size={4} className="full-width">
                  <pre className="history-sql">{item.sql}</pre>
                  {item.errorMessage && <Text type="danger">{item.errorMessage}</Text>}
                </Space>
              )}
            />
          </List.Item>
        )}
      />
    </Drawer>
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
        <Space size={8} wrap>
          <Button size="small" onClick={onBackToSql}>查询工作台</Button>
          <Button size="small" icon={<ReloadOutlined />} disabled={!activeTable || loading} onClick={onReload}>重新加载</Button>
          <Button size="small" icon={<PlusOutlined />} disabled={!tableData || loading} onClick={onAddRow}>新增行</Button>
          <Button size="small" disabled={!pendingCount || loading} onClick={onPreview}>预览语句</Button>
          <Button size="small" type="primary" icon={<SaveOutlined />} disabled={!pendingCount || loading} loading={loading} onClick={onCommit}>提交</Button>
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
    <section className="inspector-section">
      <div className="inspector-section-header">
        <Text strong>{editing ? '编辑连接' : '新建连接'}</Text>
        <Button size="small" icon={<PlusOutlined />} onClick={onReset}>新建</Button>
      </div>
      <Form layout="vertical" size="small" className="compact-form">
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
          <Text type="secondary" className="form-hint-text">
            Oracle 示例：Service Name 使用 jdbc:oracle:thin:@//localhost:1521/ORCLPDB1；SID 使用 jdbc:oracle:thin:@localhost:1521:ORCL。
          </Text>
        )}
        {editing && <Text type="secondary" className="form-hint-text">编辑已有连接时，密码为 ****** 或留空都表示沿用原密码。</Text>}
        <Space className="form-actions" size={8}>
          <Button block onClick={onTest} loading={loading}>测试连接</Button>
          <Button block type="primary" icon={<SaveOutlined />} onClick={onSave} loading={loading}>{editing ? '保存修改' : '保存连接'}</Button>
        </Space>
      </Form>
    </section>
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
    <section className="inspector-section">
      <div className="inspector-section-header">
        <Text strong>备份任务</Text>
      </div>
      <Space direction="vertical" size={10} className="full-width">
        <Button size="small" block disabled={!selected || loading} onClick={onCreate}>创建全量备份任务</Button>
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
    </section>
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
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);

  useEffect(() => {
    setCurrentPage(1);
  }, [result]);

  if (!result) return <Empty className="empty-state" description="执行查询后查看结果。" />;
  if (!result.resultSet) return <Empty className="empty-state" description={`影响 ${result.affectedRows} 行。`} />;
  const columns: ColumnsType<ResultRow> = [
    {
      title: '序号',
      key: '__index',
      width: 70,
      fixed: 'left',
      render: (_value, _row, index) => (currentPage - 1) * pageSize + index + 1
    },
    ...result.columns.map((column) => ({
      title: column,
      dataIndex: column,
      key: column,
      ellipsis: true,
      render: (value: unknown) => String(value ?? '')
    }))
  ];
  const rows = result.rows.map((row, index) => ({ key: String(index), ...row }));
  return (
    <Table<ResultRow>
      size="small"
      className="data-grid"
      columns={columns}
      dataSource={rows}
      pagination={{
        current: currentPage,
        pageSize,
        showSizeChanger: true,
        pageSizeOptions: ['50', '100', '200'],
        showTotal: (total) => `共 ${total} 行`,
        onChange: (page, nextPageSize) => {
          setCurrentPage(nextPageSize !== pageSize ? 1 : page);
          setPageSize(nextPageSize);
        }
      }}
      scroll={{ x: true, y: 'calc(100vh - 440px)' }}
    />
  );
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
      scroll={{ x: true, y: 'calc(100vh - 330px)' }}
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

function sqlKeywordCompletionItems(monaco: Parameters<OnMount>[1], range: Monaco.IRange) {
  return [
    'SELECT', 'FROM', 'WHERE', 'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN',
    'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'INSERT', 'UPDATE', 'DELETE'
  ].map((keyword) => ({
    label: keyword,
    kind: monaco.languages.CompletionItemKind.Keyword,
    insertText: keyword,
    detail: 'SQL 关键字',
    range
  }));
}

function completionKind(monaco: Parameters<OnMount>[1], kind: string) {
  if (kind === 'TABLE') return monaco.languages.CompletionItemKind.Class;
  if (kind === 'COLUMN') return monaco.languages.CompletionItemKind.Field;
  if (kind === 'SCHEMA') return monaco.languages.CompletionItemKind.Module;
  return monaco.languages.CompletionItemKind.Keyword;
}

function formatHistoryTime(value: string) {
  if (!value) return '';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
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
