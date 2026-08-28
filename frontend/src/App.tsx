import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type * as Monaco from 'monaco-editor';
import { Button, ConfigProvider, Drawer, Input, Modal, Space, Spin, Typography, message as antdMessage, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { PlusOutlined } from '@ant-design/icons';
import { ApiError, api, apiErrorCode, downloadBlob, downloadFromUrl } from './api';
import { currentSqlPage } from './sqlResultPaging';
import { API, DB_TYPE_OPTIONS } from './constants';
import type { ObjectRelation, ObjectRelations, SqlTransactionScriptResult, ActiveTable, BackupEditorRequest, BackupHistory, BackupHistoryPage, BackupRunResponse, BackupSchedulePreview, BackupTableTargetQuery, BackupTargetPage, BackupTargetQuery, BackupTask, BackupTaskForm, BackupTaskPage, CompletionCatalog, Connection, DbObject, ExportFormat, ImportResult, Metadata, ObjectDetail, ObjectStructure, RefreshConnectionsOptions, SqlFileCandidate, RowChange, SqlHistory, SqlPageNavigation, SqlResult, SqlScriptResult, SqlStatementResult, SqlTab, TableData, TableRow, WorkspaceStatus } from './types';
import { buildChanges, createSqlTab, localizeError, localizeMessage, sleep, sqlKeywordCompletionItems, timestamp } from './utils';
import { AsyncResourceCache } from './asyncResourceCache';
import { withLoadedObjectStructure } from './objectTreeModel';
import type { ExplorerObjectKind } from './schemaObjectModel';
import { analyzeSqlCompletion, findSqlObjectReferenceAtOffset, isSqlCompletionListIncomplete, quoteSqlIdentifier, resolveSqlTableReference, shouldTriggerSqlConditionColumnCompletion, sqlTableQualifier, type SqlTableReference } from './sqlCompletion';
import { readSelectedConnectionId, resolveSelectedConnection, writeSelectedConnectionId } from './selectedConnectionStorage';
import { getSqlFormatTarget } from './sqlFormatTarget';
import type { SqlEditorOnMount } from './sqlEditorTypes';
import { inferSqlTargetParts, parseQualifiedTableName } from './queryResultExport';
import { resolveSqlExecutionSchema } from './sqlExecutionContext';
import { readSqlSession, writeSqlSession } from './sqlSessionStorage';
import { readFavoriteConnectionIds, writeFavoriteConnectionIds } from './workspacePreferences';
import { enforceResultBudget } from './resultRetention';
import {
  hasMoreSqlHistory,
  INITIAL_SQL_HISTORY_QUERY,
  isSqlHistoryAtLimit,
  nextSqlHistoryLimit
} from './sqlHistoryQuery';
import { useBackgroundTasks } from './hooks/useBackgroundTasks';
import { useSqlHistory } from './hooks/useSqlHistory';
import { backgroundImportPrompt, importRoute, oversizedRowsRoute, type ImportRoute } from './dataImport';
import { matchesProductionConnectionName, normalizeProductionConfirmation, productionConfirmationHeaders } from './productionConfirmation';
import { createUuid } from './createUuid';
import type { ObjectSearchHit } from './objectSearch';
import { appendSnippetToSql, snippetDraftFromSql, type SqlSnippet, type SqlSnippetDraft } from './sqlSnippets';
import { applyCommittedChanges, type ResultEditCommit } from './resultEditing';
import {
  canJumpToRelation,
  foreignKeyColumns,
  relationJumpSql,
  relationTarget,
  type RelationTarget
} from './relationNavigation';
import {
  IDLE_SQL_TRANSACTION,
  isTransactionGone,
  transactionExecutePath,
  transactionFinishPrompt,
  transactionGoneNotice,
  transactionStateAfterError,
  restoredTransactionNotice,
  transactionLeaveWarning,
  type SqlTransaction,
  type SqlTransactionState
} from './sqlTransaction';
import { prefetchWhenIdle } from './idlePrefetch';
import { resolveAppShortcut, type AppShortcut } from './keyboardShortcuts';
import { IDLE_TABLE_ROW_COUNT, rowCountErrorMessage, rowCountFailure, type TableRowCountState } from './tableRowCount';
import { AppHeader } from './components/AppHeader';
import { PaneResizer } from './components/PaneResizer';
import { ResourceExplorer } from './components/ResourceExplorer';
import { TypedConfirmationFields } from './components/TypedConfirmationFields';
import type { TableLifecycleAction } from './components/TableLifecyclePanel';
import { useLayoutPreferences } from './hooks/useLayoutPreferences';
import { useStableEvent } from './hooks/useStableEvent';
import {
  buildConnectionSaveRequest,
  buildConnectionTestRequest,
  CLOSED_CONNECTION_EDITOR,
  createBlankConnectionEditor,
  createDuplicateConnectionEditor,
  createEditConnectionEditor,
  isConnectionEditorDirty,
  updateConnectionEditorForm
} from './connectionEditor';

const { Text } = Typography;
const OBJECT_PAGE_SIZE = 200;
const MAX_TABLE_CHANGES = 1_000;
const MAX_STRUCTURE_CACHE_ENTRIES = 500;
const MAX_OBJECT_DETAIL_CACHE_ENTRIES = 200;
const METADATA_CACHE_TTL_MS = 10 * 60 * 1000;
const METADATA_SEARCH_DEBOUNCE_MS = 500;
const OBJECT_DETAIL_PREFETCH_DELAY_MS = 250;
const MAX_SQL_TABS = 20;
type ProductionConfirmationRequest = { action: string; expected: string };
const BackupPanel = lazy(() => import('./components/BackupPanel').then((module) => ({ default: module.BackupPanel })));
const ConnectionFormPanel = lazy(() => import('./components/ConnectionFormPanel').then((module) => ({ default: module.ConnectionFormPanel })));
const SchemaDiffPanel = lazy(() => import('./components/SchemaDiffPanel').then((module) => ({ default: module.SchemaDiffPanel })));
const ConnectionList = lazy(() => import('./components/ConnectionList').then((module) => ({ default: module.ConnectionList })));
const McpSettingsPanel = lazy(() => import('./components/McpSettingsPanel').then((module) => ({ default: module.McpSettingsPanel })));
const SessionDrawer = lazy(() => import('./components/SessionDrawer').then((module) => ({ default: module.SessionDrawer })));
const AuditLogDrawer = lazy(() => import('./components/AuditLogDrawer').then((module) => ({ default: module.AuditLogDrawer })));
const ObjectSearchPalette = lazy(() => import('./components/ObjectSearchPalette').then((module) => ({ default: module.ObjectSearchPalette })));
const SqlSnippetDrawer = lazy(() => import('./components/SqlSnippetDrawer').then((module) => ({ default: module.SqlSnippetDrawer })));
const loadObjectDetailWorkspace = () => import('./components/ObjectDetailWorkspace').then((module) => ({ default: module.ObjectDetailWorkspace }));
const ObjectDetailWorkspace = lazy(loadObjectDetailWorkspace);
const SqlFileExecutionDrawer = lazy(() => import('./components/SqlFileExecutionDrawer').then((module) => ({ default: module.SqlFileExecutionDrawer })));
const SqlHistoryDrawer = lazy(() => import('./components/SqlHistoryDrawer').then((module) => ({ default: module.SqlHistoryDrawer })));
const loadSqlWorkspace = () => import('./components/SqlWorkspace').then((module) => ({ default: module.SqlWorkspace }));
const SqlWorkspace = lazy(loadSqlWorkspace);
// Monaco 是全站最大的块。SqlEditorSurface 只在用户点进编辑器时才 import 它，所以不预取的话
// 那次点击要等 600+ KiB 下载完；这里在首屏空闲时先取回来。
const TableWorkspace = lazy(() => import('./components/TableWorkspace').then((module) => ({ default: module.TableWorkspace })));
const TableLifecyclePanel = lazy(() => import('./components/TableLifecyclePanel').then((module) => ({ default: module.TableLifecyclePanel })));

export default function App() {
  const [connections, setConnections] = useState<Connection[]>([]);
  const [favoriteConnectionIds, setFavoriteConnectionIds] = useState<number[]>(() => readFavoriteConnectionIds());
  const [selected, setSelected] = useState<Connection | null>(null);
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [metadataQuery, setMetadataQuery] = useState({ schema: '', keyword: '' });
  const [metadataAppliedKeyword, setMetadataAppliedKeyword] = useState('');
  const [structureLoadingKey, setStructureLoadingKey] = useState<string | null>(null);
  const [sqlTabs, setSqlTabs] = useState<SqlTab[]>([{ id: 'query-1', title: '查询 1', sql: 'select 1 as val', dirty: false, results: [], message: '' }]);
  const [activeSqlTabId, setActiveSqlTabId] = useState('query-1');
  const [sqlSessionRevision, setSqlSessionRevision] = useState(0);
  const [workspaceStatus, setWorkspaceStatus] = useState<WorkspaceStatus>({ kind: 'idle', text: '就绪' });
  const [productionConfirmationRequest, setProductionConfirmationRequest] = useState<ProductionConfirmationRequest | null>(null);
  const [productionConfirmationInput, setProductionConfirmationInput] = useState('');
  const [connectionActionLoading, setConnectionActionLoading] = useState(false);
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [metadataBlockingLoading, setMetadataBlockingLoading] = useState(false);
  const [objectDetailLoading, setObjectDetailLoading] = useState(false);
  const [objectDesignDirty, setObjectDesignDirty] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);
  const [backupLoading, setBackupLoading] = useState(false);
  const [sqlLoading, setSqlLoading] = useState(false);
  const [sqlPagingResultKey, setSqlPagingResultKey] = useState<string | null>(null);
  const [sqlCancellable, setSqlCancellable] = useState(false);
  const [sqlCancelling, setSqlCancelling] = useState(false);
  const [connectionsLoading, setConnectionsLoading] = useState(false);
  const [connectionsError, setConnectionsError] = useState('');
  const [connectionsReady, setConnectionsReady] = useState(false);
  const [testingConnectionId, setTestingConnectionId] = useState<number | null>(null);
  const [backups, setBackups] = useState<BackupTask[]>([]);
  const [backupTaskPage, setBackupTaskPage] = useState(0);
  const [backupTaskHasMore, setBackupTaskHasMore] = useState(false);
  const [connectionEditor, setConnectionEditor] = useState(CLOSED_CONNECTION_EDITOR);
  const [mode, setMode] = useState<'sql' | 'table' | 'object'>('sql');
  const [activeTable, setActiveTable] = useState<ActiveTable | null>(null);
  const [activeObjectTarget, setActiveObjectTarget] = useState<DbObject | null>(null);
  const [activeObjectDetail, setActiveObjectDetail] = useState<ObjectDetail | null>(null);
  const [tableData, setTableData] = useState<TableData | null>(null);
  const [tableRows, setTableRows] = useState<TableRow[]>([]);
  const [tablePage, setTablePage] = useState(0);
  const [tableCursorStack, setTableCursorStack] = useState<Array<string | null>>([null]);
  const [previewSql, setPreviewSql] = useState<string[]>([]);
  const [sqlFileTasksOpen, setSqlFileTasksOpen] = useState(false);
  const [sqlFileFeatureLoaded, setSqlFileFeatureLoaded] = useState(false);
  const [sqlFileCandidate, setSqlFileCandidate] = useState<SqlFileCandidate>();
  const [activeDrawer, setActiveDrawer] = useState<'connections' | 'backups' | 'mcp' | 'audit' | 'sessions' | 'schema-diff' | null>(null);
  const [backupEditorRequest, setBackupEditorRequest] = useState<BackupEditorRequest>();
  const [compactLayout, setCompactLayout] = useState(false);
  const [mobileExplorerOpen, setMobileExplorerOpen] = useState(false);
  const [tableCreateOpen, setTableCreateOpen] = useState(false);
  const [tableLifecycleAction, setTableLifecycleAction] = useState<TableLifecycleAction>();
  const [structureCacheRevision, setStructureCacheRevision] = useState(0);
  const [tableRowCount, setTableRowCount] = useState<TableRowCountState>(IDLE_TABLE_ROW_COUNT);
  const [objectSearchOpen, setObjectSearchOpen] = useState(false);
  const [snippetsOpen, setSnippetsOpen] = useState(false);
  const [transactionState, setTransactionState] = useState<SqlTransactionState>(IDLE_SQL_TRANSACTION);
  const [activeTableRelations, setActiveTableRelations] = useState<ObjectRelations | null>(null);
  const [snippetDraft, setSnippetDraft] = useState<{ draft: SqlSnippetDraft; token: number }>();
  const [explorerRequestedView, setExplorerRequestedView] = useState<{ kind: ExplorerObjectKind; keyword: string; token: number }>();
  const selectedIdRef = useRef<number | null>(null);
  const metadataRef = useRef<Metadata | null>(null);
  const completionCatalogCacheRef = useRef(new AsyncResourceCache<string, CompletionCatalog>({ ttlMs: METADATA_CACHE_TTL_MS, maxEntries: 300 }));
  const objectStructureCacheRef = useRef(new AsyncResourceCache<string, DbObject>({ ttlMs: METADATA_CACHE_TTL_MS, maxEntries: MAX_STRUCTURE_CACHE_ENTRIES }));
  const objectDetailCacheRef = useRef(new AsyncResourceCache<string, ObjectDetail>({ ttlMs: METADATA_CACHE_TTL_MS, maxEntries: MAX_OBJECT_DETAIL_CACHE_ENTRIES }));
  const metadataRequestSeqRef = useRef(0);
  const structureRequestSeqRef = useRef(0);
  const objectDetailRequestSeqRef = useRef(0);
  const tableRequestSeqRef = useRef(0);
  const tableRowCountSeqRef = useRef(0);
  const backupRequestSeqRef = useRef(0);
  const objectDesignDirtyRef = useRef(false);
  const editorRef = useRef<Monaco.editor.IStandaloneCodeEditor | null>(null);
  const completionProviderRef = useRef<Monaco.IDisposable | null>(null);
  const objectDefinitionNavigationRef = useRef<(reference: SqlTableReference) => void>(() => undefined);
  const executeRef = useRef<() => void>(() => undefined);
  const shortcutHandlerRef = useRef<(shortcut: AppShortcut, event: KeyboardEvent) => void>(() => undefined);
  const formatRef = useRef<() => void>(() => undefined);
  const sqlTabSeqRef = useRef(1);
  const sqlFileRequestSeqRef = useRef(0);
  const backupEditorRequestSeqRef = useRef(0);
  const metadataSearchTimerRef = useRef<number | null>(null);
  const metadataAbortRef = useRef<AbortController | null>(null);
  const tableAbortRef = useRef<AbortController | null>(null);
  const objectDetailPrefetchTimerRef = useRef<number | null>(null);
  const objectDetailPrefetchCandidateRef = useRef('');
  const sqlExecutionIdRef = useRef<string | null>(null);
  const exportAbortRef = useRef<AbortController | null>(null);
  const sqlBusyRef = useRef(false);
  const sqlTabsRef = useRef(sqlTabs);
  const activeSqlTabIdRef = useRef(activeSqlTabId);
  const sqlSessionConnectionIdRef = useRef<number | null>(null);
  const productionConfirmationResolverRef = useRef<((value: string | undefined) => void) | null>(null);
  const [toastApi, toastContextHolder] = antdMessage.useMessage();
  const [modalApi, modalContextHolder] = Modal.useModal();
  const layoutPreferences = useLayoutPreferences();
  const updateObjectDesignDirty = useCallback((dirty: boolean) => {
    objectDesignDirtyRef.current = dirty;
    setObjectDesignDirty(dirty);
  }, []);

  const objects = useMemo(() => {
    if (!metadata || !selected) return metadata?.objects || [];
    return metadata.objects.map((object) => withLoadedObjectStructure(
      object,
      objectStructureCacheRef.current.peek(objectCacheKey(selected.id, object))
    ));
  }, [metadata, selected, structureCacheRevision]);
  const namespaceLabel = metadata?.namespaceKind === 'CATALOG' ? '数据库' : 'Schema';
  const activeSqlSchema = resolveSqlExecutionSchema(metadataQuery.schema, metadata);
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
  sqlTabsRef.current = sqlTabs;
  activeSqlTabIdRef.current = activeSqlTabId;
  const connectionFormDirty = isConnectionEditorDirty(connectionEditor);
  const antThemeConfig = useMemo(() => ({
    algorithm: layoutPreferences.themeMode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: { colorPrimary: '#2f74e8', borderRadius: 7, controlHeight: 34, fontSize: 13 }
  }), [layoutPreferences.themeMode]);

  useEffect(() => {
    refreshConnections({ retry: true });
  }, []);

  // 首屏空闲时只预取工作台外壳；Monaco 等真正进入 SQL 工作台后再加载。
  useEffect(() => prefetchWhenIdle(loadSqlWorkspace, window), []);

  useEffect(() => {
    if (selected) void loadObjectDetailWorkspace().catch(() => undefined);
  }, [selected?.id]);

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
    const nextConnectionId = selected?.id ?? null;
    activateSqlSession(nextConnectionId, false);
  }, [selected?.id]);

  /**
   * 恢复服务端还开着的手动事务。
   *
   * 事务活在后端，刷新页面不会结束它 —— 它继续占着连接池里的一条连接，并在数据库上持有锁。
   * 不恢复的话用户会卡死：界面上没有可提交的事务，再点「开启事务」又被 409 挡住，只能干等
   * 十分钟的空闲回收。
   */
  useEffect(() => {
    const connectionId = selected?.id;
    if (!connectionId) return;
    let cancelled = false;
    void api<{ transaction?: SqlTransaction }>(`/sql/transactions/active?connectionId=${connectionId}`)
      .then((response) => {
        if (cancelled || !response?.transaction) return;
        setTransactionState({ transaction: response.transaction, pending: false });
        showInfo(restoredTransactionNotice(response.transaction));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [selected?.id]);

  useEffect(() => {
    const connectionId = selected?.id ?? null;
    const timer = window.setTimeout(() => {
      writeSqlSession(connectionId, sqlTabs, activeSqlTabId);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [activeSqlTabId, selected?.id, sqlTabs]);

  useEffect(() => {
    const persist = () => persistCurrentSqlSession(sqlSessionConnectionIdRef.current);
    window.addEventListener('pagehide', persist);
    return () => window.removeEventListener('pagehide', persist);
  }, []);

  // The listener is registered once and dispatches through a ref, so every
  // handler below sees current state without re-binding on each render.
  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      const shortcut = resolveAppShortcut(event);
      if (shortcut) shortcutHandlerRef.current(shortcut, event);
    };
    window.addEventListener('keydown', listener);
    return () => window.removeEventListener('keydown', listener);
  }, []);

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
    clearMetadataSearchTimer();
    setTableCreateOpen(false);
    setTableLifecycleAction(undefined);
  }, [selected?.id]);

  useEffect(() => () => clearMetadataSearchTimer(), []);

  useEffect(() => () => {
    metadataAbortRef.current?.abort();
    tableAbortRef.current?.abort();
    exportAbortRef.current?.abort();
    clearObjectDetailPrefetchTimer();
  }, []);

  useEffect(() => {
    if (activeDrawer !== 'backups') return;
    refreshBackups(selected, 0).catch(() => showError('备份任务加载失败，可稍后刷新。'));
  }, [activeDrawer, selected?.id]);

  // 后台任务的订阅与降级轮询都在 useBackgroundTasks 里；这里只处理「拿到快照之后界面要做
  // 什么」：更新备份抽屉里的实时行，以及播报结束提示。
  const sqlHistoryState = useSqlHistory({
    connectionId: selected?.id,
    onWarning: (message) => toastApi.warning(message),
    onError: (message) => toastApi.error(`历史记录加载失败：${localizeMessage(message)}`)
  });

  const { summary: backgroundTasks, reset: resetBackgroundTasks } = useBackgroundTasks({
    connectionId: selected?.id,
    watchingTasks: activeDrawer === 'backups',
    onCompletion: (message) => toastApi.info(message),
    onOperations: (active) => {
      if (activeDrawer !== 'backups') return;
      const connection = selected;
      if (!connection) return;
      if (active.backups.length === 0) {
        void refreshBackups(connection).catch(() => undefined);
        return;
      }
      setBackups((current) => current.map((task) => {
        const execution = active.backups.find((item) => item.taskId === task.id);
        return execution ? { ...task, lastStatus: execution.status, lastMessage: execution.message } : task;
      }));
    }
  });

  useEffect(() => {
    if (selected) {
      loadMetadata(selected, { page: 0 }).catch(() => undefined);
    }
  }, [selected?.id]);

  useEffect(() => {
    metadataRef.current = metadata;
  }, [metadata]);

  /**
   * Records the latest counts and announces jobs that just left the active set.
   * A job leaving it only means it stopped, so the toast points at the drawer
   * rather than claiming success.
   */
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

  function requestProductionConfirmation(action: string): Promise<string | undefined> {
    if (!selected || selected.environment !== 'prod') return Promise.resolve(undefined);
    const request = { action, expected: selected.name };
    return new Promise((resolve) => {
      productionConfirmationResolverRef.current?.(undefined);
      productionConfirmationResolverRef.current = resolve;
      setProductionConfirmationInput('');
      setProductionConfirmationRequest(request);
    });
  }

  function cancelProductionConfirmation() {
    const resolve = productionConfirmationResolverRef.current;
    productionConfirmationResolverRef.current = null;
    setProductionConfirmationRequest(null);
    setProductionConfirmationInput('');
    resolve?.(undefined);
  }

  function confirmProductionConfirmation() {
    if (!productionConfirmationRequest) return;
    const input = normalizeProductionConfirmation(productionConfirmationInput);
    if (!input) {
      toastApi.error(`请输入生产连接名：${productionConfirmationRequest.expected}`);
      return;
    }
    if (!matchesProductionConnectionName(input, productionConfirmationRequest.expected)) {
      toastApi.error(`连接名不匹配，请输入：${productionConfirmationRequest.expected}`);
      return;
    }
    const resolve = productionConfirmationResolverRef.current;
    productionConfirmationResolverRef.current = null;
    setProductionConfirmationRequest(null);
    setProductionConfirmationInput('');
    resolve?.(input);
  }

  function requestUnscopedMutationConfirmation(statements: Array<{ index: number; sql: string }>): Promise<boolean> {
    return new Promise((resolve) => {
      modalApi.confirm({
        title: '确认执行无 WHERE 条件的更新或删除',
        content: (
          <div className="unscoped-sql-confirmation">
            <Typography.Paragraph type="danger">以下语句没有顶层 WHERE 条件，可能更新或删除整张表中的所有数据：</Typography.Paragraph>
            {statements.map((statement) => <pre key={statement.index}>第 {statement.index} 条：{statement.sql}</pre>)}
          </div>
        ),
        okText: '仍然执行',
        cancelText: '取消',
        okButtonProps: { danger: true },
        onOk: () => resolve(true),
        onCancel: () => resolve(false)
      });
    });
  }

  function requestSqlExportTarget(): Promise<string[] | undefined> {
    let input = '';
    return new Promise((resolve) => {
      modalApi.confirm({
        title: '指定 SQL 插入目标表',
        content: (
          <div>
            <Typography.Paragraph type="secondary">当前查询包含多个来源或复杂子查询，无法安全判断 INSERT 的目标表。</Typography.Paragraph>
            <Input autoFocus placeholder="schema.table" onChange={(event) => { input = event.target.value; }} />
          </div>
        ),
        okText: '继续导出',
        cancelText: '取消',
        onOk: () => {
          const parts = parseQualifiedTableName(input);
          if (!parts) {
            toastApi.error('请输入有效的表名，例如 public.users 或 "My Schema"."User"');
            return Promise.reject(new Error('目标表格式无效'));
          }
          resolve(parts);
        },
        onCancel: () => resolve(undefined)
      });
    });
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
        setSelected((current: Connection | null) => {
          const target = resolveSelectedConnection(rows, [
            options.preferredConnectionId,
            current?.id,
            readSelectedConnectionId()
          ]);
          writeSelectedConnectionId(target?.id);
          return target;
        });
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

  async function refreshBackups(conn = selected, pageNumber = backupTaskPage) {
    if (!conn) {
      setBackups([]);
      setBackupTaskPage(0);
      setBackupTaskHasMore(false);
      return;
    }
    const requestId = ++backupRequestSeqRef.current;
    try {
      const page = await api<BackupTaskPage>(`/backups/page?connectionId=${conn.id}&page=${pageNumber}&pageSize=10`);
      if (requestId === backupRequestSeqRef.current && selectedIdRef.current === conn.id) {
        setBackups(page.items);
        setBackupTaskPage(page.page);
        setBackupTaskHasMore(page.hasMore);
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

  async function previewBackupSchedule(cron: string, zoneId?: string): Promise<BackupSchedulePreview> {
    return api<BackupSchedulePreview>('/backups/schedule/preview', {
      method: 'POST',
      body: JSON.stringify({ cron, zoneId })
    });
  }

  async function saveConnection() {
    const editor = connectionEditor;
    if (editor.mode === 'closed') return;
    const request = buildConnectionSaveRequest(editor);
    setConnectionActionLoading(true);
    try {
      const saved = await api<Connection>(request.path, {
        method: request.method,
        body: JSON.stringify(request.body)
      });
      const updatesActiveConnection = editor.mode === 'create' || selected?.id === saved.id;
      if (updatesActiveConnection) applyConnectionSelection(saved);
      setConnectionEditor(CLOSED_CONNECTION_EDITOR);
      showSuccess(editor.mode === 'edit' ? `已更新连接：${saved.name}` : `已创建连接：${saved.name}`);
      await refreshConnections({ preferredConnectionId: updatesActiveConnection ? saved.id : undefined });
    } catch (e) {
      showError(localizeMessage((e as Error).message));
    } finally {
      setConnectionActionLoading(false);
    }
  }

  function requestSaveConnection() {
    if (connectionEditor.mode === 'closed') return;
    const changesActiveConnection = connectionEditor.mode === 'create' || selected?.id === connectionEditor.connectionId;
    if (!changesActiveConnection) {
      void saveConnection();
      return;
    }
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

  async function testConnection() {
    const editor = connectionEditor;
    if (editor.mode === 'closed') return;
    const target = editor.form;
    setConnectionActionLoading(true);
    try {
      const request = buildConnectionTestRequest(editor);
      await api<{ ok: boolean; message: string }>(request.path, {
        method: 'POST',
        body: JSON.stringify(request.body)
      });
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
      setFavoriteConnectionIds((current) => {
        const next = current.filter((id) => id !== connection.id);
        writeFavoriteConnectionIds(next);
        return next;
      });
      const remaining = connections.filter((row) => row.id !== connection.id);
      setConnections(remaining);
      if (selected?.id === connection.id) {
        const nextConnection = remaining[0] || null;
        if (nextConnection) {
          applyConnectionSelection(nextConnection);
        } else {
          invalidateConnectionRequests();
          activateSqlSession(null, true);
          setSelected(null);
          writeSelectedConnectionId(null);
          setMetadata(null);
          setMetadataQuery({ schema: '', keyword: '' });
          setMetadataAppliedKeyword('');
          clearObjectStructureCache();
          objectDetailCacheRef.current.clear();
          setStructureLoadingKey(null);
          setActiveObjectTarget(null);
          setActiveObjectDetail(null);
          clearTableWorkspace();
          setMode('sql');
        }
      }
      await refreshConnections();
    } catch (e) {
      showError(localizeError(e));
    } finally {
      setConnectionActionLoading(false);
    }
  }

  function applyConnectionSelection(connection: Connection) {
    // Counts belong to one connection; carrying them over would announce the
    // previous connection's jobs as finished on the very first snapshot here.
    resetBackgroundTasks();
    clearMetadataSearchTimer();
    invalidateConnectionRequests();
    activateSqlSession(connection.id, true);
    setSelected(connection);
    writeSelectedConnectionId(connection.id);
    setMetadata(null);
    setMetadataQuery({ schema: '', keyword: '' });
    setMetadataAppliedKeyword('');
    clearObjectStructureCache();
    completionCatalogCacheRef.current.clear();
    objectDetailCacheRef.current.clear();
    // 历史属于某一条连接，切换后必须重新取，不能把上一条连接的记录留在抽屉里。
    sqlHistoryState.reset();
    // 事务属于某一条连接；切换连接前 selectConnection 已经拦过未结束的事务。
    setTransactionState(IDLE_SQL_TRANSACTION);
    setStructureLoadingKey(null);
    setActiveObjectTarget(null);
    setActiveObjectDetail(null);
    updateObjectDesignDirty(false);
    clearTableWorkspace();
    setMode('sql');
    setMobileExplorerOpen(false);
  }

  function invalidateConnectionRequests() {
    metadataAbortRef.current?.abort();
    tableAbortRef.current?.abort();
    clearObjectDetailPrefetchTimer();
    metadataRequestSeqRef.current += 1;
    structureRequestSeqRef.current += 1;
    objectDetailRequestSeqRef.current += 1;
    tableRequestSeqRef.current += 1;
    backupRequestSeqRef.current += 1;
    metadataAbortRef.current = null;
    tableAbortRef.current = null;
    setMetadataLoading(false);
    setMetadataBlockingLoading(false);
    setTableLoading(false);
    setObjectDetailLoading(false);
  }

  function selectConnection(connection: Connection, onSelected?: () => void) {
    if (selected?.id === connection.id) return;
    const transactionWarning = transactionLeaveWarning(transactionState);
    if (transactionWarning) {
      showInfo(transactionWarning);
      return;
    }
    if (sqlLoading || tableLoading || objectDetailLoading) {
      showInfo(sqlCancellable
        ? '请等待当前操作完成后再切换连接，或先点击工具栏的「取消」终止本次执行'
        : '请等待当前操作完成后再切换连接');
      return;
    }
    confirmDiscardObjectDesign(() => {
      confirmDiscardTableChanges(() => {
        applyConnectionSelection(connection);
        onSelected?.();
      }, `切换到连接“${connection.name}”`);
    }, `切换到连接“${connection.name}”`);
  }

  function editConnection(connection: Connection) {
    setConnectionEditor(createEditConnectionEditor(connection));
    setActiveDrawer('connections');
  }

  function duplicateConnection(connection: Connection) {
    setConnectionEditor(createDuplicateConnectionEditor(connection));
    setActiveDrawer('connections');
  }

  function openNewConnectionEditor() {
    setConnectionEditor(createBlankConnectionEditor());
    setActiveDrawer('connections');
  }

  function closeConnectionEditor() {
    if (!connectionFormDirty) {
      setConnectionEditor(CLOSED_CONNECTION_EDITOR);
      return;
    }
    modalApi.confirm({
      title: '放弃未保存的连接配置？',
      content: '当前连接表单已修改，关闭后这些内容不会保留。',
      okText: '放弃修改',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: () => setConnectionEditor(CLOSED_CONNECTION_EDITOR)
    });
  }

  function closeConnectionDrawer() {
    setActiveDrawer(null);
  }

  function discardTableChanges() {
    if (!tableData) return;
    setTableRows(tableData.rows.map((row, index) => ({ id: `row-${tablePage}-${index}`, values: { ...row }, original: { ...row }, keyToken: tableData.rowKeyTokens?.[index] })));
    setPreviewSql([]);
  }

  function clearTableWorkspace() {
    setActiveTable(null);
    setTableData(null);
    setTableRows([]);
    setTablePage(0);
    setTableCursorStack([null]);
    setPreviewSql([]);
    resetTableRowCount();
  }

  /**
   * A count belongs to one table at one moment. Paging keeps it (the data has
   * not changed), while opening another table or committing changes drops it.
   */
  function resetTableRowCount() {
    tableRowCountSeqRef.current += 1;
    setTableRowCount(IDLE_TABLE_ROW_COUNT);
  }

  async function countTableRows() {
    if (!selected || !activeTable) return;
    const connectionId = selected.id;
    const requestId = ++tableRowCountSeqRef.current;
    setTableRowCount({ status: 'loading' });
    try {
      const params = new URLSearchParams({ objectName: activeTable.tableName });
      if (activeTable.schemaName) params.set('schemaName', activeTable.schemaName);
      const data = await api<{ value: number | null; exact: boolean; elapsedMs: number }>(
        `/metadata/${connectionId}/objects/row-count?${params.toString()}`
      );
      if (requestId !== tableRowCountSeqRef.current || selectedIdRef.current !== connectionId) return;
      if (data.value == null) {
        setTableRowCount({ status: 'failed' });
        return;
      }
      setTableRowCount({ status: 'ready', total: data.value, elapsedMs: data.elapsedMs });
    } catch (e) {
      if (requestId !== tableRowCountSeqRef.current) return;
      const code = e instanceof ApiError ? e.code : undefined;
      setTableRowCount(rowCountFailure(code));
      showError(rowCountErrorMessage(localizeError(e), code));
    }
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

  async function loadMetadata(conn = selected, options: { schema?: string; keyword?: string; page?: number; append?: boolean; refresh?: boolean; background?: boolean; applyKeyword?: boolean } = {}) {
    if (!conn) return;
    metadataAbortRef.current?.abort();
    const controller = new AbortController();
    metadataAbortRef.current = controller;
    const requestId = ++metadataRequestSeqRef.current;
    setMetadataLoading(true);
    setMetadataBlockingLoading(!options.background || !metadataRef.current);
    try {
      if (options.refresh) {
        structureRequestSeqRef.current += 1;
        clearObjectStructureCache();
        completionCatalogCacheRef.current.clear();
        objectDetailCacheRef.current.clear();
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
      const data = await api<Metadata>(`/metadata/${conn.id}?${params.toString()}`, { signal: controller.signal });
      if (requestId !== metadataRequestSeqRef.current || selectedIdRef.current !== conn.id) return;
      setMetadataQuery((current) => ({ ...current, schema: data.selectedSchema || '' }));
      if (options.applyKeyword !== false) setMetadataAppliedKeyword(keyword);
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
      const totalText = data.totalObjectsExact === false ? `至少 ${data.totalObjects}` : String(data.totalObjects);
      const nextMessage = data.hasMore
        ? `${cacheText}${timeText}，${scopeText} 已加载 ${loadedCount} 个对象（${totalText}）`
        : `${cacheText}${timeText}，${scopeText} 共 ${data.totalObjects} 个对象`;
      if (options.refresh) {
        showSuccess(nextMessage);
      } else {
        setWorkspaceStatus({ kind: 'success', text: nextMessage });
      }
    } catch (e) {
      if ((e as Error).name === 'AbortError') return;
      if (requestId === metadataRequestSeqRef.current && selectedIdRef.current === conn.id) {
        showError(localizeMessage((e as Error).message));
      }
    } finally {
      if (requestId === metadataRequestSeqRef.current) {
        setMetadataLoading(false);
        setMetadataBlockingLoading(false);
        if (metadataAbortRef.current === controller) metadataAbortRef.current = null;
      }
    }
  }

  function clearMetadataSearchTimer() {
    if (metadataSearchTimerRef.current != null) window.clearTimeout(metadataSearchTimerRef.current);
    metadataSearchTimerRef.current = null;
  }

  function runMetadataSearch(keyword: string, kind: ExplorerObjectKind = 'TABLE') {
    clearMetadataSearchTimer();
    setMetadataQuery((current) => ({ ...current, keyword }));
    setMetadataAppliedKeyword(keyword);
    if (kind === 'TABLE' && selected) void loadMetadata(selected, { keyword, page: 0, background: true });
  }

  function queueMetadataSearch(keyword: string, kind: ExplorerObjectKind = 'TABLE') {
    setMetadataQuery((current) => ({ ...current, keyword }));
    clearMetadataSearchTimer();
    if (!keyword.trim()) {
      runMetadataSearch('', kind);
      return;
    }
    metadataSearchTimerRef.current = window.setTimeout(() => runMetadataSearch(keyword, kind), METADATA_SEARCH_DEBOUNCE_MS);
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
    clearMetadataSearchTimer();
    await loadMetadata(selected, { schema: metadataQuery.schema, page: 0, refresh: true, background: true });
    if (mode === 'object' && activeObjectTarget) {
      await loadObjectDetail(activeObjectTarget, { refresh: true });
    }
    if (mode === 'table' && activeTable) {
      await loadTable(activeTable, { page: tablePage });
    }
  }

  /**
   * 全局搜索命中后的落点。
   *
   * 表直接打开数据（和对象树双击一致），视图打开详情；其余类型没有独立工作区，
   * 交给资源管理器切换到对应类型并带上名字作为关键字。
   */
  function openObjectSearchHit(hit: ObjectSearchHit) {
    const object: DbObject = {
      schemaName: hit.schemaName || metadataQuery.schema || metadata?.selectedSchema,
      name: hit.name,
      type: hit.kind === 'VIEW' ? 'VIEW' : 'TABLE',
      columns: [],
      indexes: []
    };
    if (hit.kind === 'TABLE') {
      openTable(object);
      return;
    }
    if (hit.kind === 'VIEW') {
      openObjectDetail(object);
      return;
    }
    setExplorerRequestedView({ kind: hit.kind as ExplorerObjectKind, keyword: hit.name, token: Date.now() });
    if (compactLayout) setMobileExplorerOpen(true);
  }

  /**
   * 跟着外键跳到关联记录。
   *
   * 生成一条 SELECT 放进新的 SQL 标签页而不是直接打开表数据：表数据工作区没有按任意列筛选的
   * 入口，而且让用户看得见自己将要执行什么。
   */
  function followRelation(target: RelationTarget, value: unknown) {
    if (!canJumpToRelation(value)) return;
    openSqlInNewTab(relationJumpSql(target, value), `关联 ${target.tableName}`);
  }

  function openRelationTarget(relation: ObjectRelation, direction: 'imported' | 'exported') {
    const target = relationTarget(relation, direction);
    openObjectDetail({
      schemaName: target.schemaName,
      name: target.tableName,
      type: 'TABLE',
      columns: [],
      indexes: []
    });
  }

  /** 在新标签页里打开一段 SQL，保留当前标签的草稿。 */
  function openSqlInNewTab(sql: string, title: string) {
    if (sqlTabsRef.current.length >= MAX_SQL_TABS) {
      toastApi.warning(`最多同时打开 ${MAX_SQL_TABS} 个 SQL 标签页，请先关闭不需要的标签页。`);
      return;
    }
    const nextIndex = sqlTabSeqRef.current + 1;
    sqlTabSeqRef.current = nextIndex;
    const tab: SqlTab = { ...createSqlTab(nextIndex), title: title.slice(0, 80), sql, dirty: true };
    setSqlTabs((tabs) => [...tabs, tab]);
    setActiveSqlTabId(tab.id);
    setMode('sql');
  }

  function objectCacheKey(connectionId: number, object: Pick<DbObject, 'schemaName' | 'name'>) {
    return `${connectionId}:${encodeURIComponent(object.schemaName || '')}:${encodeURIComponent(object.name)}`;
  }

  function objectDetailRequest(connectionId: number, object: DbObject, refresh = false) {
    const cacheKey = objectCacheKey(connectionId, object);
    if (refresh) objectDetailCacheRef.current.delete(cacheKey);
    return objectDetailCacheRef.current.load(cacheKey, async () => {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      if (refresh) params.set('refresh', 'true');
      return api<ObjectDetail>(`/metadata/${connectionId}/objects/detail?${params.toString()}`);
    });
  }

  function prefetchObjectDetail(object: DbObject) {
    const connectionId = selectedIdRef.current;
    if (!connectionId) return;
    void loadObjectDetailWorkspace().catch(() => undefined);
    const cacheKey = objectCacheKey(connectionId, object);
    if (objectDetailCacheRef.current.peek(cacheKey) || objectDetailCacheRef.current.pendingCount > 0) return;
    void objectDetailRequest(connectionId, object).catch(() => undefined);
  }

  function clearObjectDetailPrefetchTimer() {
    if (objectDetailPrefetchTimerRef.current != null) window.clearTimeout(objectDetailPrefetchTimerRef.current);
    objectDetailPrefetchTimerRef.current = null;
    objectDetailPrefetchCandidateRef.current = '';
  }

  function queueSqlObjectDetailPrefetch(reference: SqlTableReference) {
    const connectionId = selectedIdRef.current;
    if (!connectionId) return;
    const object = findCompletionObject(metadataRef.current?.objects || [], reference.schemaName, reference.name) || {
      schemaName: reference.schemaName || metadataRef.current?.selectedSchema || metadataRef.current?.currentSchema,
      name: reference.name,
      type: 'TABLE',
      columns: [],
      indexes: []
    };
    const candidate = objectCacheKey(connectionId, object);
    if (objectDetailPrefetchCandidateRef.current === candidate) return;
    clearObjectDetailPrefetchTimer();
    objectDetailPrefetchCandidateRef.current = candidate;
    objectDetailPrefetchTimerRef.current = window.setTimeout(() => {
      objectDetailPrefetchTimerRef.current = null;
      prefetchObjectDetail(object);
    }, OBJECT_DETAIL_PREFETCH_DELAY_MS);
  }

  function clearObjectStructureCache() {
    objectStructureCacheRef.current.clear();
    setStructureCacheRevision((current) => current + 1);
  }

  function loadCachedObjectStructure(connectionId: number, object: DbObject) {
    const cacheKey = objectCacheKey(connectionId, object);
    const generation = structureRequestSeqRef.current;
    return objectStructureCacheRef.current.load(cacheKey, async () => {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      const structure = await api<ObjectStructure>(`/metadata/${connectionId}/objects/structure?${params.toString()}`);
      const nextObject: DbObject = { ...object, ...structure };
      if (selectedIdRef.current === connectionId && structureRequestSeqRef.current === generation) {
        setStructureCacheRevision((current) => current + 1);
      }
      return nextObject;
    });
  }

  async function loadObjectStructure(object: DbObject) {
    const connectionId = selectedIdRef.current;
    if (!connectionId) return null;
    const cacheKey = objectCacheKey(connectionId, object);
    const cached = objectStructureCacheRef.current.get(cacheKey);
    if (cached) return cached;

    const loadingKey = `${object.schemaName || ''}.${object.name}`;
    const generation = structureRequestSeqRef.current;
    setStructureLoadingKey(loadingKey);
    try {
      const structure = await loadCachedObjectStructure(connectionId, object);
      return selectedIdRef.current === connectionId && structureRequestSeqRef.current === generation ? structure : null;
    } catch (e) {
      if (selectedIdRef.current === connectionId && structureRequestSeqRef.current === generation) showError(localizeMessage((e as Error).message));
      return null;
    } finally {
      setStructureLoadingKey((current) => current === loadingKey ? null : current);
    }
  }

  async function execute(path = '/sql/execute', productionConfirmation?: string, liveSql?: string) {
    if (!selected) {
      updateActiveSqlTab({ message: '请先选择一个数据库连接', statusKind: 'info' });
      showInfo('请先选择一个数据库连接');
      return;
    }
    const target = sqlExecutionTarget(liveSql);
    if (!target.sql.trim()) {
      updateActiveSqlTab({ message: '请输入要执行的 SQL', statusKind: 'info' });
      return;
    }
    if (path === '/sql/explain' && !selected.capabilities?.explain) {
      showInfo('当前数据库类型暂不支持执行计划');
      return;
    }
    if (sqlBusyRef.current) {
      showInfo('已有 SQL 操作正在处理，请等待完成后重试');
      return;
    }
    sqlBusyRef.current = true;
    try {
      if (selected.environment === 'prod' && !productionConfirmation) {
        const confirmation = await requestProductionConfirmation(path === '/sql/explain' ? '生成执行计划' : '执行 SQL');
        if (!confirmation) {
          updateActiveSqlTab({ message: '已取消生产 SQL 操作', statusKind: 'info' });
          return;
        }
        productionConfirmation = confirmation;
      }
      const executionId = createUuid();
      sqlExecutionIdRef.current = executionId;
      setMode('sql');
      setSqlCancellable(path !== '/sql/explain');
      setSqlLoading(true);
      try {
        if (path === '/sql/explain') {
        const data = await api<SqlResult>(path, {
          method: 'POST',
          headers: productionConfirmationHeaders(productionConfirmation),
          body: JSON.stringify({ connectionId: selected.id, sql: target.sql, executionId, schemaName: activeSqlSchema })
        });
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
        updateActiveSqlTab({ results: [result], activeResultKey: statementResultKey(result), message: nextMessage, statusKind: 'success', errorDetail: undefined });
        } else {
        // 手动事务开着时走事务端点：同一条连接、同一个事务，由用户决定提交还是回滚。
        const transactionPath = transactionExecutePath(transactionState);
        const executeScript = async (unscopedMutationConfirmed: boolean): Promise<SqlScriptResult> => {
          if (transactionPath) {
            const response = await api<SqlTransactionScriptResult>(transactionPath, {
              method: 'POST',
              body: JSON.stringify({ sql: target.sql, unscopedMutationConfirmed })
            });
            setTransactionState((current) => ({ ...current, transaction: response.transaction }));
            return response;
          }
          return api<SqlScriptResult>('/sql/execute-script', {
            method: 'POST',
            headers: productionConfirmationHeaders(productionConfirmation),
            body: JSON.stringify({
              connectionId: selected.id,
              sql: target.sql,
              pageSize: layoutPreferences.sqlPageSize,
              executionId,
              schemaName: activeSqlSchema,
              unscopedMutationConfirmed
            })
          });
        };
        let data: SqlScriptResult;
        try {
          data = await executeScript(false);
        } catch (error) {
          if (!(error instanceof ApiError) || error.code !== 'UNSCOPED_MUTATION_CONFIRMATION_REQUIRED') throw error;
          setSqlCancellable(false);
          const confirmed = await requestUnscopedMutationConfirmation(error.statements || []);
          if (!confirmed) {
            updateActiveSqlTab({ message: '已取消无 WHERE 条件的 UPDATE/DELETE', statusKind: 'info' });
            return;
          }
          setSqlCancellable(true);
          data = await executeScript(true);
        }
        const failed = data.results.find((item) => item.status === 'FAILED');
        const successCount = data.results.filter((item) => item.status === 'SUCCESS').length;
        const firstResultSet = data.results.find((item) => item.result?.resultSet);
        const returnedRows = data.results.reduce((total, item) => total + (item.result?.resultSet ? item.result.rows.length : 0), 0);
        const truncated = data.results.some((item) => item.result?.truncated);
        const nextMessage = failed
          ? `第 ${failed.index} 条 SQL 执行失败，已成功 ${successCount} 条，用时 ${data.elapsedMs}ms`
          : `已执行 ${successCount} 条 SQL，返回 ${returnedRows} 行${truncated ? '（已达到服务端结果大小上限，结果可能不完整）' : ''}，用时 ${data.elapsedMs}ms`;
        updateActiveSqlTab({
          results: data.results,
          activeResultKey: statementResultKey(failed || firstResultSet || data.results[0]),
          message: nextMessage,
          statusKind: failed ? 'error' : 'success',
          // 单条语句的失败由结果标签自己展示，这里不再重复整体错误面板。
          errorDetail: undefined
        });
        if (failed) {
          selectStatementRange(target.baseOffset + failed.startOffset, target.baseOffset + failed.endOffset);
        }
        if (data.metadataChanged) {
          void loadMetadata(selected, { page: 0, refresh: true, background: true });
        }
        }
        // The history drawer refreshes itself when opened, so an unconditional
        // fetch here would only lengthen the perceived execution time.
        if (sqlHistoryState.open) await sqlHistoryState.refreshQuietly();
      } catch (e) {
        const errorMessage = localizeError(e);
        // 服务端已经把事务回收了（多半是空闲超时自动回滚）。不清掉本地状态的话，之后每次
        // 执行都会继续发到这个不存在的事务上，连切换连接都会被「有未结束的事务」拦住。
        releaseTransactionIfGone(e);
        // 结果区也要拿到原文：状态栏只有一行，长错误会被截断且无法复制。
        updateActiveSqlTab({ message: errorMessage, statusKind: 'error', errorDetail: errorMessage });
        toastApi.error(errorMessage);
        if (sqlHistoryState.open) await sqlHistoryState.refreshQuietly();
      } finally {
        if (sqlExecutionIdRef.current === executionId) sqlExecutionIdRef.current = null;
        setSqlCancellable(false);
        setSqlLoading(false);
      }
    } finally {
      sqlBusyRef.current = false;
    }
  }

  /** 当前正在看的那段结果：就地编辑发生在可见的表格里，写回和刷新都以它为准。 */
  function activeStatementResult(): SqlStatementResult | undefined {
    const results = activeSqlTab.results;
    return results.find((item) => statementResultKey(item) === activeSqlTab.activeResultKey)
      ?? results.find((item) => item.result?.resultSet);
  }

  /**
   * 把已提交的值写回当前结果的行数据。
   *
   * 必须在重查之前做：ResultGrid 一旦提交成功就会清空本地编辑态，而重查是尽力而为的
   * （网络错误、工作台正忙都会失败）。不先写回的话，表格会继续显示旧值，用户再改同一行时
   * 带上去的原值已经过期，会被后端的乐观校验挡下来 —— 也就是被修掉的那个问题又回来了。
   */
  function applyCommittedResultEdits(changes: RowChange[]) {
    const tabId = activeSqlTab.id;
    const target = activeStatementResult();
    const resultKey = target && statementResultKey(target);
    if (!target || !resultKey) return;
    setSqlTabs((tabs) => tabs.map((tab) => tab.id !== tabId ? tab : {
      ...tab,
      results: tab.results.map((item) => {
        if (statementResultKey(item) !== resultKey) return item;
        const rows = applyCommittedChanges(
          item.result.rows, item.result.columns, item.result.edit?.rowKeyTokens || [], changes
        );
        return rows === item.result.rows ? item : { ...item, result: { ...item.result, rows } };
      })
    }));
  }

  /** 重新执行当前正在看的那段结果，偏移与翻页历史保持不变。 */
  async function refreshActiveSqlResult() {
    const target = activeStatementResult();
    const page = target?.result?.page;
    if (!target || !page) return;
    await loadSqlResultPage(target, currentSqlPage(page));
  }

  async function loadSqlResultPage(statementResult: SqlStatementResult, navigation: SqlPageNavigation) {
    const page = statementResult.result.page;
    if (!page || !selected || selected.id !== page.connectionId) {
      showInfo('请切回该结果所属的数据库连接后再翻页');
      return;
    }
    if (sqlBusyRef.current) {
      showInfo('已有 SQL 操作正在处理，请等待完成后重试');
      return;
    }
    const tabId = activeSqlTab.id;
    const resultKey = statementResultKey(statementResult)!;
    const executionId = createUuid();
    sqlBusyRef.current = true;
    sqlExecutionIdRef.current = executionId;
    setSqlPagingResultKey(`${tabId}:${resultKey}`);
    setSqlCancellable(true);
    setSqlLoading(true);
    layoutPreferences.setSqlPageSize(navigation.pageSize);
    try {
      const data = await api<SqlResult>('/sql/query-page', {
        method: 'POST',
        // Deliberately auto-filled rather than prompted: paging re-runs a SELECT
        // the user already confirmed for this connection, and the backend
        // requires the header on every request. Do NOT copy this to anything
        // that mutates — the DDL and table-lifecycle panels each make the user
        // type the connection name, which is what the guard is for.
        //
        // 残留风险，改动前请先读完：ExecutionGuard 之所以对自由 SQL 也要求确认，正是因为
        // SELECT 可能调用带副作用的函数。翻页会把同一条 SELECT 重新执行一遍，也就会把那些
        // 副作用再触发一次，而用户只在首次执行时确认过。这里接受这个权衡（否则每翻一页都要
        // 重新输入连接名），但它不是「翻页一定安全」。
        headers: productionConfirmationHeaders(selected.environment === 'prod' ? selected.name : undefined),
        body: JSON.stringify({
          connectionId: page.connectionId,
          sql: statementResult.sql,
          offset: navigation.offset,
          pageSize: navigation.pageSize,
          executionId,
          schemaName: page.schemaName
        })
      });
      if (data.page) data.page.previousOffsets = navigation.previousOffsets;
      setSqlTabs((tabs) => enforceResultBudget(tabs.map((tab) => tab.id !== tabId ? tab : {
          ...tab,
          results: tab.results.map((item) => statementResultKey(item) === resultKey ? { ...item, result: data } : item),
          message: `已加载第 ${data.page ? data.page.offset + 1 : navigation.offset + 1} 行起的查询结果，返回 ${data.rows.length} 行`,
          statusKind: 'success'
        }), tabId));
    } catch (e) {
      const errorMessage = localizeMessage((e as Error).message);
      showError(errorMessage);
    } finally {
      if (sqlExecutionIdRef.current === executionId) sqlExecutionIdRef.current = null;
      setSqlPagingResultKey(null);
      setSqlCancellable(false);
      setSqlLoading(false);
      sqlBusyRef.current = false;
    }
  }

  async function formatSql(liveSql?: string) {
    const editor = editorRef.current;
    const model = editor?.getModel();
    const modelVersion = model?.getVersionId();
    const currentSql = model?.getValue() ?? liveSql ?? activeSqlTab.sql;
    const selection = editor?.getSelection();
    const cursor = model && editor
      ? model.getOffsetAt(editor.getPosition() || { lineNumber: 1, column: 1 })
      : 0;
    const target = getSqlFormatTarget(currentSql, cursor, selection && model && !selection.isEmpty() ? {
      start: model.getOffsetAt(selection.getStartPosition()),
      end: model.getOffsetAt(selection.getEndPosition())
    } : undefined);
    if (!target) {
      updateActiveSqlTab({ message: '当前语句没有可格式化的 SQL', statusKind: 'info' });
      return;
    }
    if (sqlBusyRef.current) {
      showInfo('已有 SQL 操作正在处理，请等待完成后重试');
      return;
    }
    sqlBusyRef.current = true;
    setSqlLoading(true);
    setSqlCancellable(false);
    try {
      const data = await api<{ sql: string }>('/sql/format', { method: 'POST', body: JSON.stringify({ sql: target.sql }) });
      if (editor && model && editor.getModel() === model) {
        if (modelVersion !== undefined && model.getVersionId() !== modelVersion) {
          updateActiveSqlTab({ message: 'SQL 内容已变化，本次格式化结果未应用', statusKind: 'info' });
          return;
        }
        const start = model.getPositionAt(target.start);
        const end = model.getPositionAt(target.end);
        editor.executeEdits('format-sql', [{
          range: { startLineNumber: start.lineNumber, startColumn: start.column, endLineNumber: end.lineNumber, endColumn: end.column },
          text: data.sql,
          forceMoveMarkers: true
        }]);
        const formattedEnd = model.getPositionAt(target.start + data.sql.length);
        if (target.selected) {
          editor.setSelection({
            startLineNumber: start.lineNumber,
            startColumn: start.column,
            endLineNumber: formattedEnd.lineNumber,
            endColumn: formattedEnd.column
          });
        } else {
          const relativeCursor = Math.max(0, cursor - target.start);
          editor.setPosition(model.getPositionAt(target.start + Math.min(relativeCursor, data.sql.length)));
        }
        editor.focus();
        updateActiveSqlTab({ sql: model.getValue(), message: `当前${target.selected ? '选中' : '光标所在'}语句格式化完成`, statusKind: 'success' });
      } else {
        const nextSql = `${currentSql.slice(0, target.start)}${data.sql}${currentSql.slice(target.end)}`;
        updateActiveSqlTab({ sql: nextSql, message: '当前语句格式化完成', statusKind: 'success' });
      }
    } catch (e) {
      const errorMessage = `格式化失败：${localizeMessage((e as Error).message)}`;
      updateActiveSqlTab({ message: errorMessage, statusKind: 'error' });
      toastApi.error(errorMessage);
    } finally {
      setSqlLoading(false);
      sqlBusyRef.current = false;
    }
  }

  async function beginSqlTransaction() {
    if (!selected) {
      showInfo('请先选择数据库连接');
      return;
    }
    const productionConfirmation = await requestProductionConfirmation('开启手动事务');
    if (selected.environment === 'prod' && !productionConfirmation) return;
    setTransactionState((current) => ({ ...current, pending: true }));
    try {
      const transaction = await api<SqlTransaction>('/sql/transactions', {
        method: 'POST',
        headers: productionConfirmationHeaders(productionConfirmation),
        body: JSON.stringify({ connectionId: selected.id, schemaName: activeSqlSchema })
      });
      setTransactionState({ transaction, pending: false });
      showSuccess('已开启手动事务：后续语句在同一事务中执行，需要手动提交或回滚');
    } catch (e) {
      setTransactionState(IDLE_SQL_TRANSACTION);
      showError(localizeError(e));
    }
  }

  /** 事务已被服务端回收时清掉本地状态并说明原因；返回是否确实是这种情况。 */
  function releaseTransactionIfGone(error: unknown): boolean {
    if (!isTransactionGone(apiErrorCode(error))) return false;
    if (transactionState.transaction) showInfo(transactionGoneNotice(transactionState));
    setTransactionState(IDLE_SQL_TRANSACTION);
    return true;
  }

  function finishSqlTransaction(commit: boolean) {
    const transaction = transactionState.transaction;
    if (!transaction) return;
    modalApi.confirm({
      title: commit ? '提交手动事务？' : '回滚手动事务？',
      content: transactionFinishPrompt(transactionState, commit),
      okText: commit ? '提交' : '回滚',
      cancelText: '返回',
      okButtonProps: { danger: !commit },
      onOk: async () => {
        setTransactionState((current) => ({ ...current, pending: true }));
        try {
          await api<SqlTransaction>(`/sql/transactions/${transaction.id}/${commit ? 'commit' : 'rollback'}`, { method: 'POST' });
          setTransactionState(IDLE_SQL_TRANSACTION);
          showSuccess(commit ? `事务已提交，共 ${transaction.statementCount} 条语句生效` : '事务已回滚，本次改动全部丢弃');
          if (selected) void loadMetadata(selected, { page: 0, refresh: true, background: true });
        } catch (e) {
          // 提交/回滚失败同样可能是事务已经不在了。那种情况要退出事务模式并让弹窗关掉，
          // 而不是留一个提交不掉也回滚不掉的死状态。
          if (releaseTransactionIfGone(e)) return;
          setTransactionState((current) => transactionStateAfterError(current, apiErrorCode(e)));
          showError(localizeError(e));
          throw e;
        }
      }
    });
  }

  async function cancelSqlExecution() {
    if (sqlCancelling) return;
    // An export has no server-side execution id; it is aborted client-side,
    // which also frees the workspace and lets the user switch connections.
    const exportController = exportAbortRef.current;
    if (exportController) {
      setSqlCancelling(true);
      exportController.abort();
      return;
    }
    const executionId = sqlExecutionIdRef.current;
    if (!executionId) return;
    setSqlCancelling(true);
    try {
      const result = await api<{ ok: boolean; message: string }>(`/sql/executions/${executionId}/cancel`, { method: 'POST' });
      showInfo(result.message);
    } catch (e) {
      showError(`取消 SQL 失败：${localizeError(e)}`);
    } finally {
      setSqlCancelling(false);
    }
  }

  async function openSqlHistory() {
    // The drawer only opens once the first page has loaded, so without an
    // in-flight guard the button looks dead and repeated clicks pile up
    // duplicate /sql/history requests.
    if (sqlHistoryState.loading) return;
    // 每次打开都回到「最近一页、无关键字」，而不是沿用上次的搜索条件。
    if (!await sqlHistoryState.load(INITIAL_SQL_HISTORY_QUERY)) {
      // 具体原因已经由 useSqlHistory 弹出，这里只让状态栏留下痕迹。
      updateActiveSqlTab({ message: '历史记录加载失败', statusKind: 'error' });
      return;
    }
    sqlHistoryState.setFeatureLoaded(true);
    sqlHistoryState.setOpen(true);
  }

  async function exportSql(format: ExportFormat, liveSql?: string) {
    if (!selected) {
      updateActiveSqlTab({ message: '请先选择一个数据库连接', statusKind: 'info' });
      showInfo('请先选择一个数据库连接');
      return;
    }
    const target = sqlExecutionTarget(liveSql);
    if (!target.sql.trim()) {
      updateActiveSqlTab({ message: '请输入要导出的 SQL', statusKind: 'info' });
      return;
    }
    if (sqlBusyRef.current) {
      showInfo('已有 SQL 操作正在处理，请等待完成后重试');
      return;
    }
    sqlBusyRef.current = true;
    try {
      const productionConfirmation = await requestProductionConfirmation('导出查询结果');
      if (selected.environment === 'prod' && !productionConfirmation) return;
      let targetTableParts: string[] | undefined;
      if (format === 'sql') {
        targetTableParts = inferSqlTargetParts(target.sql);
        if (targetTableParts?.length === 1 && activeSqlSchema) targetTableParts = [activeSqlSchema, ...targetTableParts];
        if (!targetTableParts) {
          targetTableParts = await requestSqlExportTarget();
          if (!targetTableParts) {
            updateActiveSqlTab({ message: '已取消 SQL 导出', statusKind: 'info' });
            return;
          }
        }
      }
      // Exporting a large table can run for minutes and blocks connection
      // switching, so it must be interruptible like a normal execution.
      const controller = new AbortController();
      exportAbortRef.current = controller;
      setSqlLoading(true);
      setSqlCancellable(true);
      try {
      const response = await fetch(`${API}/sql/export`, {
        method: 'POST',
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          'X-User': 'admin',
          ...productionConfirmationHeaders(productionConfirmation)
        },
        body: JSON.stringify({ connectionId: selected.id, sql: target.sql, format, schemaName: activeSqlSchema, targetTableParts })
      });
      if (!response.ok) {
        const err = await response.json().catch(() => ({ message: response.statusText }));
        throw new Error(err.message || response.statusText);
      }
      const blob = await response.blob();
      downloadBlob(blob, `query-result-${timestamp()}.${format}`);
      const rowLimit = response.headers.get('x-export-row-limit') || '10000';
      const truncated = response.headers.get('x-export-truncated') === 'true';
      const nextMessage = `已导出 ${format.toUpperCase()}：${target.selected ? '选中 SQL' : '全部 SQL'}（最多 ${rowLimit} 行${truncated ? '，本次已截断' : ''}）`;
      updateActiveSqlTab({ message: nextMessage, statusKind: 'success' });
      toastApi.success(nextMessage);
      } catch (e) {
        if ((e as Error).name === 'AbortError') {
          updateActiveSqlTab({ message: '已取消导出', statusKind: 'info' });
          showInfo('已取消导出');
        } else {
          const errorMessage = `导出失败：${localizeError(e)}`;
          updateActiveSqlTab({ message: errorMessage, statusKind: 'error' });
          toastApi.error(errorMessage);
        }
      } finally {
        if (exportAbortRef.current === controller) exportAbortRef.current = null;
        setSqlCancellable(false);
        setSqlCancelling(false);
        setSqlLoading(false);
      }
    } finally {
      sqlBusyRef.current = false;
    }
  }

  function updateActiveSqlTab(patch: Partial<SqlTab>) {
    updateSqlTab(activeSqlTab.id, patch);
  }

  function updateSqlTab(targetId: string, patch: Partial<SqlTab>) {
    setSqlTabs((tabs) => enforceResultBudget(
      tabs.map((tab) => {
        if (tab.id !== targetId) return tab;
        const sqlChanged = patch.sql !== undefined && patch.sql !== tab.sql;
        return { ...tab, ...patch, dirty: patch.dirty ?? (sqlChanged ? true : tab.dirty) };
      }),
      targetId
    ));
  }

  function persistCurrentSqlSession(connectionId: number | null) {
    const liveSql = editorRef.current?.getValue();
    const tabs = sqlTabsRef.current.map((tab) => tab.id === activeSqlTabIdRef.current && liveSql !== undefined && liveSql !== tab.sql
      ? { ...tab, sql: liveSql, dirty: true }
      : tab);
    writeSqlSession(connectionId, tabs, activeSqlTabIdRef.current);
  }

  function activateSqlSession(nextConnectionId: number | null, captureLiveEditor: boolean) {
    const previousConnectionId = sqlSessionConnectionIdRef.current;
    if (previousConnectionId === nextConnectionId) return;
    if (captureLiveEditor) {
      persistCurrentSqlSession(previousConnectionId);
    } else {
      writeSqlSession(previousConnectionId, sqlTabsRef.current, activeSqlTabIdRef.current);
    }
    const restored = readSqlSession(nextConnectionId);
    const nextTabs = restored?.tabs || [{ id: `query-${Date.now()}-1`, title: '查询 1', sql: 'select 1 as val', dirty: false, results: [], message: '' }];
    const nextActiveTabId = restored?.activeTabId || nextTabs[0].id;
    sqlSessionConnectionIdRef.current = nextConnectionId;
    sqlTabSeqRef.current = Math.max(1, nextTabs.length);
    sqlTabsRef.current = nextTabs;
    activeSqlTabIdRef.current = nextActiveTabId;
    setSqlTabs(nextTabs);
    setActiveSqlTabId(nextActiveTabId);
    setSqlSessionRevision((current) => current + 1);
  }

  function addSqlTab() {
    if (sqlTabs.length >= MAX_SQL_TABS) {
      toastApi.warning(`最多同时打开 ${MAX_SQL_TABS} 个 SQL 标签页，请先关闭不需要的标签页。`);
      return;
    }
    const nextIndex = sqlTabSeqRef.current + 1;
    sqlTabSeqRef.current = nextIndex;
    const tab = createSqlTab(nextIndex);
    setSqlTabs((tabs) => [...tabs, tab]);
    setActiveSqlTabId(tab.id);
  }

  function closeSqlTab(targetId: string, liveSql?: string) {
    const current = sqlTabs.find((tab) => tab.id === targetId);
    if (!current || sqlTabs.length === 1) return;
    const target = liveSql !== undefined && liveSql !== current.sql
      ? { ...current, sql: liveSql, dirty: true }
      : current;
    if (target !== current) {
      setSqlTabs((tabs) => tabs.map((tab) => tab.id === targetId ? target : tab));
    }
    if (!target.dirty) {
      removeSqlTab(targetId);
      return;
    }
    modalApi.confirm({
      title: `关闭“${target.title}”？`,
      content: '该标签包含修改过的 SQL，关闭后当前会话内的草稿将被删除。',
      okText: '关闭标签',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: () => removeSqlTab(targetId)
    });
  }

  function removeSqlTab(targetId: string) {
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

  function renameSqlTab(targetId: string) {
    const target = sqlTabs.find((tab) => tab.id === targetId);
    if (!target) return;
    let title = target.title;
    modalApi.confirm({
      title: '重命名 SQL 标签',
      content: <Input autoFocus defaultValue={target.title} maxLength={80} aria-label="SQL 标签名称" onChange={(event) => { title = event.target.value; }} />,
      okText: '保存名称',
      cancelText: '取消',
      onOk: () => {
        const normalized = title.trim();
        if (!normalized) {
          toastApi.error('标签名称不能为空');
          return Promise.reject(new Error('标签名称不能为空'));
        }
        setSqlTabs((tabs) => tabs.map((tab) => tab.id === targetId ? { ...tab, title: normalized } : tab));
      }
    });
  }

  function duplicateSqlTab(targetId: string, liveSql?: string) {
    if (sqlTabs.length >= MAX_SQL_TABS) {
      toastApi.warning(`最多同时打开 ${MAX_SQL_TABS} 个 SQL 标签页，请先关闭不需要的标签页。`);
      return;
    }
    const source = sqlTabs.find((tab) => tab.id === targetId);
    if (!source) return;
    const nextIndex = sqlTabSeqRef.current + 1;
    sqlTabSeqRef.current = nextIndex;
    const duplicate = {
      ...createSqlTab(nextIndex),
      title: `${source.title} 副本`.slice(0, 80),
      sql: liveSql ?? source.sql,
      dirty: true
    };
    if (liveSql !== undefined && liveSql !== source.sql) {
      setSqlTabs((tabs) => [...tabs.map((tab) => tab.id === targetId ? { ...tab, sql: liveSql, dirty: true } : tab), duplicate]);
    } else {
      setSqlTabs((tabs) => [...tabs, duplicate]);
    }
    setActiveSqlTabId(duplicate.id);
  }

  function sqlExecutionTarget(liveSql?: string) {
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
    return { sql: model?.getValue() ?? liveSql ?? activeSqlTab.sql, selected: false, baseOffset: 0 };
  }

  function selectSqlFile(file: File) {
    if (!selected) {
      showInfo('请先选择数据库连接');
      return;
    }
    setSqlFileCandidate({ requestId: ++sqlFileRequestSeqRef.current, file, connection: selected });
    setSqlFileFeatureLoaded(true);
    setSqlFileTasksOpen(true);
  }

  function openSqlFileTasks() {
    setSqlFileFeatureLoaded(true);
    setSqlFileTasksOpen(true);
  }

  function handleSqlFileMetadataChanged(connectionId: number) {
    if (selected?.id === connectionId) void loadMetadata(selected, { page: 0, refresh: true }).catch(() => undefined);
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
    return `${connectionId}:${encodeURIComponent(schemaName || '')}`;
  }

  function loadCompletionCatalog(connectionId: number, schemaName?: string, prefix = '') {
    const normalizedPrefix = prefix.trim().toLowerCase();
    const key = `${completionCatalogKey(connectionId, schemaName)}:${normalizedPrefix}`;
    return completionCatalogCacheRef.current.load(key, async () => {
      const params = new URLSearchParams();
      if (schemaName) params.set('schema', schemaName);
      if (normalizedPrefix) params.set('prefix', normalizedPrefix);
      params.set('limit', '100');
      const suffix = params.size > 0 ? `?${params.toString()}` : '';
      return api<CompletionCatalog>(`/metadata/${connectionId}/completion-catalog${suffix}`);
    });
  }

  function loadCompletionStructure(connectionId: number, object: DbObject) {
    return loadCachedObjectStructure(connectionId, object);
  }

  async function sqlCompletionItems(
    model: Monaco.editor.ITextModel,
    position: Monaco.Position,
    monaco: Parameters<SqlEditorOnMount>[1],
    token: Monaco.CancellationToken,
    context: ReturnType<typeof analyzeSqlCompletion> = analyzeSqlCompletion(model.getValue(), model.getOffsetAt(position))
  ) {
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
    await sleep(80);
    if (token.isCancellationRequested || selectedIdRef.current !== connectionId) return [];
    const schemaName = metadataRef.current?.selectedSchema || '';
    const prefix = context.replacement.prefix.toLowerCase();
    let catalog: CompletionCatalog;
    try {
      catalog = await loadCompletionCatalog(connectionId, schemaName, context.mode === 'table' ? prefix : '');
    } catch {
      catalog = { selectedSchema: schemaName, objects: metadataRef.current?.objects || [] };
    }
    if (token.isCancellationRequested || selectedIdRef.current !== connectionId) return [];
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
      const object = findCompletionObject(catalog.objects, table.schemaName, table.name) || {
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

  objectDefinitionNavigationRef.current = (reference) => {
    const knownObject = findCompletionObject(metadataRef.current?.objects || [], reference.schemaName, reference.name);
    openObjectDetail(knownObject || {
      schemaName: reference.schemaName || activeSqlSchema,
      name: reference.name,
      type: 'TABLE',
      columns: [],
      indexes: []
    });
  };

  // A dialog or drawer has focus of its own; acting on the workspace behind it
  // would be surprising. The explorer toggle is exempt because it is also how a
  // user closes the mobile explorer drawer.
  const overlayOpen = Boolean(productionConfirmationRequest)
    || activeDrawer !== null
    || connectionEditor.mode !== 'closed'
    || tableCreateOpen
    || Boolean(tableLifecycleAction)
    || sqlHistoryState.open
    || sqlFileTasksOpen
    || objectSearchOpen
    || snippetsOpen;

  shortcutHandlerRef.current = (shortcut: AppShortcut, event: KeyboardEvent) => {
    if (shortcut.kind === 'toggle-explorer') {
      event.preventDefault();
      if (compactLayout) setMobileExplorerOpen((current) => !current);
      else layoutPreferences.toggleExplorer();
      return;
    }
    if (shortcut.kind === 'open-object-search') {
      if (!selected) return;
      // 浏览器的打印快捷键要让位，和 VS Code Web 的取舍一致。
      event.preventDefault();
      setObjectSearchOpen((current) => !current);
      return;
    }
    if (overlayOpen) return;
    if (shortcut.kind === 'new-sql-tab') {
      if (mode !== 'sql' || !selected) return;
      event.preventDefault();
      addSqlTab();
      return;
    }
    if (shortcut.kind === 'select-sql-tab') {
      if (mode !== 'sql' || !selected) return;
      const target = sqlTabs[shortcut.index];
      if (!target) return;
      event.preventDefault();
      setActiveSqlTabId(target.id);
      return;
    }
    if (shortcut.kind === 'commit-table-changes') {
      // Outside the table workspace Ctrl/Cmd+S is left to the browser rather
      // than silently swallowed.
      if (mode !== 'table' || !activeTable || pendingChanges.length === 0) return;
      if (tableLoading || selected?.readonly || !selected?.capabilities?.tableEdit) return;
      event.preventDefault();
      void commitChanges();
    }
  };

  const handleEditorMount: SqlEditorOnMount = (editor, monaco) => {
    editorRef.current = editor;
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => executeRef.current());
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyF, () => formatRef.current());
    completionProviderRef.current?.dispose();
    const provider = monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters: ['.', ' '],
      provideCompletionItems: async (
        model: Monaco.editor.ITextModel,
        position: Monaco.Position,
        providerContext: Monaco.languages.CompletionContext,
        token: Monaco.CancellationToken
      ) => {
        const completionContext = analyzeSqlCompletion(model.getValue(), model.getOffsetAt(position));
        if (providerContext.triggerCharacter === ' ' && !shouldTriggerSqlConditionColumnCompletion(completionContext)) {
          return { suggestions: [] };
        }
        return {
          suggestions: await sqlCompletionItems(model, position, monaco, token, completionContext),
          incomplete: isSqlCompletionListIncomplete(completionContext)
        };
      }
    });
    completionProviderRef.current = provider;
    const definitionLink = editor.createDecorationsCollection();
    let hoveredPosition: Monaco.Position | null = null;
    // Ctrl/Cmd+悬停的定义跳转装饰挂在两个高频事件上：window 级别的 keydown/keyup（编辑器里
    // 每敲一个字符就是两次）和 onMouseMove。没有下面这三个状态位时，每一次事件都会跑一遍
    // deltaDecorations，命中时还要把整篇 SQL 重新词法扫描一遍。
    let definitionLinkActive = false;
    let linkedRange: { start: number; end: number } | null = null;
    let modifierPressed = false;

    const clearDefinitionLinkDecoration = () => {
      if (!definitionLinkActive) return;
      definitionLink.clear();
      definitionLinkActive = false;
      linkedRange = null;
    };
    const objectReferenceAt = (position: Monaco.Position | null) => {
      const model = editor.getModel();
      if (!model || !position) return undefined;
      return findSqlObjectReferenceAtOffset(model.getValue(), model.getOffsetAt(position));
    };
    const updateDefinitionLink = (position: Monaco.Position | null) => {
      if (!modifierPressed) {
        clearDefinitionLinkDecoration();
        clearObjectDetailPrefetchTimer();
        return;
      }
      const model = editor.getModel();
      // 指针还停在已经高亮的那个对象名上时，结果不会变，无需重新扫描。
      if (model && position && linkedRange) {
        const offset = model.getOffsetAt(position);
        if (offset >= linkedRange.start && offset < linkedRange.end) return;
      }
      const reference = objectReferenceAt(position);
      if (!model || !reference) {
        clearDefinitionLinkDecoration();
        clearObjectDetailPrefetchTimer();
        return;
      }
      const objectName = reference.parts[reference.parts.length - 1];
      const start = model.getPositionAt(objectName.start);
      const end = model.getPositionAt(objectName.end);
      definitionLink.set([{
        range: {
          startLineNumber: start.lineNumber,
          startColumn: start.column,
          endLineNumber: end.lineNumber,
          endColumn: end.column
        },
        options: { inlineClassName: 'sql-object-definition-link' }
      }]);
      definitionLinkActive = true;
      linkedRange = { start: objectName.start, end: objectName.end };
      queueSqlObjectDetailPrefetch(reference);
    };
    const mouseMoveListener = editor.onMouseMove((event) => {
      hoveredPosition = event.target.position;
      modifierPressed = event.event.ctrlKey || event.event.metaKey;
      updateDefinitionLink(hoveredPosition);
    });
    const mouseLeaveListener = editor.onMouseLeave(() => {
      hoveredPosition = null;
      clearDefinitionLinkDecoration();
      clearObjectDetailPrefetchTimer();
    });
    const mouseDownListener = editor.onMouseDown((event) => {
      if (!event.event.leftButton || (!event.event.ctrlKey && !event.event.metaKey)) return;
      const reference = objectReferenceAt(event.target.position);
      if (!reference) return;
      event.event.preventDefault();
      event.event.stopPropagation();
      clearDefinitionLinkDecoration();
      objectDefinitionNavigationRef.current(reference);
    });
    const contentListener = editor.onDidChangeModelContent(() => {
      // 内容一变，记下来的偏移量就失效了。
      clearDefinitionLinkDecoration();
      clearObjectDetailPrefetchTimer();
    });
    const handleModifierChange = (event: KeyboardEvent) => {
      const pressed = event.ctrlKey || event.metaKey;
      // 普通输入的 keydown/keyup 不改变修饰键状态，直接返回。
      if (pressed === modifierPressed) return;
      modifierPressed = pressed;
      updateDefinitionLink(hoveredPosition);
    };
    const clearDefinitionLink = () => {
      modifierPressed = false;
      clearDefinitionLinkDecoration();
      clearObjectDetailPrefetchTimer();
    };
    window.addEventListener('keydown', handleModifierChange);
    window.addEventListener('keyup', handleModifierChange);
    window.addEventListener('blur', clearDefinitionLink);
    editor.onDidDispose(() => {
      provider.dispose();
      mouseMoveListener.dispose();
      mouseLeaveListener.dispose();
      mouseDownListener.dispose();
      contentListener.dispose();
      clearObjectDetailPrefetchTimer();
      window.removeEventListener('keydown', handleModifierChange);
      window.removeEventListener('keyup', handleModifierChange);
      window.removeEventListener('blur', clearDefinitionLink);
      if (completionProviderRef.current === provider) completionProviderRef.current = null;
      if (editorRef.current === editor) editorRef.current = null;
    });
  };

  function findCompletionObject(objects: DbObject[], requestedSchema: string | undefined, requestedName: string) {
    const schemaMatches = (object: DbObject, folded: boolean) => !requestedSchema
      || (folded
        ? (object.schemaName || '').toLowerCase() === requestedSchema.toLowerCase()
        : (object.schemaName || '') === requestedSchema);
    const exact = objects.filter((object) => object.name === requestedName && schemaMatches(object, false));
    if (exact.length === 1) return exact[0];
    const folded = objects.filter((object) => object.name.toLowerCase() === requestedName.toLowerCase() && schemaMatches(object, true));
    return folded.length === 1 ? folded[0] : undefined;
  }

  function openTable(object: DbObject) {
    if (!selected || object.type.toUpperCase().includes('VIEW')) {
      return;
    }
    if (!selected.capabilities?.tableBrowse) {
      showInfo('当前数据库类型暂不支持表数据浏览');
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
    resetTableRowCount();
    // Never leave rows from the previous table visible under a new table name
    // if the new request fails.
    setTableData(null);
    setTableRows([]);
    setPreviewSql([]);
    setActiveTable(next);
    setTablePage(0);
    setTableCursorStack([null]);
    setMode('table');
    setActiveTableRelations(null);
    // 外键关系用于数据网格里的跳转；拿不到就只是没有跳转按钮，不影响浏览。
    if (selected) {
      const params = new URLSearchParams({ objectName: object.name });
      if (object.schemaName) params.set('schemaName', object.schemaName);
      api<ObjectRelations>(`/metadata/${selected.id}/objects/relations?${params.toString()}`)
        .then((relations) => {
          if (selectedIdRef.current === selected.id) setActiveTableRelations(relations);
        })
        .catch(() => undefined);
    }
    await loadTable(next, { page: 0, reset: true });
  }

  function openObjectDetail(object: DbObject) {
    confirmDiscardObjectDesign(() => {
      confirmDiscardTableChanges(() => {
        void loadObjectDetail(object);
      }, `查看对象“${object.name}”`);
    }, `查看对象“${object.name}”`);
  }

  function openCreateTable() {
    if (!selected) {
      showInfo('请先选择数据库连接');
      return;
    }
    if (selected.readonly || !selected.capabilities?.tableDesign) {
      showInfo('当前连接不支持表对象管理');
      return;
    }
    setMobileExplorerOpen(false);
    setTableCreateOpen(true);
  }

  function openTableLifecycleAction(operation: 'RENAME' | 'DROP', object: DbObject) {
    if (!selected || selected.readonly || !selected.capabilities?.tableDesign) {
      showInfo('当前连接不支持表对象管理');
      return;
    }
    if (object.type.toUpperCase().includes('VIEW')) {
      showInfo('首版仅支持管理物理表');
      return;
    }
    const targetName = `${object.schemaName ? `${object.schemaName}.` : ''}${object.name}`;
    const begin = () => {
      setMobileExplorerOpen(false);
      setTableLifecycleAction({ operation, object });
    };
    if (mode === 'table' && activeTable && sameTable(activeTable, object)) {
      confirmDiscardTableChanges(begin, `${operation === 'DROP' ? '删除' : '重命名'}数据表“${targetName}”`);
      return;
    }
    if (mode === 'object' && activeObjectDetail && sameTable(activeObjectDetail, object) && objectDesignDirtyRef.current) {
      confirmDiscardObjectDesign(() => {
        updateObjectDesignDirty(false);
        setActiveObjectTarget(null);
        setActiveObjectDetail(null);
        setMode('sql');
        begin();
      }, `${operation === 'DROP' ? '删除' : '重命名'}数据表“${targetName}”`);
      return;
    }
    begin();
  }

  async function tableLifecycleCompleted(operation: 'CREATE' | 'RENAME' | 'DROP', source: DbObject | null, newTableName?: string) {
    if (!selected) return;
    const activeTableMatched = Boolean(source && activeTable && sameTable(activeTable, source));
    const activeObjectMatched = Boolean(source && activeObjectTarget && sameTable(activeObjectTarget, source));
    if (operation === 'DROP' && activeTableMatched) {
      discardTableChanges();
      setMode('sql');
    }
    if (operation === 'DROP' && activeObjectMatched) {
      setActiveObjectTarget(null);
      setActiveObjectDetail(null);
      updateObjectDesignDirty(false);
      setMode('sql');
    }
    setMetadataQuery((current) => ({ ...current, schema: source?.schemaName || current.schema, keyword: '' }));
    await loadMetadata(selected, {
      schema: source?.schemaName || metadataQuery.schema,
      keyword: '',
      page: 0,
      refresh: true
    });
    if (operation === 'RENAME' && source && newTableName) {
      const renamed: DbObject = { ...source, name: newTableName, columns: [], indexes: [] };
      if (activeTableMatched) await applyOpenTable(renamed);
      else if (activeObjectMatched) await loadObjectDetail(renamed, { refresh: true });
    }
  }

  async function loadObjectDetail(object: DbObject, options: { refresh?: boolean } = {}) {
    if (!selected) return;
    const connectionId = selected.id;
    const requestId = ++objectDetailRequestSeqRef.current;
    const cached = options.refresh ? undefined : objectDetailCacheRef.current.get(objectCacheKey(connectionId, object));
    const retainedDetail = options.refresh && activeObjectDetail && sameTable(activeObjectDetail, object)
      ? activeObjectDetail
      : cached || null;
    setActiveObjectTarget(object);
    setActiveObjectDetail(retainedDetail);
    setMobileExplorerOpen(false);
    setMode('object');
    setObjectDetailLoading(Boolean(options.refresh || !cached));
    try {
      const detail = await objectDetailRequest(connectionId, object, options.refresh);
      if (requestId !== objectDetailRequestSeqRef.current || selectedIdRef.current !== connectionId) return;
      setActiveObjectDetail(detail);
      setActiveObjectTarget(detail);
      updateObjectDesignDirty(false);
      setWorkspaceStatus({ kind: 'success', text: `已加载对象详情：${detail.name}` });
    } catch (e) {
      if (requestId === objectDetailRequestSeqRef.current) showError(localizeMessage((e as Error).message));
    } finally {
      if (requestId === objectDetailRequestSeqRef.current) setObjectDetailLoading(false);
    }
  }

  async function loadTable(table = activeTable, options: { page?: number; pageSize?: number; reset?: boolean } = {}) {
    if (!selected || !table) return;
    tableAbortRef.current?.abort();
    const controller = new AbortController();
    tableAbortRef.current = controller;
    const connectionId = selected.id;
    const requestId = ++tableRequestSeqRef.current;
    const requestedPage = Math.max(options.page ?? tablePage, 0);
    const requestedPageSize = options.pageSize ?? layoutPreferences.tablePageSize;
    let cursor: string | null | undefined = options.reset || requestedPage === 0 ? null : tableCursorStack[requestedPage];
    if (cursor === undefined && requestedPage === tablePage + 1) cursor = tableData?.nextCursor || undefined;
    if (cursor === undefined) {
      showInfo('当前页游标已失效，请从第一页重新加载');
      setTableLoading(false);
      if (tableAbortRef.current === controller) tableAbortRef.current = null;
      return;
    }
    setTableLoading(true);
    try {
      const params = new URLSearchParams({
        connectionId: String(connectionId),
        tableName: table.tableName,
        pageSize: String(requestedPageSize)
      });
      if (table.schemaName) params.set('schemaName', table.schemaName);
      if (cursor) params.set('cursor', cursor);
      const data = await api<TableData>(`/data/table?${params.toString()}`, { signal: controller.signal });
      if (requestId !== tableRequestSeqRef.current || selectedIdRef.current !== connectionId) return;
      setTableData(data);
      setTablePage(requestedPage);
      setTableCursorStack((current) => {
        const next = options.reset || requestedPage === 0 ? [null] : current.slice(0, requestedPage + 1);
        if (data.nextCursor) next[requestedPage + 1] = data.nextCursor;
        return next;
      });
      setTableRows(data.rows.map((row, index) => ({ id: `row-${requestedPage}-${index}`, values: { ...row }, original: { ...row }, keyToken: data.rowKeyTokens?.[index] })));
      setPreviewSql([]);
      setWorkspaceStatus({ kind: 'success', text: `已从 ${table.tableName} 加载第 ${requestedPage + 1} 页，共 ${data.rows.length} 行${data.hasMore ? '，还有下一页' : ''}${data.navigationMode === 'OFFSET' ? '；当前表无稳定行键，深页浏览受限' : ''}` });
    } catch (e) {
      if ((e as Error).name === 'AbortError') return;
      if (requestId === tableRequestSeqRef.current) showError(localizeMessage((e as Error).message));
    } finally {
      if (requestId === tableRequestSeqRef.current) setTableLoading(false);
      if (tableAbortRef.current === controller) tableAbortRef.current = null;
    }
  }

  const editCell = useCallback((rowId: string, column: string, value: unknown) => {
    setTableRows((rows) => rows.map((row) => {
      if (row.id !== rowId) return row;
      if (row.inserted) {
        const values = { ...row.values };
        const touched = new Set(row.touchedColumns || []);
        if (value === undefined) {
          delete values[column];
          touched.delete(column);
        } else {
          values[column] = value;
          touched.add(column);
        }
        return { ...row, values, touchedColumns: [...touched] };
      }
      return { ...row, values: { ...row.values, [column]: value } };
    }));
    setPreviewSql([]);
  }, []);

  function changeDbType(dbType: string) {
    const preset = DB_TYPE_OPTIONS.find((option) => option.value === dbType);
    setConnectionEditor((current) => current.mode === 'closed'
      ? current
      : updateConnectionEditorForm(current, {
          ...current.form,
          dbType,
          jdbcUrl: preset ? preset.url : current.form.jdbcUrl
        }));
  }

  function addRow() {
    if (!tableData) return;
    if (pendingChanges.length >= MAX_TABLE_CHANGES) {
      showInfo(`单次最多提交 ${MAX_TABLE_CHANGES} 项变更，请先提交当前修改。`);
      return;
    }
    setTableRows((rows) => [{ id: `new-${createUuid()}`, values: {}, touchedColumns: [], inserted: true }, ...rows]);
    setPreviewSql([]);
  }

  /** 大 CSV 交给后端流式转成 INSERT 脚本，复用 SQL 文件执行的进度、取消与并发闸门。 */
  function startBackgroundImport(file: File, route: Extract<ImportRoute, { kind: 'background' }>) {
    if (!selected || !activeTable) {
      showInfo('请先选择数据库连接');
      return;
    }
    const target = activeTable.schemaName || metadata?.selectedSchema || metadata?.currentSchema || undefined;
    showInfo(backgroundImportPrompt(activeTable.tableName, route));
    setSqlFileCandidate({
      requestId: ++sqlFileRequestSeqRef.current,
      file,
      connection: selected,
      csvImport: { schemaName: target, tableName: activeTable.tableName }
    });
    setSqlFileFeatureLoaded(true);
    setSqlFileTasksOpen(true);
  }

  async function importRows(file: File) {
    if (!activeTable || !tableData) {
      showInfo('请先打开一张表再导入数据');
      return;
    }
    const route = importRoute(file, pendingChanges.length, MAX_TABLE_CHANGES);
    if (route.kind === 'unsupported') {
      showError(`导入失败：${route.message}`);
      return;
    }
    if (route.kind === 'background') {
      startBackgroundImport(file, route);
      return;
    }
    setTableLoading(true);
    try {
      const text = await file.text();
      const worker = new Worker(new URL('./workers/importWorker.ts', import.meta.url), { type: 'module' });
      const result: ImportResult = await new Promise((resolve, reject) => {
        const cleanup = () => {
          worker.onmessage = null;
          worker.onerror = null;
          worker.onmessageerror = null;
          worker.terminate();
        };
        worker.onmessage = (e) => {
          cleanup();
          e.data.success ? resolve(e.data.result) : reject(new Error(e.data.error || '导入文件解析失败'));
        };
        worker.onerror = (event) => {
          event.preventDefault();
          cleanup();
          reject(new Error(event.message || '导入解析服务加载失败'));
        };
        worker.onmessageerror = () => {
          cleanup();
          reject(new Error('导入解析结果无法读取'));
        };
        worker.postMessage({
          text,
          filename: file.name,
          tableName: activeTable.tableName,
          columns: tableData.columns.map((column) => column.name)
        });
      });
      if (result.rows.length + pendingChanges.length > MAX_TABLE_CHANGES) {
        // 行数只有解析完才知道。CSV 还有后台这条路，不该让用户拿着文件无处可去。
        const fallback = oversizedRowsRoute(file, result.rows.length + pendingChanges.length, MAX_TABLE_CHANGES);
        if (fallback.kind !== 'background') throw new Error(fallback.message);
        startBackgroundImport(file, fallback);
        return;
      }
      const importedRows = result.rows.map((values, index) => ({
        id: `import-${Date.now()}-${index}`,
        values,
        touchedColumns: Object.keys(values),
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

  const deleteRow = useCallback((rowId: string) => {
    setTableRows((rows) => rows.flatMap((row) => {
      if (row.id !== rowId) return [row];
      return row.inserted ? [] : [{ ...row, deleted: !row.deleted }];
    }));
    setPreviewSql([]);
  }, []);

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
    const productionConfirmation = await requestProductionConfirmation('提交表数据变更');
    if (selected.environment === 'prod' && !productionConfirmation) return;
    setTableLoading(true);
    try {
      const data = await api<{ sql: string[]; affectedRows: number }>('/data/commit', {
        method: 'POST',
        headers: productionConfirmationHeaders(productionConfirmation),
        body: JSON.stringify(dataChangePayload())
      });
      setPreviewSql(data.sql);
      showSuccess(`已提交，影响 ${data.affectedRows} 行`);
      resetTableRowCount();
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
        scheduleZone: form.scheduleZone,
        enabled: form.enabled,
        retentionDays: form.retentionDays,
        retentionCount: form.retentionCount,
        storageProfileId: form.storageProfileId
      };
      const task = id
        ? await api<BackupTask>(`/backups/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
        : await api<BackupTask>('/backups', { method: 'POST', body: JSON.stringify(payload) });
      showSuccess(id ? `已更新备份任务：${task.name}` : `已创建备份任务：${task.name}`);
      await refreshBackups(selected, id ? backupTaskPage : 0);
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
      await refreshBackups(selected, backupTaskPage);
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
      await refreshBackups(selected, backups.length === 1 && backupTaskPage > 0 ? backupTaskPage - 1 : backupTaskPage);
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
      const result = await api<BackupRunResponse>(`/backups/${id}/run`, { method: 'POST' });
      showSuccess(localizeMessage(result.execution.message || '备份任务已进入后台队列'));
      setBackups((current) => current.map((task) => task.id === id ? result.task : task));
    } catch (e) {
      showError(`备份执行失败：${localizeMessage((e as Error).message)}`);
      await refreshBackups(selected);
    } finally {
      setBackupLoading(false);
    }
  }

  function downloadBackup(id: number) {
    downloadFromUrl(`${API}/backups/${id}/download`);
    showInfo('已交由浏览器流式下载最近备份文件');
  }

  async function loadBackupHistory(id: number, pageNumber: number, pageSize: number) {
    try {
      return await api<BackupHistoryPage>(`/backups/${id}/history?page=${pageNumber}&pageSize=${pageSize}`);
    } catch (e) {
      showError(`加载备份历史失败：${localizeMessage((e as Error).message)}`);
      throw e;
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

  async function cancelBackupHistory(taskId: number, historyId: number) {
    try {
      await api<BackupHistory>(`/backups/${taskId}/history/${historyId}/cancel`, { method: 'POST' });
      showSuccess('已发送取消备份请求');
      await refreshBackups(selected);
    } catch (e) {
      showError(`取消备份失败：${localizeMessage((e as Error).message)}`);
      throw e;
    }
  }

  function downloadBackupHistory(taskId: number, historyId: number) {
    downloadFromUrl(`${API}/backups/${taskId}/history/${historyId}/download`);
    showInfo('已交由浏览器流式下载历史备份文件');
  }

  const sqlStatus = useMemo<WorkspaceStatus>(() => sqlLoading
    ? { kind: 'loading', text: '正在执行 SQL…' }
    : sqlStatusFromTab(activeSqlTab), [activeSqlTab, sqlLoading]);
  const tableStatus = useMemo<WorkspaceStatus>(() => tableLoading
    ? { kind: 'loading', text: '正在处理表数据…' }
    : workspaceStatus, [tableLoading, workspaceStatus]);
  const objectStatus = useMemo<WorkspaceStatus>(() => objectDetailLoading
    ? { kind: 'loading', text: '正在加载对象详情…' }
    : workspaceStatus, [objectDetailLoading, workspaceStatus]);
  const explorerActiveObject = useMemo(() => mode === 'object' && activeObjectTarget
    ? { schemaName: activeObjectTarget.schemaName || metadata?.selectedSchema || metadata?.currentSchema, name: activeObjectTarget.name }
    : mode === 'table' && activeTable
      ? { schemaName: activeTable.schemaName || metadata?.selectedSchema || metadata?.currentSchema, name: activeTable.tableName }
      : null, [activeObjectTarget, activeTable, metadata?.currentSchema, metadata?.selectedSchema, mode]);
  const refreshExplorer = useStableEvent(() => requestRefreshDatabaseObjects());
  const closeMobileExplorer = useStableEvent(() => setMobileExplorerOpen(false));
  const openConnectionsFromExplorer = useStableEvent(() => setActiveDrawer('connections'));
  const changeExplorerSchema = useStableEvent((schema: string, kind: ExplorerObjectKind) => {
    clearMetadataSearchTimer();
    setMetadataQuery((current) => ({ ...current, schema }));
    void loadMetadata(selected, {
      schema,
      keyword: kind === 'TABLE' ? metadataQuery.keyword : '',
      page: 0,
      applyKeyword: kind === 'TABLE'
    });
  });
  const changeExplorerKeyword = useStableEvent((keyword: string, kind: ExplorerObjectKind) => queueMetadataSearch(keyword, kind));
  const searchExplorer = useStableEvent((keyword: string, kind: ExplorerObjectKind) => runMetadataSearch(keyword, kind));
  const loadMoreExplorerObjects = useStableEvent(() => {
    if (metadata?.hasMore && !metadataLoading) void loadMetadata(selected, { page: metadata.page + 1, append: true, background: true });
  });
  const loadExplorerObjectStructure = useStableEvent((object: DbObject) => loadObjectStructure(object));
  const prefetchExplorerObjectDetail = useStableEvent((object: DbObject) => prefetchObjectDetail(object));
  const openExplorerObjectDetail = useStableEvent((object: DbObject) => openObjectDetail(object));
  const openExplorerTable = useStableEvent((object: DbObject) => openTable(object));
  const backupExplorerTable = useStableEvent((object: DbObject) => openBackupTaskEditor({ schemaName: object.schemaName, tableName: object.name }));
  const toggleExplorerFromHeader = useStableEvent(() => compactLayout
    ? setMobileExplorerOpen((current) => !current)
    : layoutPreferences.toggleExplorer());
  const selectConnectionFromHeader = useStableEvent((connection: Connection) => selectConnection(connection));
  const refreshConnectionsFromHeader = useStableEvent(() => refreshConnections());
  const openConnectionsFromHeader = useStableEvent(() => setActiveDrawer('connections'));
  const openBackupsFromHeader = useStableEvent(() => setActiveDrawer('backups'));
  const openMcpFromHeader = useStableEvent(() => setActiveDrawer('mcp'));
  const openAuditFromHeader = useStableEvent(() => setActiveDrawer('audit'));
  const openSessionsFromHeader = useStableEvent(() => setActiveDrawer('sessions'));
  const openSchemaDiffFromHeader = useStableEvent(() => setActiveDrawer('schema-diff'));
  const openSchemaDiffScript = useStableEvent((sql: string, title: string) => {
    // 生成的 DDL 不在对比面板里执行：送进 SQL 工作台才会经过生产确认与审计。
    setActiveDrawer(null);
    openSqlInNewTab(sql, title);
  });
  const requestProductionConfirmationEvent = useStableEvent((action: string) => requestProductionConfirmation(action));
  const loadBackupNamespacesEvent = useStableEvent((query: BackupTargetQuery) => loadBackupNamespaces(query));
  const loadBackupTablesEvent = useStableEvent((query: BackupTableTargetQuery) => loadBackupTables(query));
  const previewBackupScheduleEvent = useStableEvent((cron: string) => previewBackupSchedule(cron));
  const saveBackupEvent = useStableEvent((id: number | null, form: BackupTaskForm) => saveBackup(id, form));
  const toggleBackupEvent = useStableEvent((id: number, enabled: boolean) => toggleBackup(id, enabled));
  const deleteBackupEvent = useStableEvent((id: number, deleteFile: boolean) => deleteBackup(id, deleteFile));
  const runBackupEvent = useStableEvent((id: number) => runBackup(id));
  const downloadBackupEvent = useStableEvent((id: number) => downloadBackup(id));
  const loadBackupHistoryEvent = useStableEvent((id: number, page: number, pageSize: number) => loadBackupHistory(id, page, pageSize));
  const loadBackupTaskPageEvent = useStableEvent((page: number) => refreshBackups(selected, page));
  const cancelBackupHistoryEvent = useStableEvent((taskId: number, historyId: number) => cancelBackupHistory(taskId, historyId));
  const deleteBackupHistoryEvent = useStableEvent((taskId: number, historyId: number, deleteFile: boolean) => deleteBackupHistory(taskId, historyId, deleteFile));
  const downloadBackupHistoryEvent = useStableEvent((taskId: number, historyId: number) => downloadBackupHistory(taskId, historyId));
  const toggleThemeFromHeader = useStableEvent(() => layoutPreferences.setThemeMode((current) => current === 'light' ? 'dark' : 'light'));
  const toggleFavoriteConnectionEvent = useStableEvent((connectionId: number) => {
    setFavoriteConnectionIds((current) => {
      const next = current.includes(connectionId) ? current.filter((id) => id !== connectionId) : [...current, connectionId];
      writeFavoriteConnectionIds(next);
      return next;
    });
  });
  const addSqlTabEvent = useStableEvent(() => addSqlTab());
  const closeSqlTabEvent = useStableEvent((tabId: string, liveSql?: string) => closeSqlTab(tabId, liveSql));
  const renameSqlTabEvent = useStableEvent((tabId: string) => renameSqlTab(tabId));
  const duplicateSqlTabEvent = useStableEvent((tabId: string, liveSql?: string) => duplicateSqlTab(tabId, liveSql));
  const changeSqlEvent = useStableEvent((connectionId: number | null, tabId: string, sql: string) => {
    if (sqlSessionConnectionIdRef.current !== connectionId) return;
    updateSqlTab(tabId, { sql });
  });
  const mountSqlEditorEvent = useStableEvent<Parameters<SqlEditorOnMount>, ReturnType<SqlEditorOnMount>>((...args) => handleEditorMount(...args));
  const formatSqlEvent = useStableEvent((liveSql?: string) => formatSql(liveSql));
  const explainSqlEvent = useStableEvent((liveSql?: string) => execute('/sql/explain', undefined, liveSql));
  const executeSqlEvent = useStableEvent((liveSql?: string) => execute('/sql/execute', undefined, liveSql));
  const cancelSqlEvent = useStableEvent(() => cancelSqlExecution());
  const exportSqlEvent = useStableEvent((format: ExportFormat, liveSql?: string) => exportSql(format, liveSql));
  const openSqlHistoryEvent = useStableEvent(() => openSqlHistory());
  const selectSqlFileEvent = useStableEvent((file: File) => selectSqlFile(file));
  const openSqlFileTasksEvent = useStableEvent(() => openSqlFileTasks());
  const openSnippetsEvent = useStableEvent(() => {
    setSnippetDraft(undefined);
    setSnippetsOpen(true);
  });
  const saveSnippetEvent = useStableEvent((sql: string) => {
    setSnippetDraft({ draft: snippetDraftFromSql(sql, selected?.dbType), token: Date.now() });
    setSnippetsOpen(true);
  });
  const insertSnippetEvent = useStableEvent((snippet: SqlSnippet) => {
    const editor = editorRef.current;
    const currentSql = editor?.getValue() ?? activeSqlTab.sql;
    const nextSql = appendSnippetToSql(currentSql, snippet.sql);
    editor?.setValue(nextSql);
    updateActiveSqlTab({ sql: nextSql, dirty: true, message: `已插入片段「${snippet.name}」`, statusKind: 'info' });
  });
  const changeSqlResultTabEvent = useStableEvent((key: string) => updateActiveSqlTab({ activeResultKey: key }));
  const changeSqlResultPageEvent = useStableEvent((result: SqlStatementResult, navigation: SqlPageNavigation) => loadSqlResultPage(result, navigation));
  /** 结果就地编辑的提交：复用表数据的 /data/commit，生产连接照样要确认。 */
  const tableForeignKeys = useMemo(() => foreignKeyColumns(activeTableRelations), [activeTableRelations]);
  const followRelationEvent = useStableEvent((target: RelationTarget, value: unknown) => followRelation(target, value));
  const openRelationEvent = useStableEvent((relation: ObjectRelation, direction: 'imported' | 'exported') => openRelationTarget(relation, direction));
  const beginTransactionEvent = useStableEvent(() => void beginSqlTransaction());
  const finishTransactionEvent = useStableEvent((commit: boolean) => finishSqlTransaction(commit));
  const commitResultEditsEvent = useStableEvent(async (request: ResultEditCommit) => {
    if (!selected) throw new Error('请先选择数据库连接');
    const productionConfirmation = await requestProductionConfirmation('提交查询结果修改');
    if (selected.environment === 'prod' && !productionConfirmation) throw new Error('已取消提交');
    const data = await api<{ sql: string[]; affectedRows: number }>('/data/commit', {
      method: 'POST',
      headers: productionConfirmationHeaders(productionConfirmation),
      body: JSON.stringify({
        connectionId: selected.id,
        schemaName: request.schemaName || undefined,
        tableName: request.tableName,
        changes: request.changes
      })
    });
    showSuccess(`已提交查询结果修改，影响 ${data.affectedRows} 行`);
    // 先把已提交的值写回本地行，再尽力重查：重查失败时表格至少与刚写进库里的值一致。
    applyCommittedResultEdits(request.changes);
    await refreshActiveSqlResult();
  });
  const returnFromTableEvent = useStableEvent(() => confirmDiscardTableChanges(() => setMode('sql'), '返回 SQL 查询工作台'));
  const backupCurrentTableEvent = useStableEvent(() => openBackupTaskEditor());
  const reloadTableEvent = useStableEvent(() => confirmDiscardTableChanges(() => void loadTable(), '重新加载当前表'));
  const changeTablePageEvent = useStableEvent((page: number) => confirmDiscardTableChanges(() => void loadTable(activeTable, { page }), `切换到第 ${page + 1} 页`));
  const changeTablePageSizeEvent = useStableEvent((pageSize: number) => confirmDiscardTableChanges(() => {
    layoutPreferences.setTablePageSize(pageSize);
    void loadTable(activeTable, { page: 0, pageSize, reset: true });
  }, `将每页行数调整为 ${pageSize}`));
  const addTableRowEvent = useStableEvent(() => addRow());
  const importTableRowsEvent = useStableEvent((file: File) => importRows(file));
  const previewTableChangesEvent = useStableEvent(() => previewChanges());
  const discardTableChangesEvent = useStableEvent(() => discardTableChanges());
  const commitTableChangesEvent = useStableEvent(() => commitChanges());
  const countTableRowsEvent = useStableEvent(() => void countTableRows());
  const returnFromObjectEvent = useStableEvent(() => confirmDiscardObjectDesign(() => setMode('sql'), '返回 SQL 查询工作台'));
  const reloadObjectDetailEvent = useStableEvent(() => {
    if (activeObjectTarget) void loadObjectDetail(activeObjectTarget, { refresh: true });
  });
  const closeSqlHistoryEvent = useStableEvent(() => sqlHistoryState.setOpen(false));
  const searchSqlHistoryEvent = useStableEvent((keyword: string) => {
    void sqlHistoryState.load({ keyword, limit: INITIAL_SQL_HISTORY_QUERY.limit });
  });
  const loadMoreSqlHistoryEvent = useStableEvent(() => {
    const limit = nextSqlHistoryLimit(sqlHistoryState.query.limit);
    if (limit === sqlHistoryState.query.limit) return;
    void sqlHistoryState.load({ ...sqlHistoryState.query, limit });
  });
  const pickSqlHistoryEvent = useStableEvent((historyItem: SqlHistory, mode: 'new-tab' | 'replace-current') => {
    if (mode === 'new-tab') {
      if (sqlTabsRef.current.length >= MAX_SQL_TABS) {
        toastApi.warning(`最多同时打开 ${MAX_SQL_TABS} 个 SQL 标签页，请先关闭不需要的标签页。`);
        return;
      }
      const currentId = activeSqlTabIdRef.current;
      const liveSql = editorRef.current?.getValue();
      const nextTabs = sqlTabsRef.current.map((tab) => tab.id === currentId && liveSql !== undefined && liveSql !== tab.sql
        ? { ...tab, sql: liveSql, dirty: true }
        : tab);
      const nextIndex = sqlTabSeqRef.current + 1;
      sqlTabSeqRef.current = nextIndex;
      const historyTab: SqlTab = {
        ...createSqlTab(nextIndex),
        title: `历史 SQL ${nextIndex}`,
        sql: historyItem.sql,
        dirty: true,
        message: '已从执行历史打开，原标签草稿保持不变。',
        statusKind: 'info'
      };
      setSqlTabs([...nextTabs, historyTab]);
      setActiveSqlTabId(historyTab.id);
      sqlHistoryState.setOpen(false);
      return;
    }

    const currentTab = sqlTabsRef.current.find((tab) => tab.id === activeSqlTabIdRef.current);
    const currentSql = editorRef.current?.getValue() ?? currentTab?.sql ?? '';
    const replaceCurrent = () => {
      editorRef.current?.setValue(historyItem.sql);
      if (currentTab) updateSqlTab(currentTab.id, { sql: historyItem.sql, dirty: true });
      sqlHistoryState.setOpen(false);
    };
    if (!currentSql.trim() || currentSql === historyItem.sql) {
      replaceCurrent();
      return;
    }
    modalApi.confirm({
      title: '替换当前 SQL 草稿？',
      content: `当前标签“${currentTab?.title || '未命名查询'}”中的内容将被执行历史覆盖。`,
      okText: '确认替换',
      cancelText: '保留当前草稿',
      okButtonProps: { danger: true },
      onOk: replaceCurrent
    });
  });
  const closeSqlFileTasksEvent = useStableEvent(() => {
    setSqlFileTasksOpen(false);
    setSqlFileCandidate(undefined);
  });
  const handleSqlFileMetadataEvent = useStableEvent((connectionId: number) => handleSqlFileMetadataChanged(connectionId));
  const openCreateTableEvent = useStableEvent(() => openCreateTable());
  const renameTableEvent = useStableEvent((object: DbObject) => openTableLifecycleAction('RENAME', object));
  const dropTableEvent = useStableEvent((object: DbObject) => openTableLifecycleAction('DROP', object));
  const closeCreateTableEvent = useStableEvent(() => setTableCreateOpen(false));
  const closeTableLifecycleActionEvent = useStableEvent(() => setTableLifecycleAction(undefined));
  const completeTableLifecycleEvent = useStableEvent((operation: 'CREATE' | 'RENAME' | 'DROP', source: DbObject | null, newTableName?: string) => {
    void tableLifecycleCompleted(operation, source, newTableName);
  });

  const explorerPanel = (
    <ResourceExplorer
      compactLayout={compactLayout}
      selected={selected}
      metadata={metadata}
      metadataQuery={metadataQuery}
      metadataAppliedKeyword={metadataAppliedKeyword}
      metadataLoading={metadataLoading}
      metadataBlockingLoading={metadataBlockingLoading}
      connectionsError={connectionsError}
      structureLoadingKey={structureLoadingKey}
      objects={objects}
      activeObject={explorerActiveObject}
      namespaceLabel={namespaceLabel}
      onRefresh={refreshExplorer}
      onClose={closeMobileExplorer}
      onOpenConnections={openConnectionsFromExplorer}
      onSchemaChange={changeExplorerSchema}
      onKeywordChange={changeExplorerKeyword}
      onSearch={searchExplorer}
      onLoadMore={loadMoreExplorerObjects}
      onLoadStructure={loadExplorerObjectStructure}
      onPrefetchDetail={prefetchExplorerObjectDetail}
      onOpenDetail={openExplorerObjectDetail}
      onOpenTable={openExplorerTable}
      onBackupTable={backupExplorerTable}
      tableLifecycleEnabled={Boolean(selected?.capabilities?.tableDesign && !selected.readonly)}
      requestedView={explorerRequestedView}
      onCreateTable={openCreateTableEvent}
      onRenameTable={renameTableEvent}
      onDropTable={dropTableEvent}
    />
  );

  return (
    <ConfigProvider
      locale={zhCN}
      theme={antThemeConfig}
    >
      {toastContextHolder}
      {modalContextHolder}
      <Modal
        open={Boolean(productionConfirmationRequest)}
        title={productionConfirmationRequest ? `确认在生产连接上${productionConfirmationRequest.action}` : undefined}
        okText="确认执行"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        onOk={confirmProductionConfirmation}
        onCancel={cancelProductionConfirmation}
        destroyOnHidden
      >
        {productionConfirmationRequest && (
          <TypedConfirmationFields
            autoFocus="production"
            production={{
              expected: productionConfirmationRequest.expected,
              value: productionConfirmationInput,
              ariaLabel: '输入生产连接名确认操作',
              onChange: setProductionConfirmationInput
            }}
          />
        )}
      </Modal>
      <div className="app-shell" data-theme={layoutPreferences.themeMode}>
        <AppHeader
          connections={connections}
          favoriteConnectionIds={favoriteConnectionIds}
          selected={selected}
          connectionsLoading={connectionsLoading}
          backgroundTasks={backgroundTasks}
          explorerCollapsed={compactLayout ? !mobileExplorerOpen : layoutPreferences.explorerCollapsed}
          themeMode={layoutPreferences.themeMode}
          onToggleExplorer={toggleExplorerFromHeader}
          onSelectConnection={selectConnectionFromHeader}
          onRefreshConnections={refreshConnectionsFromHeader}
          onOpenConnections={openConnectionsFromHeader}
          onOpenBackups={openBackupsFromHeader}
          onOpenMcp={openMcpFromHeader}
          onOpenAudit={openAuditFromHeader}
          onOpenSessions={openSessionsFromHeader}
          onOpenSchemaDiff={openSchemaDiffFromHeader}
          onToggleTheme={toggleThemeFromHeader}
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
            <Suspense fallback={<div className="workspace-lazy-loading"><Spin /> 正在加载工作区…</div>}>
            {mode === 'sql' && !selected ? (
              <div className="empty-state empty-state-fill">
                <div className="empty-state-content">
                  <Typography.Title level={4}>选择数据库连接后加载 SQL 工作台</Typography.Title>
                  <Text type="secondary">编辑器和数据库元数据会在连接确定后按需加载。</Text>
                  <Button type="primary" onClick={openConnectionsFromHeader}>打开连接管理</Button>
                </div>
              </div>
            ) : mode === 'sql' ? (
              <SqlWorkspace
                key={`${selected?.id ?? 'unselected'}:${sqlSessionRevision}`}
                selected={selected}
                activeSchema={activeSqlSchema}
                namespaceKind={metadata?.namespaceKind}
                sessionConnectionId={selected?.id ?? null}
                tabs={sqlTabs}
                activeTabId={activeSqlTab.id}
                activeTab={activeSqlTab}
                status={sqlStatus}
                loading={sqlLoading}
                cancelling={sqlCancelling}
                cancellable={sqlCancellable}
                historyLoading={sqlHistoryState.loading}
                pagingResultKey={sqlPagingResultKey}
                themeMode={layoutPreferences.themeMode}
                editorSplitRatio={layoutPreferences.editorSplitRatio}
                editorSplitRatioTouched={layoutPreferences.editorSplitRatioTouched}
                onEditorSplitRatioChange={layoutPreferences.setEditorSplitRatio}
                onTabChange={setActiveSqlTabId}
                onTabAdd={addSqlTabEvent}
                onTabClose={closeSqlTabEvent}
                onTabRename={renameSqlTabEvent}
                onTabDuplicate={duplicateSqlTabEvent}
                onSqlChange={changeSqlEvent}
                onEditorMount={mountSqlEditorEvent}
                onFormat={formatSqlEvent}
                onExplain={explainSqlEvent}
                onExecute={executeSqlEvent}
                onCancel={cancelSqlEvent}
                onExport={exportSqlEvent}
                onOpenHistory={openSqlHistoryEvent}
                onSqlFileSelect={selectSqlFileEvent}
                onOpenSqlFileTasks={openSqlFileTasksEvent}
                onOpenSnippets={openSnippetsEvent}
                onSaveSnippet={saveSnippetEvent}
                onResultTabChange={changeSqlResultTabEvent}
                onResultPageChange={changeSqlResultPageEvent}
                onCommitResultEdits={commitResultEditsEvent}
                transactionState={transactionState}
                onBeginTransaction={beginTransactionEvent}
                onFinishTransaction={finishTransactionEvent}
              />
            ) : mode === 'table' ? (
              <TableWorkspace
                activeTable={activeTable}
                tableData={tableData}
                tableRows={tableRows}
                previewSql={previewSql}
                pendingChanges={pendingChanges}
                page={tablePage}
                pageSize={layoutPreferences.tablePageSize}
                hasMore={tableData?.hasMore ?? false}
                rowCount={tableRowCount}
                onCountRows={countTableRowsEvent}
                status={tableStatus}
                loading={tableLoading}
                readonlyConnection={selected?.readonly}
                editingSupported={selected?.capabilities?.tableEdit ?? false}
                onBackToSql={returnFromTableEvent}
                onBackupTable={backupCurrentTableEvent}
                onReload={reloadTableEvent}
                onPageChange={changeTablePageEvent}
                onPageSizeChange={changeTablePageSizeEvent}
                onAddRow={addTableRowEvent}
                onImportFile={importTableRowsEvent}
                onPreview={previewTableChangesEvent}
                onDiscardChanges={discardTableChangesEvent}
                onCommit={commitTableChangesEvent}
                onEdit={editCell}
                onDelete={deleteRow}
                foreignKeys={tableForeignKeys}
                onFollowRelation={followRelationEvent}
              />
            ) : (
              <ObjectDetailWorkspace
                connectionId={selected?.id}
                readonlyConnection={selected?.readonly}
                capabilities={selected?.capabilities}
                productionConfirmationText={selected?.environment === 'prod' ? selected.name : undefined}
                target={activeObjectTarget}
                detail={activeObjectDetail}
                status={objectStatus}
                loading={objectDetailLoading}
                onBackToSql={returnFromObjectEvent}
                onOpenTable={openExplorerTable}
                onReloadDetail={reloadObjectDetailEvent}
                onBackupTable={backupCurrentTableEvent}
                onRenameTable={renameTableEvent}
                onDropTable={dropTableEvent}
                onDesignDirtyChange={updateObjectDesignDirty}
                onOpenRelation={openRelationEvent}
              />
            )}
            </Suspense>
          </main>
        </div>
      </div>

      <Drawer
        title={null}
        size={380}
        open={compactLayout && mobileExplorerOpen}
        closeIcon={null}
        rootClassName="mobile-explorer-drawer"
        onClose={() => setMobileExplorerOpen(false)}
      >
        {explorerPanel}
      </Drawer>

      <Drawer
        title="连接管理"
        size={480}
        open={activeDrawer === 'connections' && connectionEditor.mode === 'closed'}
        rootClassName="management-drawer"
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={openNewConnectionEditor}>新建连接</Button>}
        onClose={closeConnectionDrawer}
      >
        <div className="connection-management-content">
          {activeDrawer === 'connections' && (
            <Suspense fallback={<div className="workspace-lazy-loading"><Spin /> 正在加载连接管理…</div>}>
              <ConnectionList
                connections={connections}
                favoriteConnectionIds={favoriteConnectionIds}
                selectedId={selected?.id}
                connectionsLoading={connectionsLoading}
                connectionsError={connectionsError}
                connectionsReady={connectionsReady}
                testingConnectionId={testingConnectionId}
                onSwitch={(connection) => selectConnection(connection, closeConnectionDrawer)}
                onEdit={editConnection}
                onTest={testSavedConnection}
                onDuplicate={duplicateConnection}
                onDelete={deleteConnection}
                onToggleFavorite={toggleFavoriteConnectionEvent}
              />
            </Suspense>
          )}
        </div>
      </Drawer>

      <Modal
        title={connectionEditor.mode === 'edit'
          ? `编辑连接：${connectionEditor.connectionName}`
          : connectionEditor.mode === 'create' && connectionEditor.origin === 'duplicate'
            ? '复制为新连接'
            : '新建数据库连接'}
        width={640}
        open={connectionEditor.mode !== 'closed'}
        footer={null}
        mask={{ closable: !connectionFormDirty && !connectionActionLoading }}
        closable={!connectionActionLoading}
        onCancel={closeConnectionEditor}
        destroyOnHidden
      >
        {connectionEditor.mode !== 'closed' && (
          <Suspense fallback={<div className="workspace-lazy-loading"><Spin /> 正在加载连接表单…</div>}>
            <ConnectionFormPanel
              form={connectionEditor.form}
              editing={connectionEditor.mode === 'edit'}
              loading={connectionActionLoading}
              onChange={(form) => setConnectionEditor((current) => updateConnectionEditorForm(current, form))}
              onDbTypeChange={changeDbType}
              onCancel={closeConnectionEditor}
              onTest={testConnection}
              onSave={requestSaveConnection}
            />
          </Suspense>
        )}
      </Modal>

      <Drawer
        title="备份与恢复"
        size={960}
        open={activeDrawer === 'backups'}
        rootClassName="management-drawer backup-management-drawer"
        onClose={() => setActiveDrawer(null)}
        destroyOnHidden
      >
        {activeDrawer === 'backups' && (
          <Suspense fallback={<div className="workspace-lazy-loading"><Spin /> 正在加载备份管理…</div>}>
            <BackupPanel
              connections={connections}
              backups={backups}
              taskPage={backupTaskPage}
              taskHasMore={backupTaskHasMore}
              selected={selected}
              activeTable={currentBackupTable}
              loading={backupLoading}
              namespaceKind={metadata?.namespaceKind}
              editorRequest={backupEditorRequest}
              onLoadNamespaces={loadBackupNamespacesEvent}
              onLoadTables={loadBackupTablesEvent}
              onPreviewSchedule={previewBackupScheduleEvent}
              onSave={saveBackupEvent}
              onToggle={toggleBackupEvent}
              onDelete={deleteBackupEvent}
              onRun={runBackupEvent}
              onDownload={downloadBackupEvent}
              onLoadHistory={loadBackupHistoryEvent}
              onLoadTaskPage={loadBackupTaskPageEvent}
              onCancelHistory={cancelBackupHistoryEvent}
              onDeleteHistory={deleteBackupHistoryEvent}
              onDownloadHistory={downloadBackupHistoryEvent}
            />
          </Suspense>
        )}
      </Drawer>

      {snippetsOpen && (
        <Suspense fallback={null}>
          <SqlSnippetDrawer
            open
            dbType={selected?.dbType}
            pendingDraft={snippetDraft}
            onClose={() => setSnippetsOpen(false)}
            onInsert={insertSnippetEvent}
          />
        </Suspense>
      )}
      {objectSearchOpen && (
        <Suspense fallback={null}>
          <ObjectSearchPalette
            open
            connectionId={selected?.id}
            schemaName={activeSqlSchema}
            onClose={() => setObjectSearchOpen(false)}
            onOpenHit={openObjectSearchHit}
          />
        </Suspense>
      )}
      {activeDrawer === 'sessions' && (
        <Suspense fallback={null}>
          <SessionDrawer
            open
            connectionId={selected?.id}
            connectionName={selected?.name}
            productionConfirmationText={selected?.environment === 'prod' ? selected.name : undefined}
            onClose={() => setActiveDrawer(null)}
            onRequestConfirmation={requestProductionConfirmationEvent}
          />
        </Suspense>
      )}
      {activeDrawer === 'audit' && (
        <Suspense fallback={null}>
          <AuditLogDrawer open connections={connections} onClose={() => setActiveDrawer(null)} />
        </Suspense>
      )}
      <Drawer
        title="结构对比与同步"
        size={960}
        open={activeDrawer === 'schema-diff'}
        rootClassName="management-drawer"
        onClose={() => setActiveDrawer(null)}
        destroyOnHidden
      >
        <Suspense fallback={<div className="workspace-lazy-loading"><Spin /> 正在加载结构对比…</div>}>
          {activeDrawer === 'schema-diff' && (
            <SchemaDiffPanel
              connections={connections}
              defaultConnectionId={selected?.id}
              onOpenInSqlTab={openSchemaDiffScript}
            />
          )}
        </Suspense>
      </Drawer>
      <Drawer
        title="MCP Server 设置"
        size={960}
        open={activeDrawer === 'mcp'}
        rootClassName="management-drawer mcp-management-drawer"
        onClose={() => setActiveDrawer(null)}
        destroyOnHidden
      >
        <Suspense fallback={<div className="workspace-lazy-loading"><Spin /> 正在加载 MCP 设置…</div>}>
          {activeDrawer === 'mcp' && <McpSettingsPanel />}
        </Suspense>
      </Drawer>
      {(tableCreateOpen || tableLifecycleAction) && (
        <Suspense fallback={null}>
          <TableLifecyclePanel
            createOpen={tableCreateOpen}
            action={tableLifecycleAction}
            connection={selected}
            schemas={metadata?.schemas || []}
            defaultSchema={metadataQuery.schema || metadata?.selectedSchema || undefined}
            onCloseCreate={closeCreateTableEvent}
            onCloseAction={closeTableLifecycleActionEvent}
            onCompleted={completeTableLifecycleEvent}
          />
        </Suspense>
      )}
      {sqlHistoryState.featureLoaded && (
        <Suspense fallback={null}>
          <SqlHistoryDrawer
            open={sqlHistoryState.open}
            history={sqlHistoryState.rows}
            keyword={sqlHistoryState.query.keyword}
            loading={sqlHistoryState.loading}
            hasMore={hasMoreSqlHistory(sqlHistoryState.rows.length, sqlHistoryState.query.limit)}
            atLimit={isSqlHistoryAtLimit(sqlHistoryState.rows.length, sqlHistoryState.query.limit)}
            onClose={closeSqlHistoryEvent}
            onPick={pickSqlHistoryEvent}
            onSearch={searchSqlHistoryEvent}
            onLoadMore={loadMoreSqlHistoryEvent}
          />
        </Suspense>
      )}
      {sqlFileFeatureLoaded && (
        <Suspense fallback={null}>
          <SqlFileExecutionDrawer
            open={sqlFileTasksOpen}
            candidate={sqlFileCandidate}
            connections={connections}
            selected={selected}
            onClose={closeSqlFileTasksEvent}
            onMetadataChanged={handleSqlFileMetadataEvent}
          />
        </Suspense>
      )}
    </ConfigProvider>
  );
}

function sameTable(
  left: { schemaName?: string; tableName?: string; name?: string },
  right: { schemaName?: string; tableName?: string; name?: string }
) {
  return (left.schemaName || '') === (right.schemaName || '')
    && (left.tableName || left.name) === (right.tableName || right.name);
}

function sqlStatusFromTab(tab: SqlTab): WorkspaceStatus {
  const text = tab.message || '就绪';
  if (tab.statusKind === 'error' || tab.results.some((result) => result.status === 'FAILED')) {
    return { kind: 'error', text };
  }
  return tab.message ? { kind: tab.statusKind || 'info', text } : { kind: 'idle', text };
}
