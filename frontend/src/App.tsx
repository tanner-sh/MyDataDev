import { useEffect, useMemo, useRef, useState } from 'react';
import type { OnMount } from '@monaco-editor/react';
import type * as Monaco from 'monaco-editor';
import { Button, Card, ConfigProvider, Layout, Space, Tabs } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { DatabaseOutlined, ReloadOutlined, TableOutlined } from '@ant-design/icons';
import { api, downloadBlob } from './api';
import { API, DB_TYPE_OPTIONS, EMPTY_FORM, PASSWORD_MASK } from './constants';
import type { ActiveTable, BackupTask, Connection, ConnectionForm, DbObject, ExportFormat, Metadata, ObjectDetail, RefreshConnectionsOptions, SqlCompletionItem, SqlHistory, SqlResult, SqlTab, TableData, TableRow } from './types';
import { buildChanges, completionKind, createSqlTab, localizeMessage, normalizeEnvironment, sleep, sqlKeywordCompletionItems, timestamp } from './utils';
import { BackupPanel } from './components/BackupPanel';
import { ConnectionFormPanel } from './components/ConnectionFormPanel';
import { ConnectionList } from './components/ConnectionList';
import { ObjectDetailWorkspace } from './components/ObjectDetailWorkspace';
import { ObjectTree } from './components/ObjectTree';
import { SqlHistoryDrawer } from './components/SqlHistoryDrawer';
import { SqlWorkspace } from './components/SqlWorkspace';
import { TableWorkspace } from './components/TableWorkspace';

const { Sider, Content } = Layout;

export default function App() {
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
  const [mode, setMode] = useState<'sql' | 'table' | 'object'>('sql');
  const [activeTable, setActiveTable] = useState<ActiveTable | null>(null);
  const [activeObjectDetail, setActiveObjectDetail] = useState<ObjectDetail | null>(null);
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
        setActiveObjectDetail(null);
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
    setActiveObjectDetail(null);
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

  async function exportSql(format: ExportFormat) {
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

  async function openObjectDetail(object: DbObject) {
    if (!selected) return;
    setLoading(true);
    try {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      const detail = await api<ObjectDetail>(`/metadata/${selected.id}/objects/detail?${params.toString()}`);
      setActiveObjectDetail(detail);
      setMode('object');
      setMessage(`已加载对象详情：${detail.name}`);
    } catch (e) {
      setMessage(localizeMessage((e as Error).message));
    } finally {
      setLoading(false);
    }
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
                <ObjectTree objects={objects} onOpenDetail={openObjectDetail} onOpenTable={openTable} />
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
          ) : mode === 'table' ? (
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
          ) : (
            <ObjectDetailWorkspace
              detail={activeObjectDetail}
              statusMessage={operationStatusMessage}
              loading={loading}
              onBackToSql={() => setMode('sql')}
              onOpenTable={openTable}
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
