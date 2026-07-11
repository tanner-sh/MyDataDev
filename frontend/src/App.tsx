import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { OnMount } from '@monaco-editor/react';
import type * as Monaco from 'monaco-editor';
import { Button, ConfigProvider, Drawer, Input, Modal, Select, Space, Spin, Tag, Tooltip, Typography, message as antdMessage, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { api, downloadBlob } from './api';
import { API, DB_TYPE_OPTIONS, EMPTY_FORM, PASSWORD_MASK } from './constants';
import { parseImportFile } from './importers';
import type { ActiveTable, BackupEditorRequest, BackupHistory, BackupSchedulePreview, BackupTableTargetQuery, BackupTargetPage, BackupTargetQuery, BackupTask, BackupTaskForm, CompletionCatalog, Connection, ConnectionForm, DbObject, ExportFormat, Metadata, ObjectDetail, ObjectStructure, RefreshConnectionsOptions, SqlHistory, SqlResult, SqlScriptResult, SqlStatementResult, SqlTab, TableData, TableRow, WorkspaceStatus } from './types';
import { buildChanges, createSqlTab, dbTypeLabel, localizeMessage, normalizeEnvironment, sleep, sqlKeywordCompletionItems, timestamp } from './utils';
import { AsyncResourceCache } from './asyncResourceCache';
import { analyzeSqlCompletion, quoteSqlIdentifier, resolveSqlTableReference, sqlTableQualifier } from './sqlCompletion';
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
  const [objectDesignDirty, setObjectDesignDirty] = useState(false);
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
  const [tablePage, setTablePage] = useState(0);
  const [previewSql, setPreviewSql] = useState<string[]>([]);
  const [sqlHistory, setSqlHistory] = useState<SqlHistory[]>([]);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [activeDrawer, setActiveDrawer] = useState<'connections' | 'backups' | null>(null);
  const [backupEditorRequest, setBackupEditorRequest] = useState<BackupEditorRequest>();
  const [compactLayout, setCompactLayout] = useState(false);
  const [mobileExplorerOpen, setMobileExplorerOpen] = useState(false);
  const selectedIdRef = useRef<number | null>(null);
  const metadataRef = useRef<Metadata | null>(null);
  const completionCatalogCacheRef = useRef(new AsyncResourceCache<string, CompletionCatalog>());
  const completionStructureCacheRef = useRef(new AsyncResourceCache<string, DbObject>());
  const structureCacheRef = useRef<Map<string, DbObject>>(new Map());
  const metadataRequestSeqRef = useRef(0);
  const structureRequestSeqRef = useRef(0);
  const objectDetailRequestSeqRef = useRef(0);
  const tableRequestSeqRef = useRef(0);
  const backupRequestSeqRef = useRef(0);
  const objectDesignDirtyRef = useRef(false);
  const editorRef = useRef<Monaco.editor.IStandaloneCodeEditor | null>(null);
  const completionProviderRef = useRef<Monaco.IDisposable | null>(null);
  const executeRef = useRef<() => void>(() => undefined);
  const formatRef = useRef<() => void>(() => undefined);
  const sqlTabSeqRef = useRef(1);
  const backupEditorRequestSeqRef = useRef(0);
  const [toastApi, toastContextHolder] = antdMessage.useMessage();
  const [modalApi, modalContextHolder] = Modal.useModal();
  const layoutPreferences = useLayoutPreferences();
  const updateObjectDesignDirty = useCallback((dirty: boolean) => {
    objectDesignDirtyRef.current = dirty;
    setObjectDesignDirty(dirty);
  }, []);

  const objects = useMemo(() => metadata?.objects || [], [metadata]);
  const namespaceLabel = metadata?.namespaceKind === 'CATALOG' ? '数据库' : 'Schema';
  const currentBackupTable = useMemo<ActiveTable | null>(() => {
    const fallbackNamespace = metadata?.selectedSchema || metadata?.currentSchema || undefined;
    if (mode === 'table' && activeTable) {
      return { ...activeTable, schemaName: activeTable.schemaName || fallbackNamespace };
    }
    const objectType = activeObjectDetail?.type.toUpperCase() || '';
    if (mode !== 'object' || !activeObjectDetail || !objectType.includes('TABLE') || objectType.includes('VIEW')) return null;
    return { schemaName: activeObjectDetail.schemaName || fallbackNamespace, tableName: activeObjectDetail.name };
  }, [activeObjectDetail, activeTable, metadata?.currentSchema, metadata?.selectedSchema, mode]);
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
  const antThemeConfig = useMemo(() => ({
    algorithm: layoutPreferences.themeMode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: { colorPrimary: '#2f74e8', borderRadius: 7, controlHeight: 34, fontSize: 13 }
  }), [layoutPreferences.themeMode]);

  useEffect(() => {
    refreshConnections({ retry: true });
  }, []);

  useEffect(() => () => completionProviderRef.current?.dispose(), []);

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
    if (pendingChanges.length === 0 && !objectDesignDirty) return;
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [objectDesignDirty, pendingChanges.length]);

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
    if (selected && metadata?.selectedSchema) {
      void loadCompletionCatalog(selected.id, metadata.selectedSchema).catch(() => undefined);
    }
  }, [metadata, selected?.id]);

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

  async function loadBackupNamespaces(query: BackupTargetQuery): Promise<BackupTargetPage> {
    if (!selected) throw new Error('请先选择数据库连接');
    const params = new URLSearchParams({
      page: String(query.page),
      pageSize: String(query.pageSize)
    });
    if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
    if (query.refresh) params.set('refresh', 'true');
    return api<BackupTargetPage>(`/metadata/${selected.id}/backup-targets/namespaces?${params.toString()}`);
  }

  async function loadBackupTables(query: BackupTableTargetQuery): Promise<BackupTargetPage> {
    if (!selected) throw new Error('请先选择数据库连接');
    const params = new URLSearchParams({
      namespaceName: query.namespaceName,
      page: String(query.page),
      pageSize: String(query.pageSize)
    });
    if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
    if (query.refresh) params.set('refresh', 'true');
    return api<BackupTargetPage>(`/metadata/${selected.id}/backup-targets/tables?${params.toString()}`);
  }

  async function previewBackupSchedule(cron: string): Promise<BackupSchedulePreview> {
    return api<BackupSchedulePreview>('/backups/schedule/preview', {
      method: 'POST',
      body: JSON.stringify({ cron })
    });
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
    confirmDiscardObjectDesign(() => {
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
    }, '保存连接配置');
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

  function deleteConnection(connection: Connection) {
    const performDelete = () => void performDeleteConnection(connection);
    if (selected?.id !== connection.id) {
      performDelete();
      return;
    }
    confirmDiscardObjectDesign(() => {
      confirmDiscardTableChanges(performDelete, `删除连接“${connection.name}”`);
    }, `删除连接“${connection.name}”`);
  }

  async function performDeleteConnection(connection: Connection) {
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
    completionCatalogCacheRef.current.clear();
    completionStructureCacheRef.current.clear();
    setStructureLoadingKey(null);
    setActiveObjectDetail(null);
    updateObjectDesignDirty(false);
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
      confirmDiscardObjectDesign(() => {
        confirmDiscardTableChanges(() => {
          resetConnectionForm();
          applyConnectionSelection(connection);
        }, `切换到连接“${connection.name}”`);
      }, `切换到连接“${connection.name}”`);
    });
  }

  function editConnection(connection: Connection) {
    const openEditor = () => {
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
    };
    const continueEditing = () => confirmDiscardTableChanges(openEditor, `编辑连接“${connection.name}”`);
    confirmDiscardConnectionDraft(() => {
      if (selected?.id === connection.id) {
        continueEditing();
        return;
      }
      confirmDiscardObjectDesign(continueEditing, `编辑连接“${connection.name}”`);
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
    setTableRows(tableData.rows.map((row, index) => ({ id: `row-${tablePage}-${index}`, values: { ...row }, original: { ...row } })));
    setPreviewSql([]);
  }

  function clearTableWorkspace() {
    setActiveTable(null);
    setTableData(null);
    setTableRows([]);
    setTablePage(0);
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

  function confirmDiscardObjectDesign(action: () => void, nextAction = '离开当前对象') {
    if (!objectDesignDirtyRef.current) {
      action();
      return;
    }
    modalApi.confirm({
      title: '存在未保存的表结构设计',
      content: `${nextAction}将放弃尚未执行的字段或索引修改。`,
      okText: '放弃并继续',
      cancelText: '继续设计',
      okButtonProps: { danger: true },
      // The destination action (successful reload, mode change or connection
      // switch) resets dirty state. Keep it dirty if that action later fails.
      onOk: action
    });
  }

  async function loadMetadata(conn = selected, options: { schema?: string; keyword?: string; page?: number; append?: boolean; refresh?: boolean } = {}) {
    if (!conn) return;
    const requestId = ++metadataRequestSeqRef.current;
    setMetadataLoading(true);
    try {
      if (options.refresh) {
        structureCacheRef.current.clear();
        completionCatalogCacheRef.current.clear();
        completionStructureCacheRef.current.clear();
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
      const scopeText = data.selectedSchema ? `${data.namespaceKind === 'CATALOG' ? '数据库' : 'Schema'} ${data.selectedSchema}` : '当前数据库';
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

  function requestRefreshDatabaseObjects() {
    if (!selected) return;
    confirmDiscardObjectDesign(() => {
      confirmDiscardTableChanges(() => {
        void refreshDatabaseObjects();
      }, '刷新数据库对象');
    }, '刷新数据库对象');
  }

  async function refreshDatabaseObjects() {
    if (!selected) return;
    await loadMetadata(selected, { schema: metadataQuery.schema, page: 0, refresh: true });
    if (mode === 'object' && activeObjectDetail) {
      await loadObjectDetail(activeObjectDetail, { refresh: true });
    }
    if (mode === 'table' && activeTable) {
      await loadTable(activeTable, { page: tablePage });
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
        const data = await api<SqlResult>(path, { method: 'POST', body: JSON.stringify({ connectionId: selected.id, sql: target.sql, maxRows: layoutPreferences.sqlMaxRows }) });
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
        const data = await api<SqlScriptResult>('/sql/execute-script', { method: 'POST', body: JSON.stringify({ connectionId: selected.id, sql: target.sql, maxRows: layoutPreferences.sqlMaxRows }) });
        const failed = data.results.find((item) => item.status === 'FAILED');
        const successCount = data.results.filter((item) => item.status === 'SUCCESS').length;
        const firstResultSet = data.results.find((item) => item.result?.resultSet);
        const returnedRows = data.results.reduce((total, item) => total + (item.result?.resultSet ? item.result.rows.length : 0), 0);
        const truncated = data.results.some((item) => item.result?.truncated);
        const nextMessage = failed
          ? `第 ${failed.index} 条 SQL 执行失败，已成功 ${successCount} 条，用时 ${data.elapsedMs}ms`
          : `已执行 ${successCount} 条 SQL，返回 ${returnedRows} 行${truncated ? `（已达到 ${layoutPreferences.sqlMaxRows} 行上限，结果可能不完整）` : ''}，用时 ${data.elapsedMs}ms`;
        updateActiveSqlTab({
          results: data.results,
          activeResultKey: statementResultKey(failed || firstResultSet || data.results[0]),
          message: nextMessage,
          statusKind: failed ? 'error' : 'success'
        });
        if (failed) {
          selectStatementRange(target.baseOffset + failed.startOffset, target.baseOffset + failed.endOffset);
        }
        if (data.metadataChanged) {
          await loadMetadata(selected, { page: 0, refresh: true });
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
    const currentSql = editorRef.current?.getValue() ?? activeSqlTab.sql;
    if (!currentSql.trim()) {
      updateActiveSqlTab({ message: '请输入要格式化的 SQL', statusKind: 'info' });
      return;
    }
    setSqlLoading(true);
    try {
      const data = await api<{ sql: string }>('/sql/format', { method: 'POST', body: JSON.stringify({ sql: currentSql }) });
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
    const editor = editorRef.current;
    const selection = editor?.getSelection();
    const model = editor?.getModel();
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
    // SqlWorkspace keeps the current editor text in a local draft to avoid
    // re-rendering Monaco on every keystroke. Always read the live model here:
    // React state may still contain the last committed draft when a toolbar
    // action or Ctrl/Cmd+Enter is triggered.
    return { sql: model?.getValue() ?? activeSqlTab.sql, selected: false, baseOffset: 0 };
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

  function completionCatalogKey(connectionId: number, schemaName?: string) {
    return `${connectionId}:${(schemaName || '').toLowerCase()}`;
  }

  function completionObjectKey(connectionId: number, object: Pick<DbObject, 'schemaName' | 'name'>) {
    return `${connectionId}:${(object.schemaName || '').toLowerCase()}.${object.name.toLowerCase()}`;
  }

  function loadCompletionCatalog(connectionId: number, schemaName?: string) {
    const key = completionCatalogKey(connectionId, schemaName);
    return completionCatalogCacheRef.current.load(key, async () => {
      const params = new URLSearchParams();
      if (schemaName) params.set('schema', schemaName);
      const suffix = params.size > 0 ? `?${params.toString()}` : '';
      return api<CompletionCatalog>(`/metadata/${connectionId}/completion-catalog${suffix}`);
    });
  }

  function loadCompletionStructure(connectionId: number, object: DbObject) {
    const key = completionObjectKey(connectionId, object);
    return completionStructureCacheRef.current.load(key, async () => {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      const structure = await api<ObjectStructure>(`/metadata/${connectionId}/objects/structure?${params.toString()}`);
      return { ...object, ...structure };
    });
  }

  async function sqlCompletionItems(
    model: Monaco.editor.ITextModel,
    position: Monaco.Position,
    monaco: Parameters<OnMount>[1],
    token: Monaco.CancellationToken
  ) {
    const context = analyzeSqlCompletion(model.getValue(), model.getOffsetAt(position));
    if (context.insideCommentOrString || context.mode === 'none') return [];
    const start = model.getPositionAt(context.replacement.start);
    const end = model.getPositionAt(context.replacement.end);
    const range = {
      startLineNumber: start.lineNumber,
      startColumn: start.column,
      endLineNumber: end.lineNumber,
      endColumn: end.column
    };
    const keywords = sqlKeywordCompletionItems(monaco, range);
    const connectionId = selectedIdRef.current;
    if (!connectionId) return keywords;
    const schemaName = metadataRef.current?.selectedSchema || '';
    let catalog: CompletionCatalog;
    try {
      catalog = await loadCompletionCatalog(connectionId, schemaName);
    } catch {
      catalog = { selectedSchema: schemaName, objects: metadataRef.current?.objects || [] };
    }
    if (token.isCancellationRequested || selectedIdRef.current !== connectionId) return [];
    const prefix = context.replacement.prefix.toLowerCase();
    const matchingObjects = catalog.objects.filter((object) => !prefix || object.name.toLowerCase().startsWith(prefix));
    const tableItems = matchingObjects.map((object) => ({
      label: object.name,
      kind: monaco.languages.CompletionItemKind.Class,
      insertText: quoteSqlIdentifier(object.name, context.replacement.quoteStyle),
      detail: `${object.schemaName || catalog.selectedSchema || schemaName} · ${object.type.toUpperCase().includes('VIEW') ? '视图' : '表'}`,
      range,
      sortText: `1-${object.name.toLowerCase()}`
    }));
    if (context.mode === 'table') return tableItems;

    const referenced = context.mode === 'qualified-column'
      ? [resolveSqlTableReference(context, context.qualifierParts)].filter((table): table is NonNullable<typeof table> => Boolean(table))
      : context.tables;
    if (referenced.length === 0) return [...tableItems, ...keywords];

    const structures = await Promise.all(referenced.map(async (table) => {
      const object = catalog.objects.find((candidate) => matchesObjectName(candidate, table.qualifiedName)) || {
        schemaName: table.schemaName || schemaName,
        name: table.name,
        type: 'TABLE',
        columns: [],
        indexes: []
      };
      try {
        return { table, object: await loadCompletionStructure(connectionId, object) };
      } catch {
        return { table, object };
      }
    }));
    if (token.isCancellationRequested || selectedIdRef.current !== connectionId) return [];

    const qualify = context.mode === 'column' && context.qualifyColumns;
    const columnItems = structures.flatMap(({ table, object }) => object.columns
      .filter((column) => !prefix || column.name.toLowerCase().startsWith(prefix))
      .map((column) => {
        const columnName = quoteSqlIdentifier(column.name, context.replacement.quoteStyle);
        const insertText = qualify ? `${sqlTableQualifier(table)}.${columnName}` : columnName;
        return {
          label: qualify ? `${sqlTableQualifier(table)}.${column.name}` : column.name,
          kind: monaco.languages.CompletionItemKind.Field,
          insertText,
          detail: `${object.name} 字段 · ${column.type}`,
          range,
          sortText: `0-${column.name.toLowerCase()}`
        };
      }));
    return columnItems.length > 0 ? [...columnItems, ...keywords] : [...tableItems, ...keywords];
  }

  const handleEditorMount: OnMount = (editor, monaco) => {
    editorRef.current = editor;
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => executeRef.current());
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyF, () => formatRef.current());
    completionProviderRef.current?.dispose();
    const provider = monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters: ['.'],
      provideCompletionItems: async (
        model: Monaco.editor.ITextModel,
        position: Monaco.Position,
        _context: Monaco.languages.CompletionContext,
        token: Monaco.CancellationToken
      ) => {
        return { suggestions: await sqlCompletionItems(model, position, monaco, token) };
      }
    });
    completionProviderRef.current = provider;
    editor.onDidDispose(() => {
      provider.dispose();
      if (completionProviderRef.current === provider) completionProviderRef.current = null;
      if (editorRef.current === editor) editorRef.current = null;
    });
  };

  function matchesObjectName(object: DbObject, tableName: string) {
    const normalizedTable = tableName.toLowerCase();
    const objectName = object.name.toLowerCase();
    const qualifiedName = object.schemaName ? `${object.schemaName}.${object.name}`.toLowerCase() : objectName;
    return normalizedTable === objectName || normalizedTable === qualifiedName || normalizedTable.endsWith(`.${objectName}`);
  }

  function openTable(object: DbObject) {
    if (!selected || object.type.toUpperCase().includes('VIEW')) {
      return;
    }
    const targetName = `${object.schemaName ? `${object.schemaName}.` : ''}${object.name}`;
    confirmDiscardObjectDesign(() => {
      confirmDiscardTableChanges(() => {
        void applyOpenTable(object);
      }, `打开数据表“${targetName}”`);
    }, `打开数据表“${targetName}”`);
  }

  async function applyOpenTable(object: DbObject) {
    const next = { schemaName: object.schemaName, tableName: object.name };
    setActiveTable(next);
    setTablePage(0);
    setMode('table');
    await loadTable(next, { page: 0 });
  }

  function openObjectDetail(object: DbObject) {
    confirmDiscardObjectDesign(() => {
      confirmDiscardTableChanges(() => {
        void loadObjectDetail(object);
      }, `查看对象“${object.name}”`);
    }, `查看对象“${object.name}”`);
  }

  async function loadObjectDetail(object: DbObject, options: { refresh?: boolean } = {}) {
    if (!selected) return;
    const connectionId = selected.id;
    const requestId = ++objectDetailRequestSeqRef.current;
    setObjectDetailLoading(true);
    try {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      if (options.refresh) params.set('refresh', 'true');
      const detail = await api<ObjectDetail>(`/metadata/${connectionId}/objects/detail?${params.toString()}`);
      if (requestId !== objectDetailRequestSeqRef.current || selectedIdRef.current !== connectionId) return;
      setActiveObjectDetail(detail);
      updateObjectDesignDirty(false);
      setMode('object');
      setWorkspaceStatus({ kind: 'success', text: `已加载对象详情：${detail.name}` });
    } catch (e) {
      if (requestId === objectDetailRequestSeqRef.current) showError(localizeMessage((e as Error).message));
    } finally {
      if (requestId === objectDetailRequestSeqRef.current) setObjectDetailLoading(false);
    }
  }

  async function loadTable(table = activeTable, options: { page?: number; pageSize?: number } = {}) {
    if (!selected || !table) return;
    const connectionId = selected.id;
    const requestId = ++tableRequestSeqRef.current;
    const requestedPage = Math.max(options.page ?? tablePage, 0);
    const requestedPageSize = options.pageSize ?? layoutPreferences.tablePageSize;
    setTableLoading(true);
    try {
      const params = new URLSearchParams({
        connectionId: String(connectionId),
        tableName: table.tableName,
        page: String(requestedPage),
        pageSize: String(requestedPageSize)
      });
      if (table.schemaName) params.set('schemaName', table.schemaName);
      const data = await api<TableData>(`/data/table?${params.toString()}`);
      if (requestId !== tableRequestSeqRef.current || selectedIdRef.current !== connectionId) return;
      setTableData(data);
      const resolvedPage = data.page ?? requestedPage;
      setTablePage(resolvedPage);
      setTableRows(data.rows.map((row, index) => ({ id: `row-${resolvedPage}-${index}`, values: { ...row }, original: { ...row } })));
      setPreviewSql([]);
      setWorkspaceStatus({ kind: 'success', text: `已从 ${table.tableName} 加载第 ${resolvedPage + 1} 页，共 ${data.rows.length} 行${data.hasMore ? '，还有下一页' : ''}` });
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
      await loadTable(activeTable, { page: tablePage });
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

  function openBackupTaskEditor(target = currentBackupTable) {
    if (!selected) {
      showInfo('请先选择一个数据库连接');
      return;
    }
    if (!target) {
      showInfo('当前工作区没有可备份的数据表');
      return;
    }
    const requestId = ++backupEditorRequestSeqRef.current;
    setBackupEditorRequest({ requestId, target });
    setActiveDrawer('backups');
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
        schemaName: form.scope === 'SCHEMA' || form.scope === 'TABLES' ? form.schemaName : undefined,
        tableNames: form.scope === 'TABLES' ? form.tableNames || [] : [],
        tableName: form.scope === 'TABLES' ? form.tableNames?.[0] : undefined,
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
              onClick={requestRefreshDatabaseObjects}
            >
              刷新
            </Button>
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
            {metadata?.selectedSchema && <Tag bordered={false}>{namespaceLabel} · {metadata.selectedSchema}</Tag>}
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
          placeholder={`选择${namespaceLabel}`}
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
      theme={antThemeConfig}
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
                maxRows={layoutPreferences.sqlMaxRows}
                onMaxRowsChange={layoutPreferences.setSqlMaxRows}
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
                page={tablePage}
                pageSize={tableData?.pageSize ?? layoutPreferences.tablePageSize}
                hasMore={tableData?.hasMore ?? false}
                status={tableStatus}
                loading={tableLoading}
                readonlyConnection={selected?.readonly}
                onBackToSql={() => confirmDiscardTableChanges(() => setMode('sql'), '返回 SQL 查询工作台')}
                onBackupTable={() => openBackupTaskEditor()}
                onReload={() => confirmDiscardTableChanges(() => void loadTable(), '重新加载当前表')}
                onPageChange={(page) => confirmDiscardTableChanges(() => void loadTable(activeTable, { page }), `切换到第 ${page + 1} 页`)}
                onPageSizeChange={(pageSize) => confirmDiscardTableChanges(() => {
                  layoutPreferences.setTablePageSize(pageSize);
                  void loadTable(activeTable, { page: 0, pageSize });
                }, `将每页行数调整为 ${pageSize}`)}
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
                onBackToSql={() => confirmDiscardObjectDesign(() => setMode('sql'), '返回 SQL 查询工作台')}
                onOpenTable={openTable}
                onReloadDetail={requestRefreshDatabaseObjects}
                onBackupTable={() => openBackupTaskEditor()}
                onDesignDirtyChange={updateObjectDesignDirty}
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
          activeTable={currentBackupTable}
          loading={backupLoading}
          namespaceKind={metadata?.namespaceKind}
          editorRequest={backupEditorRequest}
          onLoadNamespaces={loadBackupNamespaces}
          onLoadTables={loadBackupTables}
          onPreviewSchedule={previewBackupSchedule}
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
