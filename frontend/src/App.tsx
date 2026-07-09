import { useEffect, useMemo, useRef, useState } from 'react';
import type { OnMount } from '@monaco-editor/react';
import type * as Monaco from 'monaco-editor';
import { Button, Card, ConfigProvider, Input, Layout, Select, Space, Tabs, message as antdMessage } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { DatabaseOutlined, ReloadOutlined } from '@ant-design/icons';
import { api, downloadBlob } from './api';
import { API, DB_TYPE_OPTIONS, EMPTY_FORM, PASSWORD_MASK } from './constants';
import { parseImportFile } from './importers';
import type { ActiveTable, BackupHistory, BackupTask, BackupTaskForm, Connection, ConnectionForm, DbObject, ExportFormat, Metadata, ObjectDetail, ObjectStructure, RefreshConnectionsOptions, SqlCompletionItem, SqlHistory, SqlResult, SqlScriptResult, SqlStatementResult, SqlTab, TableData, TableRow } from './types';
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
const OBJECT_PAGE_SIZE = 200;

export default function App() {
  const [connections, setConnections] = useState<Connection[]>([]);
  const [selected, setSelected] = useState<Connection | null>(null);
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [metadataQuery, setMetadataQuery] = useState({ schema: '', keyword: '' });
  const [structureLoadingKey, setStructureLoadingKey] = useState<string | null>(null);
  const [sqlTabs, setSqlTabs] = useState<SqlTab[]>([{ id: 'query-1', title: '查询 1', sql: 'select 1 as val', results: [], message: '' }]);
  const [activeSqlTabId, setActiveSqlTabId] = useState('query-1');
  const [message, setMessage] = useState('');
  const [connectionActionLoading, setConnectionActionLoading] = useState(false);
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [objectDetailLoading, setObjectDetailLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);
  const [backupLoading, setBackupLoading] = useState(false);
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
  const metadataRef = useRef<Metadata | null>(null);
  const structureCacheRef = useRef<Map<string, DbObject>>(new Map());
  const editorRef = useRef<Monaco.editor.IStandaloneCodeEditor | null>(null);
  const executeRef = useRef<() => void>(() => undefined);
  const formatRef = useRef<() => void>(() => undefined);
  const sqlTabSeqRef = useRef(1);
  const [toastApi, toastContextHolder] = antdMessage.useMessage();

  const objects = useMemo(() => metadata?.objects || [], [metadata]);
  const pendingChanges = useMemo(() => buildChanges(tableRows, tableData?.keyColumns || []), [tableRows, tableData]);
  const activeSqlTab = useMemo(() => sqlTabs.find((tab) => tab.id === activeSqlTabId) || sqlTabs[0], [activeSqlTabId, sqlTabs]);

  useEffect(() => {
    refreshConnections({ retry: true });
  }, []);

  useEffect(() => {
    selectedIdRef.current = selected?.id || null;
  }, [selected]);

  useEffect(() => {
    refreshBackups(selected).catch(() => showError('备份任务加载失败，可稍后刷新。'));
  }, [selected?.id]);

  useEffect(() => {
    if (selected) {
      loadMetadata(selected, { page: 0 }).catch(() => undefined);
    }
  }, [selected?.id]);

  useEffect(() => {
    metadataRef.current = metadata;
  }, [metadata]);

  function showSuccess(text: string) {
    setMessage(text);
    toastApi.success(text);
  }

  function showError(text: string) {
    setMessage(text);
    toastApi.error(text);
  }

  function showInfo(text: string) {
    setMessage(text);
    toastApi.info(text);
  }

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

  async function refreshBackups(conn = selected) {
    if (!conn) {
      setBackups([]);
      return;
    }
    setBackups(await api<BackupTask[]>(`/backups?connectionId=${conn.id}`));
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
    setConnectionActionLoading(true);
    try {
      const saved = editingConnectionId
        ? await api<Connection>(`/connections/${editingConnectionId}`, { method: 'PUT', body: JSON.stringify(form) })
        : await api<Connection>('/connections', { method: 'POST', body: JSON.stringify(form) });
      setSelected(saved);
      setMetadata(null);
      setMetadataQuery({ schema: '', keyword: '' });
      structureCacheRef.current.clear();
      setStructureLoadingKey(null);
      setEditingConnectionId(saved.id);
      showSuccess(editingConnectionId ? `已更新连接：${saved.name}` : `已创建连接：${saved.name}`);
      await refreshConnections();
    } catch (e) {
      showError(localizeMessage((e as Error).message));
    } finally {
      setConnectionActionLoading(false);
    }
  }

  async function testConnection(target = form) {
    setConnectionActionLoading(true);
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
      showSuccess(`连接测试成功：${target.name || target.jdbcUrl}`);
    } catch (e) {
      showError(`连接测试失败：${localizeMessage((e as Error).message)}`);
    } finally {
      setConnectionActionLoading(false);
    }
  }

  async function testSavedConnection(connection: Connection) {
    setTestingConnectionId(connection.id);
    try {
      await api<{ ok: boolean; message: string }>(`/connections/${connection.id}/test`, { method: 'POST' });
      showSuccess(`连接测试成功：${connection.name}`);
    } catch (e) {
      showError(`连接测试失败：${localizeMessage((e as Error).message)}`);
    } finally {
      setTestingConnectionId(null);
    }
  }

  async function deleteConnection(connection: Connection) {
    setConnectionActionLoading(true);
    try {
      await api<{ ok: boolean; message: string }>(`/connections/${connection.id}`, { method: 'DELETE' });
      showSuccess(`已删除连接：${connection.name}`);
      const remaining = connections.filter((row) => row.id !== connection.id);
      setConnections(remaining);
      if (selected?.id === connection.id) {
        setSelected(remaining[0] || null);
        setMetadata(null);
        setMetadataQuery({ schema: '', keyword: '' });
        structureCacheRef.current.clear();
        setStructureLoadingKey(null);
        setActiveObjectDetail(null);
        setMode('sql');
      }
      if (editingConnectionId === connection.id) {
        resetConnectionForm();
      }
      await refreshConnections();
    } catch (e) {
      const rawMessage = (e as Error).message;
      const blockedByBackups = rawMessage.includes('backup task');
      showError(blockedByBackups ? '该连接存在关联备份任务，请先切换到“备份任务”删除相关任务后再删除连接。' : localizeMessage(rawMessage));
      await refreshSqlHistory(selected);
    } finally {
      setConnectionActionLoading(false);
    }
  }

  function selectConnection(connection: Connection) {
    setSelected(connection);
    setMetadata(null);
    setMetadataQuery({ schema: '', keyword: '' });
    structureCacheRef.current.clear();
    setStructureLoadingKey(null);
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
    showInfo(`正在编辑连接：${connection.name}。密码显示为 ${PASSWORD_MASK} 表示沿用已保存密码。`);
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
    showInfo('已复制连接配置，请输入密码后保存为新连接。');
  }

  function resetConnectionForm() {
    setEditingConnectionId(null);
    setForm(EMPTY_FORM);
  }

  async function loadMetadata(conn = selected, options: { schema?: string; keyword?: string; page?: number; append?: boolean; refresh?: boolean } = {}) {
    if (!conn) return;
    setMetadataLoading(true);
    try {
      if (options.refresh) {
        structureCacheRef.current.clear();
        setStructureLoadingKey(null);
      }
      const schema = options.schema ?? metadataQuery.schema;
      const keyword = options.keyword ?? metadataQuery.keyword;
      const params = new URLSearchParams({
        page: String(options.page ?? 0),
        pageSize: String(OBJECT_PAGE_SIZE)
      });
      if (options.refresh) params.set('refresh', 'true');
      if (schema) params.set('schema', schema);
      if (keyword.trim()) params.set('keyword', keyword.trim());
      const data = await api<Metadata>(`/metadata/${conn.id}?${params.toString()}`);
      setMetadata((current) => {
        if (!options.append || !current) {
          return data;
        }
        return { ...data, objects: [...current.objects, ...data.objects] };
      });
      const loadedCount = (options.append ? (metadata?.objects.length || 0) : 0) + data.objects.length;
      const cacheText = data.cacheHit ? '来自缓存' : '已刷新缓存';
      const timeText = data.cachedAt ? `，缓存时间 ${new Date(data.cachedAt).toLocaleString()}` : '';
      const nextMessage = data.hasMore ? `${cacheText}${timeText}，已加载 ${loadedCount} 个数据库对象，可继续加载更多` : `${cacheText}${timeText}，已加载 ${loadedCount} 个数据库对象`;
      if (options.refresh) {
        showSuccess(nextMessage);
      } else {
        setMessage(nextMessage);
      }
    } catch (e) {
      showError(localizeMessage((e as Error).message));
    } finally {
      setMetadataLoading(false);
    }
  }

  function objectCacheKey(connectionId: number, object: Pick<DbObject, 'schemaName' | 'name'>) {
    return `${connectionId}:${object.schemaName || ''}.${object.name}`.toLowerCase();
  }

  async function loadObjectStructure(object: DbObject) {
    const connectionId = selectedIdRef.current;
    if (!connectionId) return null;
    const cacheKey = objectCacheKey(connectionId, object);
    const cached = structureCacheRef.current.get(cacheKey);
    if (cached) {
      mergeObjectStructure(cached);
      return cached;
    }

    setStructureLoadingKey(`${object.schemaName || ''}.${object.name}`);
    try {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      const structure = await api<ObjectStructure>(`/metadata/${connectionId}/objects/structure?${params.toString()}`);
      const nextObject: DbObject = {
        schemaName: structure.schemaName,
        name: structure.name,
        type: structure.type,
        columns: structure.columns,
        indexes: structure.indexes
      };
      structureCacheRef.current.set(cacheKey, nextObject);
      mergeObjectStructure(nextObject);
      return nextObject;
    } catch (e) {
      showError(localizeMessage((e as Error).message));
      return null;
    } finally {
      setStructureLoadingKey(null);
    }
  }

  function mergeObjectStructure(structure: DbObject) {
    setMetadata((current) => {
      if (!current) return current;
      return {
        ...current,
        objects: current.objects.map((object) => matchesSameObject(object, structure) ? { ...object, ...structure } : object)
      };
    });
  }

  function matchesSameObject(left: Pick<DbObject, 'schemaName' | 'name'>, right: Pick<DbObject, 'schemaName' | 'name'>) {
    return (left.schemaName || '').toLowerCase() === (right.schemaName || '').toLowerCase()
      && left.name.toLowerCase() === right.name.toLowerCase();
  }

  async function execute(path = '/sql/execute') {
    if (!selected) {
      showInfo('请先选择一个数据库连接');
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
      if (path === '/sql/explain') {
        const data = await api<SqlResult>(path, { method: 'POST', body: JSON.stringify({ connectionId: selected.id, sql: target.sql, maxRows: 500 }) });
        const result: SqlStatementResult = {
          index: 1,
          sql: target.sql,
          startOffset: 0,
          endOffset: target.sql.length,
          status: 'SUCCESS',
          errorMessage: null,
          result: data
        };
        const nextMessage = `已生成${target.selected ? '选中 SQL' : '当前 SQL'}的执行计划，用时 ${data.elapsedMs}ms`;
        updateActiveSqlTab({ results: [result], activeResultKey: statementResultKey(result), message: nextMessage });
      } else {
        const data = await api<SqlScriptResult>('/sql/execute-script', { method: 'POST', body: JSON.stringify({ connectionId: selected.id, sql: target.sql, maxRows: 500 }) });
        const failed = data.results.find((item) => item.status === 'FAILED');
        const successCount = data.results.filter((item) => item.status === 'SUCCESS').length;
        const firstResultSet = data.results.find((item) => item.result?.resultSet);
        const returnedRows = data.results.reduce((total, item) => total + (item.result?.resultSet ? item.result.rows.length : 0), 0);
        const nextMessage = failed
          ? `第 ${failed.index} 条 SQL 执行失败，已成功 ${successCount} 条，用时 ${data.elapsedMs}ms`
          : `已执行 ${successCount} 条 SQL，返回 ${returnedRows} 行，用时 ${data.elapsedMs}ms`;
        updateActiveSqlTab({
          results: data.results,
          activeResultKey: statementResultKey(failed || firstResultSet || data.results[0]),
          message: nextMessage
        });
        if (failed) {
          selectStatementRange(target.baseOffset + failed.startOffset, target.baseOffset + failed.endOffset);
        }
      }
      await refreshSqlHistory(selected);
    } catch (e) {
      const errorMessage = localizeMessage((e as Error).message);
      updateActiveSqlTab({ message: errorMessage });
      toastApi.error(errorMessage);
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
      showInfo('请先选择一个数据库连接');
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
      const nextMessage = `已导出 ${format.toUpperCase()}：${target.selected ? '选中 SQL' : '全部 SQL'}`;
      updateActiveSqlTab({ message: nextMessage });
      toastApi.success(nextMessage);
    } catch (e) {
      const errorMessage = `导出失败：${localizeMessage((e as Error).message)}`;
      updateActiveSqlTab({ message: errorMessage });
      toastApi.error(errorMessage);
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
    if (selection && model && !selection.isEmpty()) {
      const rawSelectedText = model.getValueInRange(selection);
      const leadingWhitespace = rawSelectedText.length - rawSelectedText.trimStart().length;
      const selectedText = rawSelectedText.trim();
      if (selectedText) {
        return {
          sql: selectedText,
          selected: true,
          baseOffset: model.getOffsetAt(selection.getStartPosition()) + leadingWhitespace
        };
      }
    }
    return { sql: activeSqlTab.sql, selected: false, baseOffset: 0 };
  }

  function statementResultKey(result?: SqlStatementResult) {
    return result ? `statement-${result.index}` : undefined;
  }

  function selectStatementRange(startOffset: number, endOffset: number) {
    const editor = editorRef.current;
    const model = editor?.getModel();
    if (!editor || !model) return;
    const start = model.getPositionAt(startOffset);
    const end = model.getPositionAt(endOffset);
    editor.setSelection({
      startLineNumber: start.lineNumber,
      startColumn: start.column,
      endLineNumber: end.lineNumber,
      endColumn: end.column
    });
    editor.revealPositionInCenter(start);
    editor.focus();
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
        const aliasItems = await aliasColumnCompletionItems(model, position, monaco, range);
        if (aliasItems.length > 0) {
          return { suggestions: aliasItems };
        }
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

  async function aliasColumnCompletionItems(model: Monaco.editor.ITextModel, position: Monaco.Position, monaco: Parameters<OnMount>[1], range: Monaco.IRange) {
    const textBeforeCursor = model.getValueInRange({
      startLineNumber: 1,
      startColumn: 1,
      endLineNumber: position.lineNumber,
      endColumn: position.column
    });
    const aliasMatch = textBeforeCursor.match(/([A-Za-z_][\w$]*)\.\w*$/);
    if (!aliasMatch) return [];
    const object = resolveAliasObject(model.getValue(), aliasMatch[1], metadataRef.current?.objects || []);
    if (!object) return [];
    const objectWithColumns = object.columns.length > 0 ? object : await loadObjectStructure(object);
    if (!objectWithColumns) return [];
    return objectWithColumns.columns.map((column) => ({
      label: column.name,
      kind: monaco.languages.CompletionItemKind.Field,
      insertText: column.name,
      detail: `${objectWithColumns.name} 字段 · ${column.type}`,
      range
    }));
  }

  function resolveAliasObject(sql: string, alias: string, objects: DbObject[]) {
    const aliasMap = new Map<string, string>();
    const tablePattern = /\b(?:from|join)\s+((?:"[^"]+"|`[^`]+`|\[[^\]]+\]|[A-Za-z0-9_.$]+))(?:\s+(?:as\s+)?([A-Za-z_][\w$]*))?/gi;
    const reserved = new Set(['where', 'join', 'left', 'right', 'inner', 'outer', 'full', 'cross', 'on', 'group', 'order', 'having', 'limit']);
    let match: RegExpExecArray | null;
    while ((match = tablePattern.exec(sql)) !== null) {
      const tableName = stripSqlIdentifier(match[1]);
      const tableParts = tableName.split('.');
      const defaultAlias = tableParts[tableParts.length - 1];
      const aliasName = match[2] && !reserved.has(match[2].toLowerCase()) ? match[2] : defaultAlias;
      aliasMap.set(aliasName.toLowerCase(), tableName);
    }
    const tableName = aliasMap.get(alias.toLowerCase());
    if (!tableName) return null;
    return objects.find((object) => matchesObjectName(object, tableName)) || objectFromTableName(tableName);
  }

  function objectFromTableName(tableName: string): DbObject {
    const parts = tableName.split('.').filter(Boolean);
    const name = parts[parts.length - 1] || tableName;
    const schemaName = parts.length > 1 ? parts[parts.length - 2] : undefined;
    return { schemaName, name, type: 'TABLE', columns: [], indexes: [] };
  }

  function matchesObjectName(object: DbObject, tableName: string) {
    const normalizedTable = tableName.toLowerCase();
    const objectName = object.name.toLowerCase();
    const qualifiedName = object.schemaName ? `${object.schemaName}.${object.name}`.toLowerCase() : objectName;
    return normalizedTable === objectName || normalizedTable === qualifiedName || normalizedTable.endsWith(`.${objectName}`);
  }

  function stripSqlIdentifier(value: string) {
    return value
      .replace(/^["`\[]/, '')
      .replace(/["`\]]$/, '');
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

  async function openObjectDetail(object: DbObject) {
    if (!selected) return;
    setObjectDetailLoading(true);
    try {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      const detail = await api<ObjectDetail>(`/metadata/${selected.id}/objects/detail?${params.toString()}`);
      setActiveObjectDetail(detail);
      setMode('object');
      setMessage(`已加载对象详情：${detail.name}`);
    } catch (e) {
      showError(localizeMessage((e as Error).message));
    } finally {
      setObjectDetailLoading(false);
    }
  }

  async function loadTable(table = activeTable) {
    if (!selected || !table) return;
    setTableLoading(true);
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
      showError(localizeMessage((e as Error).message));
    } finally {
      setTableLoading(false);
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

  async function importRows(file: File) {
    if (!activeTable || !tableData) {
      showInfo('请先打开一张表再导入数据');
      return;
    }
    setTableLoading(true);
    try {
      const result = parseImportFile(await file.text(), file.name, activeTable.tableName, tableData.columns);
      const importedRows = result.rows.map((values, index) => ({
        id: `import-${Date.now()}-${index}`,
        values,
        inserted: true
      }));
      setTableRows((rows) => [...importedRows, ...rows]);
      setPreviewSql([]);
      showSuccess(result.message);
    } catch (e) {
      showError(`导入失败：${localizeMessage((e as Error).message)}`);
    } finally {
      setTableLoading(false);
    }
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
      showInfo('没有待提交的变更');
      return;
    }
    setTableLoading(true);
    try {
      const data = await api<{ sql: string[] }>('/data/preview', { method: 'POST', body: JSON.stringify(dataChangePayload()) });
      setPreviewSql(data.sql);
      showSuccess(`已生成 ${data.sql.length} 条变更语句`);
    } catch (e) {
      showError(localizeMessage((e as Error).message));
    } finally {
      setTableLoading(false);
    }
  }

  async function commitChanges() {
    if (!selected || !activeTable) return;
    if (!pendingChanges.length) {
      showInfo('没有待提交的变更');
      return;
    }
    setTableLoading(true);
    try {
      const data = await api<{ sql: string[]; affectedRows: number }>('/data/commit', { method: 'POST', body: JSON.stringify(dataChangePayload()) });
      setPreviewSql(data.sql);
      showSuccess(`已提交，影响 ${data.affectedRows} 行`);
      await loadTable(activeTable);
    } catch (e) {
      showError(localizeMessage((e as Error).message));
    } finally {
      setTableLoading(false);
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

  async function saveBackup(id: number | null, form: BackupTaskForm) {
    if (!selected) {
      showInfo('请先选择一个数据库连接');
      throw new Error('Connection is required');
    }
    setBackupLoading(true);
    try {
      const payload = {
        name: form.name,
        connectionId: selected.id,
        scope: form.scope,
        schemaName: form.scope === 'TABLE' ? form.schemaName : undefined,
        tableName: form.scope === 'TABLE' ? form.tableName : undefined,
        backupMethod: form.backupMethod || 'SQL',
        toolPath: form.toolPath,
        extraArgs: form.extraArgs,
        nativeConnectName: form.nativeConnectName,
        cron: form.cron,
        enabled: form.enabled
      };
      const task = id
        ? await api<BackupTask>(`/backups/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
        : await api<BackupTask>('/backups', { method: 'POST', body: JSON.stringify(payload) });
      showSuccess(id ? `已更新备份任务：${task.name}` : `已创建备份任务：${task.name}`);
      await refreshBackups(selected);
    } catch (e) {
      showError(`${id ? '更新' : '创建'}备份任务失败：${localizeMessage((e as Error).message)}`);
      throw e;
    } finally {
      setBackupLoading(false);
    }
  }

  async function toggleBackup(id: number, enabled: boolean) {
    setBackupLoading(true);
    try {
      const task = await api<BackupTask>(`/backups/${id}/enabled`, { method: 'PATCH', body: JSON.stringify({ enabled }) });
      showSuccess(`${task.name} 已${task.enabled ? '启用' : '停用'}`);
      await refreshBackups(selected);
    } catch (e) {
      showError(`更新备份任务状态失败：${localizeMessage((e as Error).message)}`);
    } finally {
      setBackupLoading(false);
    }
  }

  async function deleteBackup(id: number, deleteFile: boolean) {
    setBackupLoading(true);
    try {
      await api<{ ok: boolean; message: string }>(`/backups/${id}?deleteFile=${deleteFile}`, { method: 'DELETE' });
      showSuccess(deleteFile ? '已删除备份任务和所有历史备份文件' : '已删除备份任务');
      await refreshBackups(selected);
    } catch (e) {
      showError(`删除备份任务失败：${localizeMessage((e as Error).message)}`);
      throw e;
    } finally {
      setBackupLoading(false);
    }
  }

  async function runBackup(id: number) {
    setBackupLoading(true);
    try {
      const task = await api<BackupTask>(`/backups/${id}/run`, { method: 'POST' });
      showSuccess(localizeMessage(task.lastMessage || '备份任务已执行'));
      await refreshBackups(selected);
    } catch (e) {
      showError(`备份执行失败：${localizeMessage((e as Error).message)}`);
      await refreshBackups(selected);
    } finally {
      setBackupLoading(false);
    }
  }

  async function downloadBackup(id: number) {
    setBackupLoading(true);
    try {
      const response = await fetch(`${API}/backups/${id}/download`);
      if (!response.ok) {
        const err = await response.json().catch(() => ({ message: response.statusText }));
        throw new Error(err.message || response.statusText);
      }
      const blob = await response.blob();
      const disposition = response.headers.get('content-disposition') || '';
      const filename = disposition.match(/filename="([^"]+)"/)?.[1] || `backup-${id}.sql`;
      downloadBlob(blob, filename);
      showSuccess(`已下载备份文件：${filename}`);
    } catch (e) {
      showError(`下载备份失败：${localizeMessage((e as Error).message)}`);
    } finally {
      setBackupLoading(false);
    }
  }

  async function loadBackupHistory(id: number) {
    setBackupLoading(true);
    try {
      return await api<BackupHistory[]>(`/backups/${id}/history`);
    } catch (e) {
      showError(`加载备份历史失败：${localizeMessage((e as Error).message)}`);
      throw e;
    } finally {
      setBackupLoading(false);
    }
  }

  async function deleteBackupHistory(taskId: number, historyId: number, deleteFile: boolean) {
    setBackupLoading(true);
    try {
      await api<{ ok: boolean; message: string }>(`/backups/${taskId}/history/${historyId}?deleteFile=${deleteFile}`, { method: 'DELETE' });
      showSuccess(deleteFile ? '已删除备份历史和对应文件' : '已删除备份历史');
      await refreshBackups(selected);
    } catch (e) {
      showError(`删除备份历史失败：${localizeMessage((e as Error).message)}`);
      throw e;
    } finally {
      setBackupLoading(false);
    }
  }

  async function downloadBackupHistory(taskId: number, historyId: number) {
    setBackupLoading(true);
    try {
      const response = await fetch(`${API}/backups/${taskId}/history/${historyId}/download`);
      if (!response.ok) {
        const err = await response.json().catch(() => ({ message: response.statusText }));
        throw new Error(err.message || response.statusText);
      }
      const blob = await response.blob();
      const disposition = response.headers.get('content-disposition') || '';
      const filename = disposition.match(/filename="([^"]+)"/)?.[1] || `backup-history-${historyId}.sql`;
      downloadBlob(blob, filename);
      showSuccess(`已下载备份文件：${filename}`);
    } catch (e) {
      showError(`下载备份历史失败：${localizeMessage((e as Error).message)}`);
    } finally {
      setBackupLoading(false);
    }
  }


  const sqlStatusMessage = sqlLoading ? '处理中...' : activeSqlTab.message || '就绪';
  const tableStatusMessage = tableLoading ? '处理中...' : message || '就绪';
  const objectStatusMessage = objectDetailLoading ? '处理中...' : message || '就绪';

  return (
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#1f6feb', borderRadius: 6 } }}>
      {toastContextHolder}
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
              onSelect={selectConnection}
              onEdit={editConnection}
              onTest={testSavedConnection}
              onDuplicate={duplicateConnection}
              onDelete={deleteConnection}
            />
            <Card size="small" title="数据库对象" className="panel-card">
              <Space direction="vertical" size={8} className="full-width">
                <Button size="small" icon={<ReloadOutlined />} block disabled={!selected || metadataLoading} loading={metadataLoading} onClick={() => loadMetadata(selected, { page: 0, refresh: true })}>
                  刷新缓存
                </Button>
                {metadata?.cachedAt && (
                  <span className="metadata-cache-status">
                    {metadata.cacheHit ? '来自缓存' : '已刷新'} · {new Date(metadata.cachedAt).toLocaleString()}
                  </span>
                )}
                <Select
                  size="small"
                  allowClear
                  showSearch
                  className="full-width"
                  placeholder="全部 Schema"
                  value={metadataQuery.schema || undefined}
                  disabled={!selected || metadataLoading}
                  options={(metadata?.schemas || []).map((schema) => ({ value: schema, label: schema }))}
                  onChange={(schema) => {
                    const nextSchema = schema || '';
                    setMetadataQuery((current) => ({ ...current, schema: nextSchema }));
                    loadMetadata(selected, { schema: nextSchema, page: 0 });
                  }}
                />
                <Input.Search
                  size="small"
                  allowClear
                  placeholder="搜索对象"
                  disabled={!selected || metadataLoading}
                  value={metadataQuery.keyword}
                  onChange={(event) => setMetadataQuery((current) => ({ ...current, keyword: event.target.value }))}
                  onSearch={(keyword) => {
                    setMetadataQuery((current) => ({ ...current, keyword }));
                    loadMetadata(selected, { keyword, page: 0 });
                  }}
                />
                <ObjectTree
                  objects={objects}
                  structureLoadingKey={structureLoadingKey}
                  onLoadStructure={loadObjectStructure}
                  onOpenDetail={openObjectDetail}
                  onOpenTable={openTable}
                />
                {metadata?.hasMore && (
                  <Button
                    size="small"
                    block
                    disabled={metadataLoading}
                    onClick={() => loadMetadata(selected, { page: metadata.page + 1, append: true })}
                  >
                    加载更多
                  </Button>
                )}
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
              onResultTabChange={(key) => updateActiveSqlTab({ activeResultKey: key })}
            />
          ) : mode === 'table' ? (
            <TableWorkspace
              activeTable={activeTable}
              tableData={tableData}
              tableRows={tableRows}
              previewSql={previewSql}
              pendingCount={pendingChanges.length}
              statusMessage={`${tableStatusMessage} · 待提交变更：${pendingChanges.length}`}
              loading={tableLoading}
              onBackToSql={() => setMode('sql')}
              onReload={() => loadTable()}
              onAddRow={addRow}
              onImportFile={importRows}
              onPreview={previewChanges}
              onCommit={commitChanges}
              onEdit={editCell}
              onDelete={deleteRow}
            />
          ) : (
            <ObjectDetailWorkspace
              connectionId={selected?.id}
              readonlyConnection={selected?.readonly}
              detail={activeObjectDetail}
              statusMessage={objectStatusMessage}
              loading={objectDetailLoading}
              onBackToSql={() => setMode('sql')}
              onOpenTable={openTable}
              onReloadDetail={() => activeObjectDetail && openObjectDetail(activeObjectDetail)}
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
                    selected={selected}
                    editing={Boolean(editingConnectionId)}
                    loading={connectionActionLoading}
                    onChange={setForm}
                    onDbTypeChange={changeDbType}
                    onReset={resetConnectionForm}
                    onEdit={editConnection}
                    onDuplicate={duplicateConnection}
                    onDelete={deleteConnection}
                    onTest={() => testConnection()}
                    onSave={saveConnection}
                  />
                )
              },
              {
                key: 'backup',
                label: '备份任务',
                children: (
                  <BackupPanel
                    backups={backups}
                    selected={selected}
                    activeTable={activeTable}
                    loading={backupLoading}
                    onSave={saveBackup}
                    onToggle={toggleBackup}
                    onDelete={deleteBackup}
                    onRun={runBackup}
                    onDownload={downloadBackup}
                    onLoadHistory={loadBackupHistory}
                    onDeleteHistory={deleteBackupHistory}
                    onDownloadHistory={downloadBackupHistory}
                  />
                )
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
