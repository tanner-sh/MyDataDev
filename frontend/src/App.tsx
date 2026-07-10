import { useEffect, useMemo, useRef, useState } from 'react';
import type { OnMount } from '@monaco-editor/react';
import type * as Monaco from 'monaco-editor';
import { Button, ConfigProvider, Drawer, Input, Modal, Select, Space, Spin, Tag, Tooltip, Typography, message as antdMessage, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { api, downloadBlob } from './api';
import { API, DB_TYPE_OPTIONS, EMPTY_FORM, PASSWORD_MASK } from './constants';
import { parseImportFile } from './importers';
import type { ActiveTable, BackupHistory, BackupTask, BackupTaskForm, Connection, ConnectionForm, DbObject, ExportFormat, Metadata, ObjectDetail, ObjectStructure, RefreshConnectionsOptions, SqlCompletionItem, SqlHistory, SqlResult, SqlScriptResult, SqlStatementResult, SqlTab, TableData, TableRow, WorkspaceStatus } from './types';
import { buildChanges, completionKind, createSqlTab, dbTypeLabel, localizeMessage, normalizeEnvironment, sleep, sqlKeywordCompletionItems, timestamp } from './utils';
import { BackupPanel } from './components/BackupPanel';
import { AppHeader } from './components/AppHeader';
import { ConnectionFormPanel } from './components/ConnectionFormPanel';
import { ConnectionList } from './components/ConnectionList';
import { ObjectDetailWorkspace } from './components/ObjectDetailWorkspace';
import { ObjectTree } from './components/ObjectTree';
import { PaneResizer } from './components/PaneResizer';
import { SqlHistoryDrawer } from './components/SqlHistoryDrawer';
import { SqlWorkspace } from './components/SqlWorkspace';
import { TableWorkspace } from './components/TableWorkspace';
import { useLayoutPreferences } from './hooks/useLayoutPreferences';

const { Text } = Typography;
const OBJECT_PAGE_SIZE = 200;

export default function App() {
  const [connections, setConnections] = useState<Connection[]>([]);
  const [selected, setSelected] = useState<Connection | null>(null);
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [metadataQuery, setMetadataQuery] = useState({ schema: '', keyword: '' });
  const [structureLoadingKey, setStructureLoadingKey] = useState<string | null>(null);
  const [sqlTabs, setSqlTabs] = useState<SqlTab[]>([{ id: 'query-1', title: '查询 1', sql: 'select 1 as val', results: [], message: '' }]);
  const [activeSqlTabId, setActiveSqlTabId] = useState('query-1');
  const [workspaceStatus, setWorkspaceStatus] = useState<WorkspaceStatus>({ kind: 'idle', text: '就绪' });
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
  const [activeDrawer, setActiveDrawer] = useState<'connections' | 'backups' | null>(null);
  const [compactLayout, setCompactLayout] = useState(false);
  const [mobileExplorerOpen, setMobileExplorerOpen] = useState(false);
  const selectedIdRef = useRef<number | null>(null);
  const metadataRef = useRef<Metadata | null>(null);
  const structureCacheRef = useRef<Map<string, DbObject>>(new Map());
  const metadataRequestSeqRef = useRef(0);
  const structureRequestSeqRef = useRef(0);
  const objectDetailRequestSeqRef = useRef(0);
  const tableRequestSeqRef = useRef(0);
  const backupRequestSeqRef = useRef(0);
  const editorRef = useRef<Monaco.editor.IStandaloneCodeEditor | null>(null);
  const executeRef = useRef<() => void>(() => undefined);
  const formatRef = useRef<() => void>(() => undefined);
  const sqlTabSeqRef = useRef(1);
  const [toastApi, toastContextHolder] = antdMessage.useMessage();
  const [modalApi, modalContextHolder] = Modal.useModal();
  const layoutPreferences = useLayoutPreferences();

  const objects = useMemo(() => metadata?.objects || [], [metadata]);
  const pendingChanges = useMemo(() => buildChanges(tableRows, tableData?.keyColumns || []), [tableRows, tableData]);
  const activeSqlTab = useMemo(() => sqlTabs.find((tab) => tab.id === activeSqlTabId) || sqlTabs[0], [activeSqlTabId, sqlTabs]);
  const connectionFormBaseline = useMemo<ConnectionForm>(() => {
    if (!editingConnectionId || selected?.id !== editingConnectionId) return EMPTY_FORM;
    return {
      name: selected.name,
      dbType: selected.dbType,
      jdbcUrl: selected.jdbcUrl,
      username: selected.username || '',
      password: PASSWORD_MASK,
      environment: normalizeEnvironment(selected.environment),
      readonly: selected.readonly
    };
  }, [editingConnectionId, selected]);
  const connectionFormDirty = useMemo(
    () => JSON.stringify(form) !== JSON.stringify(connectionFormBaseline),
    [form, connectionFormBaseline]
  );

  useEffect(() => {
    refreshConnections({ retry: true });
  }, []);

  useEffect(() => {
    const media = window.matchMedia('(max-width: 1199px)');
    const syncLayout = () => setCompactLayout(media.matches);
    syncLayout();
    media.addEventListener('change', syncLayout);
    return () => media.removeEventListener('change', syncLayout);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = layoutPreferences.themeMode;
    document.documentElement.style.colorScheme = layoutPreferences.themeMode;
  }, [layoutPreferences.themeMode]);

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'b') {
        event.preventDefault();
        if (compactLayout) {
          setMobileExplorerOpen((current) => !current);
        } else {
          layoutPreferences.toggleExplorer();
        }
      }
    };
    window.addEventListener('keydown', handleShortcut);
    return () => window.removeEventListener('keydown', handleShortcut);
  }, [compactLayout, layoutPreferences.toggleExplorer]);

  useEffect(() => {
    if (pendingChanges.length === 0) return;
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [pendingChanges.length]);

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
    setWorkspaceStatus({ kind: 'success', text });
    toastApi.success(text);
  }

  function showError(text: string) {
    setWorkspaceStatus({ kind: 'error', text });
    toastApi.error(text);
  }

  function showInfo(text: string) {
    setWorkspaceStatus({ kind: 'info', text });
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
    const requestId = ++backupRequestSeqRef.current;
    try {
      const rows = await api<BackupTask[]>(`/backups?connectionId=${conn.id}`);
      if (requestId === backupRequestSeqRef.current && selectedIdRef.current === conn.id) {
        setBackups(rows);
      }
    } catch (error) {
      if (requestId === backupRequestSeqRef.current && selectedIdRef.current === conn.id) throw error;
    }
  }

  async function refreshSqlHistory(conn = selected) {
    if (!conn) {
      setSqlHistory([]);
      return;
    }
    const rows = await api<SqlHistory[]>(`/sql/history?connectionId=${conn.id}&limit=50`);
    setSqlHistory(rows);
  }

  async function refreshSqlHistoryQuietly(conn = selected) {
    try {
      await refreshSqlHistory(conn);
    } catch {
      toastApi.warning('SQL 已处理，但历史记录刷新失败，可稍后重新打开历史。');
    }
  }

  async function saveConnection() {
    setConnectionActionLoading(true);
    try {
      const saved = editingConnectionId
        ? await api<Connection>(`/connections/${editingConnectionId}`, { method: 'PUT', body: JSON.stringify(form) })
        : await api<Connection>('/connections', { method: 'POST', body: JSON.stringify(form) });
      applyConnectionSelection(saved);
      setEditingConnectionId(saved.id);
      setForm({
        name: saved.name,
        dbType: saved.dbType,
        jdbcUrl: saved.jdbcUrl,
        username: saved.username || '',
        password: PASSWORD_MASK,
        environment: normalizeEnvironment(saved.environment),
        readonly: saved.readonly
      });
      showSuccess(editingConnectionId ? `已更新连接：${saved.name}` : `已创建连接：${saved.name}`);
      await refreshConnections();
    } catch (e) {
      showError(localizeMessage((e as Error).message));
    } finally {
      setConnectionActionLoading(false);
    }
  }

  function requestSaveConnection() {
    if (pendingChanges.length === 0) {
      void saveConnection();
      return;
    }
    modalApi.confirm({
      title: '保存连接将关闭当前数据表',
      content: `当前表有 ${pendingChanges.length} 项待提交变更。连接保存成功后，这些变更将被放弃。`,
      okText: '保存并放弃变更',
      cancelText: '返回处理变更',
      okButtonProps: { danger: true },
      onOk: saveConnection
    });
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
        const nextConnection = remaining[0] || null;
        if (nextConnection) {
          applyConnectionSelection(nextConnection);
        } else {
          invalidateConnectionRequests();
          setSelected(null);
          setMetadata(null);
          setMetadataQuery({ schema: '', keyword: '' });
          structureCacheRef.current.clear();
          setStructureLoadingKey(null);
          setActiveObjectDetail(null);
          clearTableWorkspace();
          setMode('sql');
        }
      }
      if (editingConnectionId === connection.id) {
        resetConnectionForm();
      }
      await refreshConnections();
    } catch (e) {
      const rawMessage = (e as Error).message;
      const blockedByBackups = rawMessage.includes('backup task');
      showError(blockedByBackups ? '该连接存在关联备份任务，请先切换到“备份任务”删除相关任务后再删除连接。' : localizeMessage(rawMessage));
      await refreshSqlHistoryQuietly(selected);
    } finally {
      setConnectionActionLoading(false);
    }
  }

  function applyConnectionSelection(connection: Connection) {
    invalidateConnectionRequests();
    setSelected(connection);
    setMetadata(null);
    setMetadataQuery({ schema: '', keyword: '' });
    structureCacheRef.current.clear();
    setStructureLoadingKey(null);
    setActiveObjectDetail(null);
    clearTableWorkspace();
    setMode('sql');
    setMobileExplorerOpen(false);
  }

  function invalidateConnectionRequests() {
    metadataRequestSeqRef.current += 1;
    structureRequestSeqRef.current += 1;
    objectDetailRequestSeqRef.current += 1;
    tableRequestSeqRef.current += 1;
    backupRequestSeqRef.current += 1;
  }

  function selectConnection(connection: Connection) {
    if (selected?.id === connection.id) return;
    if (sqlLoading || tableLoading || objectDetailLoading) {
      showInfo('请等待当前操作完成后再切换连接');
      return;
    }
    confirmDiscardConnectionDraft(() => {
      confirmDiscardTableChanges(() => {
        resetConnectionForm();
        applyConnectionSelection(connection);
      }, `切换到连接“${connection.name}”`);
    });
  }

  function editConnection(connection: Connection) {
    confirmDiscardConnectionDraft(() => {
      confirmDiscardTableChanges(() => {
        if (selected?.id !== connection.id) applyConnectionSelection(connection);
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
        setActiveDrawer('connections');
        showInfo(`正在编辑连接：${connection.name}。密码显示为 ${PASSWORD_MASK} 表示沿用已保存密码。`);
      }, `编辑连接“${connection.name}”`);
    });
  }

  function duplicateConnection(connection: Connection) {
    confirmDiscardConnectionDraft(() => {
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
      setActiveDrawer('connections');
      showInfo('已复制连接配置，请输入密码后保存为新连接。');
    });
  }

  function resetConnectionForm() {
    setEditingConnectionId(null);
    setForm(EMPTY_FORM);
  }

  function requestResetConnectionForm() {
    confirmDiscardConnectionDraft(resetConnectionForm);
  }

  function confirmDiscardConnectionDraft(action: () => void) {
    if (!connectionFormDirty) {
      action();
      return;
    }
    modalApi.confirm({
      title: '放弃未保存的连接配置？',
      content: '当前连接表单已修改，继续操作将丢失这些内容。',
      okText: '放弃修改',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: action
    });
  }

  function closeConnectionDrawer() {
    if (!connectionFormDirty) {
      setActiveDrawer(null);
      return;
    }
    modalApi.confirm({
      title: '放弃未保存的连接配置？',
      content: '当前连接表单已修改，关闭后这些内容不会保留。',
      okText: '放弃并关闭',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: () => {
        resetConnectionForm();
        setActiveDrawer(null);
      }
    });
  }

  function discardTableChanges() {
    if (!tableData) return;
    setTableRows(tableData.rows.map((row, index) => ({ id: `row-${index}`, values: { ...row }, original: { ...row } })));
    setPreviewSql([]);
  }

  function clearTableWorkspace() {
    setActiveTable(null);
    setTableData(null);
    setTableRows([]);
    setPreviewSql([]);
  }

  function confirmDiscardTableChanges(action: () => void, nextAction = '离开当前数据表') {
    if (pendingChanges.length === 0) {
      action();
      return;
    }
    modalApi.confirm({
      title: '存在未提交的数据变更',
      content: `${nextAction}将放弃 ${pendingChanges.length} 项待提交变更。`,
      okText: '放弃并继续',
      cancelText: '留在当前表',
      okButtonProps: { danger: true },
      onOk: () => {
        discardTableChanges();
        action();
      }
    });
  }

  async function loadMetadata(conn = selected, options: { schema?: string; keyword?: string; page?: number; append?: boolean; refresh?: boolean } = {}) {
    if (!conn) return;
    const requestId = ++metadataRequestSeqRef.current;
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
      if (requestId !== metadataRequestSeqRef.current || selectedIdRef.current !== conn.id) return;
      setMetadataQuery((current) => ({ ...current, schema: data.selectedSchema || '' }));
      setMetadata((current) => {
        if (!options.append || !current) {
          return data;
        }
        return { ...data, objects: [...current.objects, ...data.objects] };
      });
      const loadedCount = (options.append ? (metadata?.objects.length || 0) : 0) + data.objects.length;
      const cacheText = data.cacheHit ? '来自缓存' : '已刷新缓存';
      const timeText = data.cachedAt ? `，缓存时间 ${new Date(data.cachedAt).toLocaleString()}` : '';
      const scopeText = data.selectedSchema ? `Schema ${data.selectedSchema}` : '当前数据库';
      const nextMessage = data.hasMore
        ? `${cacheText}${timeText}，${scopeText} 已加载 ${loadedCount}/${data.totalObjects} 个对象`
        : `${cacheText}${timeText}，${scopeText} 共 ${data.totalObjects} 个对象`;
      if (options.refresh) {
        showSuccess(nextMessage);
      } else {
        setWorkspaceStatus({ kind: 'success', text: nextMessage });
      }
    } catch (e) {
      if (requestId === metadataRequestSeqRef.current && selectedIdRef.current === conn.id) {
        showError(localizeMessage((e as Error).message));
      }
    } finally {
      if (requestId === metadataRequestSeqRef.current) setMetadataLoading(false);
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

    const requestId = ++structureRequestSeqRef.current;
    setStructureLoadingKey(`${object.schemaName || ''}.${object.name}`);
    try {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      const structure = await api<ObjectStructure>(`/metadata/${connectionId}/objects/structure?${params.toString()}`);
      if (requestId !== structureRequestSeqRef.current || selectedIdRef.current !== connectionId) return null;
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
      if (requestId === structureRequestSeqRef.current) showError(localizeMessage((e as Error).message));
      return null;
    } finally {
      if (requestId === structureRequestSeqRef.current) setStructureLoadingKey(null);
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
      updateActiveSqlTab({ message: '请先选择一个数据库连接', statusKind: 'info' });
      showInfo('请先选择一个数据库连接');
      return;
    }
    const target = sqlExecutionTarget();
    if (!target.sql.trim()) {
      updateActiveSqlTab({ message: '请输入要执行的 SQL', statusKind: 'info' });
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
        updateActiveSqlTab({ results: [result], activeResultKey: statementResultKey(result), message: nextMessage, statusKind: 'success' });
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
          message: nextMessage,
          statusKind: failed ? 'error' : 'success'
        });
        if (failed) {
          selectStatementRange(target.baseOffset + failed.startOffset, target.baseOffset + failed.endOffset);
        }
      }
      await refreshSqlHistoryQuietly(selected);
    } catch (e) {
      const errorMessage = localizeMessage((e as Error).message);
      updateActiveSqlTab({ message: errorMessage, statusKind: 'error' });
      toastApi.error(errorMessage);
      await refreshSqlHistoryQuietly(selected);
    } finally {
      setSqlLoading(false);
    }
  }

  async function formatSql() {
    if (!activeSqlTab.sql.trim()) {
      updateActiveSqlTab({ message: '请输入要格式化的 SQL', statusKind: 'info' });
      return;
    }
    setSqlLoading(true);
    try {
      const data = await api<{ sql: string }>('/sql/format', { method: 'POST', body: JSON.stringify({ sql: activeSqlTab.sql }) });
      updateActiveSqlTab({ sql: data.sql, message: 'SQL 格式化完成', statusKind: 'success' });
    } catch (e) {
      const errorMessage = `格式化失败：${localizeMessage((e as Error).message)}`;
      updateActiveSqlTab({ message: errorMessage, statusKind: 'error' });
      toastApi.error(errorMessage);
    } finally {
      setSqlLoading(false);
    }
  }

  async function openSqlHistory() {
    try {
      await refreshSqlHistory();
      setHistoryOpen(true);
    } catch (e) {
      const errorMessage = `历史记录加载失败：${localizeMessage((e as Error).message)}`;
      updateActiveSqlTab({ message: errorMessage, statusKind: 'error' });
      toastApi.error(errorMessage);
    }
  }

  async function exportSql(format: ExportFormat) {
    if (!selected) {
      updateActiveSqlTab({ message: '请先选择一个数据库连接', statusKind: 'info' });
      showInfo('请先选择一个数据库连接');
      return;
    }
    const target = sqlExecutionTarget();
    if (!target.sql.trim()) {
      updateActiveSqlTab({ message: '请输入要导出的 SQL', statusKind: 'info' });
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
      updateActiveSqlTab({ message: nextMessage, statusKind: 'success' });
      toastApi.success(nextMessage);
    } catch (e) {
      const errorMessage = `导出失败：${localizeMessage((e as Error).message)}`;
      updateActiveSqlTab({ message: errorMessage, statusKind: 'error' });
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

  function openTable(object: DbObject) {
    if (!selected || object.type.toUpperCase().includes('VIEW')) {
      return;
    }
    const targetName = `${object.schemaName ? `${object.schemaName}.` : ''}${object.name}`;
    confirmDiscardTableChanges(() => {
      void applyOpenTable(object);
    }, `打开数据表“${targetName}”`);
  }

  async function applyOpenTable(object: DbObject) {
    const next = { schemaName: object.schemaName, tableName: object.name };
    setActiveTable(next);
    setMode('table');
    await loadTable(next);
  }

  function openObjectDetail(object: DbObject) {
    confirmDiscardTableChanges(() => {
      void loadObjectDetail(object);
    }, `查看对象“${object.name}”`);
  }

  async function loadObjectDetail(object: DbObject) {
    if (!selected) return;
    const connectionId = selected.id;
    const requestId = ++objectDetailRequestSeqRef.current;
    setObjectDetailLoading(true);
    try {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      const detail = await api<ObjectDetail>(`/metadata/${connectionId}/objects/detail?${params.toString()}`);
      if (requestId !== objectDetailRequestSeqRef.current || selectedIdRef.current !== connectionId) return;
      setActiveObjectDetail(detail);
      setMode('object');
      setWorkspaceStatus({ kind: 'success', text: `已加载对象详情：${detail.name}` });
    } catch (e) {
      if (requestId === objectDetailRequestSeqRef.current) showError(localizeMessage((e as Error).message));
    } finally {
      if (requestId === objectDetailRequestSeqRef.current) setObjectDetailLoading(false);
    }
  }

  async function loadTable(table = activeTable) {
    if (!selected || !table) return;
    const connectionId = selected.id;
    const requestId = ++tableRequestSeqRef.current;
    setTableLoading(true);
    try {
      const params = new URLSearchParams({
        connectionId: String(connectionId),
        tableName: table.tableName,
        page: '0',
        pageSize: '100'
      });
      if (table.schemaName) params.set('schemaName', table.schemaName);
      const data = await api<TableData>(`/data/table?${params.toString()}`);
      if (requestId !== tableRequestSeqRef.current || selectedIdRef.current !== connectionId) return;
      setTableData(data);
      setTableRows(data.rows.map((row, index) => ({ id: `row-${index}`, values: { ...row }, original: { ...row } })));
      setPreviewSql([]);
      setWorkspaceStatus({ kind: 'success', text: `已从 ${table.tableName} 加载 ${data.rows.length} 行数据` });
    } catch (e) {
      if (requestId === tableRequestSeqRef.current) showError(localizeMessage((e as Error).message));
    } finally {
      if (requestId === tableRequestSeqRef.current) setTableLoading(false);
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
      return row.inserted ? [] : [{ ...row, deleted: !row.deleted }];
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

  const sqlStatus: WorkspaceStatus = sqlLoading
    ? { kind: 'loading', text: '正在执行 SQL…' }
    : sqlStatusFromTab(activeSqlTab);
  const tableStatus: WorkspaceStatus = tableLoading
    ? { kind: 'loading', text: '正在处理表数据…' }
    : workspaceStatus;
  const objectStatus: WorkspaceStatus = objectDetailLoading
    ? { kind: 'loading', text: '正在加载对象详情…' }
    : workspaceStatus;

  const explorerPanel = (
    <div className="resource-explorer">
      <div className="explorer-header">
        <div>
          <Text strong>资源管理器</Text>
          <Text type="secondary">数据库对象</Text>
        </div>
        <Space size={2}>
          <Tooltip title="刷新对象缓存">
            <Button
              type="text"
              size="small"
              icon={<ReloadOutlined />}
              loading={metadataLoading}
              disabled={!selected}
              aria-label="刷新对象缓存"
              onClick={() => loadMetadata(selected, { page: 0, refresh: true })}
            />
          </Tooltip>
          {compactLayout && (
            <Tooltip title="关闭资源管理器">
              <Button type="text" size="small" icon={<CloseOutlined />} aria-label="关闭资源管理器" onClick={() => setMobileExplorerOpen(false)} />
            </Tooltip>
          )}
        </Space>
      </div>

      {selected ? (
        <div className="explorer-connection-summary">
          <div className="explorer-connection-title">
            <span className="connection-dot" aria-hidden="true" />
            <Text strong ellipsis>{selected.name}</Text>
          </div>
          <Space size={4} wrap>
            <Tag bordered={false} color="blue">{dbTypeLabel(selected.dbType)}</Tag>
            {selected.readonly && <Tag bordered={false} color="orange">只读</Tag>}
            {metadata?.selectedSchema && <Tag bordered={false}>Schema · {metadata.selectedSchema}</Tag>}
          </Space>
          <Text type="secondary" ellipsis title={selected.jdbcUrl}>{selected.jdbcUrl}</Text>
        </div>
      ) : (
        <button className="explorer-empty-connection" onClick={() => setActiveDrawer('connections')}>
          尚未选择连接，打开连接管理
        </button>
      )}

      <div className="explorer-filters">
        <Select
          size="small"
          allowClear
          showSearch
          className="full-width"
          placeholder="选择 Schema"
          value={metadataQuery.schema || metadata?.selectedSchema || undefined}
          disabled={!selected || metadataLoading}
          options={(metadata?.schemas || []).map((schema) => ({
            value: schema,
            label: schema === metadata?.currentSchema ? `${schema}（当前）` : schema
          }))}
          onChange={(schema) => {
            const nextSchema = schema || '';
            setMetadataQuery((current) => ({ ...current, schema: nextSchema }));
            loadMetadata(selected, { schema: nextSchema, page: 0 });
          }}
        />
        <Input.Search
          size="small"
          allowClear
          placeholder="搜索表或视图"
          disabled={!selected || metadataLoading}
          value={metadataQuery.keyword}
          onChange={(event) => setMetadataQuery((current) => ({ ...current, keyword: event.target.value }))}
          onSearch={(keyword) => {
            setMetadataQuery((current) => ({ ...current, keyword }));
            loadMetadata(selected, { keyword, page: 0 });
          }}
        />
      </div>

      {connectionsError && <div className="explorer-error" role="alert">{connectionsError}</div>}
      <div className="object-tree-scroll">
        {metadataLoading ? (
          <div className="explorer-loading" role="status">
            <Spin size="small" />
            <Text type="secondary">正在加载 {metadataQuery.schema || '当前 Schema'}…</Text>
          </div>
        ) : (
          <ObjectTree
            key={metadata?.selectedSchema || 'current-schema'}
            objects={objects}
            emptyDescription={metadataQuery.keyword ? '未找到匹配的表或视图' : '当前 Schema 暂无数据库对象'}
            structureLoadingKey={structureLoadingKey}
            onLoadStructure={loadObjectStructure}
            onOpenDetail={openObjectDetail}
            onOpenTable={openTable}
          />
        )}
      </div>
      <div className="explorer-footer">
        {metadata?.cachedAt && (
          <span className="metadata-cache-status">
            已加载 {objects.length}/{metadata.totalObjects} · {metadata.cacheHit ? '缓存数据' : '刚刚刷新'} · {new Date(metadata.cachedAt).toLocaleTimeString()}
          </span>
        )}
        {metadata?.hasMore && (
          <Button size="small" block disabled={metadataLoading} onClick={() => loadMetadata(selected, { page: metadata.page + 1, append: true })}>
            加载更多对象
          </Button>
        )}
      </div>
    </div>
  );

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: layoutPreferences.themeMode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: { colorPrimary: '#2f74e8', borderRadius: 7, controlHeight: 34, fontSize: 13 }
      }}
    >
      {toastContextHolder}
      {modalContextHolder}
      <div className="app-shell" data-theme={layoutPreferences.themeMode}>
        <AppHeader
          connections={connections}
          selected={selected}
          connectionsLoading={connectionsLoading}
          explorerCollapsed={compactLayout ? !mobileExplorerOpen : layoutPreferences.explorerCollapsed}
          themeMode={layoutPreferences.themeMode}
          onToggleExplorer={() => compactLayout ? setMobileExplorerOpen((current) => !current) : layoutPreferences.toggleExplorer()}
          onSelectConnection={selectConnection}
          onRefreshConnections={() => refreshConnections()}
          onOpenConnections={() => setActiveDrawer('connections')}
          onOpenBackups={() => setActiveDrawer('backups')}
          onToggleTheme={() => layoutPreferences.setThemeMode((current) => current === 'light' ? 'dark' : 'light')}
        />

        <div className="app-body">
          {!compactLayout && !layoutPreferences.explorerCollapsed && (
            <>
              <aside id="resource-explorer" className="app-sider" style={{ width: layoutPreferences.explorerWidth }}>
                {explorerPanel}
              </aside>
              <PaneResizer
                direction="horizontal"
                value={layoutPreferences.explorerWidth}
                min={240}
                max={480}
                ariaLabel="调整资源管理器宽度"
                controlsId="resource-explorer"
                onChange={layoutPreferences.setExplorerWidth}
              />
            </>
          )}

          <main className="app-content">
            {mode === 'sql' ? (
              <SqlWorkspace
                selected={selected}
                tabs={sqlTabs}
                activeTabId={activeSqlTab.id}
                activeTab={activeSqlTab}
                status={sqlStatus}
                loading={sqlLoading}
                themeMode={layoutPreferences.themeMode}
                editorSplitRatio={layoutPreferences.editorSplitRatio}
                onEditorSplitRatioChange={layoutPreferences.setEditorSplitRatio}
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
                status={tableStatus}
                loading={tableLoading}
                readonlyConnection={selected?.readonly}
                onBackToSql={() => confirmDiscardTableChanges(() => setMode('sql'), '返回 SQL 查询工作台')}
                onReload={() => confirmDiscardTableChanges(() => void loadTable(), '重新加载当前表')}
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
                status={objectStatus}
                loading={objectDetailLoading}
                onBackToSql={() => setMode('sql')}
                onOpenTable={openTable}
                onReloadDetail={() => activeObjectDetail && void loadObjectDetail(activeObjectDetail)}
              />
            )}
          </main>
        </div>
      </div>

      <Drawer
        title="资源管理器"
        width={380}
        open={compactLayout && mobileExplorerOpen}
        closeIcon={null}
        rootClassName="mobile-explorer-drawer"
        onClose={() => setMobileExplorerOpen(false)}
      >
        {explorerPanel}
      </Drawer>

      <Drawer
        title="连接管理"
        width={480}
        open={activeDrawer === 'connections'}
        rootClassName="management-drawer"
        maskClosable={!connectionFormDirty}
        onClose={closeConnectionDrawer}
      >
        <div className="connection-management-content">
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
          <ConnectionFormPanel
            form={form}
            selected={selected}
            editing={Boolean(editingConnectionId)}
            loading={connectionActionLoading}
            onChange={setForm}
            onDbTypeChange={changeDbType}
            onReset={requestResetConnectionForm}
            onEdit={editConnection}
            onDuplicate={duplicateConnection}
            onDelete={deleteConnection}
            onTest={() => testConnection()}
            onSave={requestSaveConnection}
          />
        </div>
      </Drawer>

      <Drawer
        title="备份任务"
        width={720}
        open={activeDrawer === 'backups'}
        rootClassName="management-drawer backup-management-drawer"
        onClose={() => setActiveDrawer(null)}
      >
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
      </Drawer>
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

function sqlStatusFromTab(tab: SqlTab): WorkspaceStatus {
  const text = tab.message || '就绪';
  if (tab.statusKind === 'error' || tab.results.some((result) => result.status === 'FAILED')) {
    return { kind: 'error', text };
  }
  return tab.message ? { kind: tab.statusKind || 'info', text } : { kind: 'idle', text };
}
