import { lazy, memo, Suspense, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Dropdown, Empty, Layout, Modal, Popover, Select, Space, Tabs, Tooltip, Typography } from 'antd';
import { BookOutlined, BranchesOutlined, BulbOutlined, CheckOutlined, CloseCircleFilled, CopyOutlined, DownloadOutlined, DownOutlined, FileTextOutlined, FormatPainterOutlined, FullscreenExitOutlined, FullscreenOutlined, FundProjectionScreenOutlined, HistoryOutlined, InfoCircleOutlined, MoreOutlined, PlayCircleOutlined, ProfileOutlined, SaveOutlined, StopOutlined, UpOutlined } from '@ant-design/icons';
import type { MenuProps } from 'antd';
import type { AiExecutionFailure, Connection, ExportFormat, SqlPageNavigation, SqlStatementResult, SqlTab, WorkspaceStatus } from '../types';
import { selectSqlTemplate } from '../sqlTemplates';
import { ResultGrid } from './ResultGrid';
import { PaneResizer } from './PaneResizer';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';
import { SqlEditorSurface } from './SqlEditorSurface';
import { ExplainInsightsPanel } from './ExplainInsightsPanel';
import { explainFindingsText, explainPlanText, type ExplainFinding } from '../explainInsights';
import { chartCandidateText, resultPreviewText } from '../aiResultPreview';
import { nextResultPaneMode, sqlStatementResultLabel, type ResultPaneMode } from '../sqlResultWorkspace';
import { resolveEditorSplitRatio } from '../editorSplit';
import type { ResultEditCommit } from '../resultEditing';
import { transactionBadge, transactionTooltip, type SqlTransactionState } from '../sqlTransaction';
import type { SqlEditorOnMount, SqlEditorProps } from '../sqlEditorTypes';
import { useStableEvent } from '../hooks/useStableEvent';
import { SHORTCUT_HINTS } from '../keyboardShortcuts';
import { hasAnyConnectionPermission, hasConnectionPermission } from '../accessControl';
import type { AiAskRequest } from './AiAssistantPanel';

// AI 抽屉只在真的问了一次之后才需要，懒加载它才不会把这条链拉进 SQL 工作台的首屏预算。
const AiAssistantPanel = lazy(() => import('./AiAssistantPanel').then((module) => ({ default: module.AiAssistantPanel })));
const AiSqlChatPanel = lazy(() => import('./AiSqlChatPanel').then((module) => ({ default: module.AiSqlChatPanel })));

const { Header } = Layout;
const { Text } = Typography;
const MIN_EDITOR_HEIGHT = 120;
const MIN_RESULTS_HEIGHT = 240;
const RESIZER_HEIGHT = 5;
/** 与后端 AiAssistantService.MAX_DOCUMENT_TABLES 一致：再多就该分几次写。 */
const MAX_DOCUMENT_TABLES = 20;

export const SqlWorkspace = memo(function SqlWorkspace({ aiAvailable, aiSampleAllowed, schemaTables, onOpenSqlInNewTab, selected, activeSchema, namespaceKind, sessionConnectionId, tabs, activeTabId, activeTab, status, loading, cancelling, cancellable, historyLoading, pagingResultKey, themeMode, editorSplitRatio, editorSplitRatioTouched, onEditorSplitRatioChange, onTabChange, onTabAdd, onTabClose, onTabRename, onTabDuplicate, onSqlChange, onEditorMount, completionSource, onDefinitionProbe, onDefinitionActivate, onFormat, onExplain, onExecute, onCancel, onExport, onOpenHistory, onSqlFileSelect, onOpenSqlFileTasks, onOpenSnippets, onSaveSnippet, onResultTabChange, onResultPageChange, onCommitResultEdits, transactionState, onBeginTransaction, onFinishTransaction }: {
  /** AI 功能是否对当前连接可用；关掉或未授权时相关入口整个不出现。 */
  aiAvailable: boolean;
  /** 这条连接是否开了样本档：只有开了才允许把查询结果发给模型解读。 */
  aiSampleAllowed: boolean;
  /** 当前命名空间已加载的表名，供「AI 数据字典」挑选。 */
  schemaTables: string[];
  /** AI 生成的 SQL 开在新标签页，不覆盖用户手里正在写的那一条。 */
  onOpenSqlInNewTab: (sql: string, title: string) => void;
  selected: Connection | null;
  activeSchema?: string;
  namespaceKind?: 'SCHEMA' | 'CATALOG';
  sessionConnectionId: number | null;
  tabs: SqlTab[];
  activeTabId: string;
  activeTab: SqlTab;
  status: WorkspaceStatus;
  loading: boolean;
  cancelling: boolean;
  cancellable: boolean;
  historyLoading: boolean;
  pagingResultKey: string | null;
  themeMode: 'light' | 'dark';
  editorSplitRatio: number;
  editorSplitRatioTouched: boolean;
  onEditorSplitRatioChange: (value: number) => void;
  onTabChange: (tabId: string) => void;
  onTabAdd: () => void;
  onTabClose: (tabId: string, liveSql?: string) => void;
  onTabRename: (tabId: string) => void;
  onTabDuplicate: (tabId: string, liveSql?: string) => void;
  onSqlChange: (connectionId: number | null, tabId: string, sql: string) => void;
  onEditorMount: SqlEditorOnMount;
  completionSource: SqlEditorProps['completionSource'];
  onDefinitionProbe: SqlEditorProps['onDefinitionProbe'];
  onDefinitionActivate: SqlEditorProps['onDefinitionActivate'];
  onFormat: (liveSql?: string) => void;
  onExplain: (liveSql?: string) => void;
  onExecute: (liveSql?: string) => void;
  onCancel: () => void;
  onExport: (format: ExportFormat, liveSql?: string) => void;
  onOpenHistory: () => void;
  onSqlFileSelect: (file: File) => void;
  onOpenSqlFileTasks: () => void;
  onOpenSnippets: () => void;
  onSaveSnippet: (sql: string) => void;
  onResultTabChange: (key: string) => void;
  onResultPageChange: (result: SqlStatementResult, navigation: SqlPageNavigation) => void;
  onCommitResultEdits: (request: ResultEditCommit) => Promise<void>;
  transactionState: SqlTransactionState;
  onBeginTransaction: () => void;
  onFinishTransaction: (commit: boolean) => void;
}) {
  const canQuery = Boolean(selected && hasConnectionPermission(selected, 'QUERY'));
  const aiQueryAvailable = aiAvailable && canQuery;
  const canWrite = Boolean(selected && hasConnectionPermission(selected, 'DATA_WRITE'));
  const canExecute = Boolean(selected && hasAnyConnectionPermission(selected, ['QUERY', 'DATA_WRITE', 'DDL']));
  const canExport = canQuery && Boolean(selected && hasConnectionPermission(selected, 'EXPORT'));
  const [draftSql, setDraftSql] = useState(activeTab.sql);
  const [resultPaneMode, setResultPaneMode] = useState<ResultPaneMode>('normal');
  const [aiRequest, setAiRequest] = useState<AiAskRequest>();
  const [aiChatOpen, setAiChatOpen] = useState(false);
  const [aiChatFailure, setAiChatFailure] = useState<AiExecutionFailure>();
  const [aiDocumentOpen, setAiDocumentOpen] = useState(false);
  const [aiDocumentTables, setAiDocumentTables] = useState<string[]>([]);
  const [executionElapsedMs, setExecutionElapsedMs] = useState(0);
  const draftRef = useRef(activeTab.sql);
  const draftCommitTimerRef = useRef<number | null>(null);
  const previousResultsRef = useRef(activeTab.results);
  const sqlFileInputRef = useRef<HTMLInputElement>(null);
  const onSqlChangeRef = useRef(onSqlChange);
  const onResultPageChangeRef = useRef(onResultPageChange);
  const { elementRef: splitRef, height: splitHeight } = useVisibleElementHeight();

  useEffect(() => {
    if (!loading) {
      setExecutionElapsedMs(0);
      return;
    }
    const startedAt = performance.now();
    const updateElapsed = () => setExecutionElapsedMs(performance.now() - startedAt);
    updateElapsed();
    const timer = window.setInterval(updateElapsed, 250);
    return () => window.clearInterval(timer);
  }, [loading]);

  useEffect(() => {
    onSqlChangeRef.current = onSqlChange;
  }, [onSqlChange]);

  useEffect(() => {
    onResultPageChangeRef.current = onResultPageChange;
  }, [onResultPageChange]);

  const handleResultPageChange = useCallback((result: SqlStatementResult, navigation: SqlPageNavigation) => {
    onResultPageChangeRef.current(result, navigation);
  }, []);

  useEffect(() => {
    draftRef.current = activeTab.sql;
    setDraftSql(activeTab.sql);
  }, [activeTab.sql, activeTabId]);

  useEffect(() => {
    if (activeTab.results.length === 0) {
      setResultPaneMode('normal');
    } else if (previousResultsRef.current !== activeTab.results) {
      setResultPaneMode((current) => nextResultPaneMode(current, 'new-result'));
    }
    previousResultsRef.current = activeTab.results;
  }, [activeTab.results]);

  useEffect(() => () => {
    if (draftCommitTimerRef.current != null) window.clearTimeout(draftCommitTimerRef.current);
    onSqlChangeRef.current(sessionConnectionId, activeTabId, draftRef.current);
  }, [activeTabId, sessionConnectionId]);

  function updateDraft(value: string) {
    draftRef.current = value;
    setDraftSql(value);
    if (draftCommitTimerRef.current != null) window.clearTimeout(draftCommitTimerRef.current);
    draftCommitTimerRef.current = window.setTimeout(() => {
      draftCommitTimerRef.current = null;
      onSqlChangeRef.current(sessionConnectionId, activeTabId, draftRef.current);
    }, 300);
  }

  function commitDraft() {
    if (draftCommitTimerRef.current != null) {
      window.clearTimeout(draftCommitTimerRef.current);
      draftCommitTimerRef.current = null;
    }
    onSqlChangeRef.current(sessionConnectionId, activeTabId, draftRef.current);
  }

  // A plain function declaration gets a new identity every render, which
  // defeats SqlEditorSurface's memo() even when `value` itself has not
  // changed — e.g. the 250ms elapsed-time ticker below re-renders this
  // component 4x/sec while a query runs, and without a stable identity that
  // used to re-render the editor surface on every tick too.
  const editorChangeEvent = useStableEvent(updateDraft);
  const editorFormatEvent = useStableEvent(() => { commitDraft(); onFormat(draftRef.current); });
  const editorExecuteEvent = useStableEvent(() => { commitDraft(); onExecute(draftRef.current); });

  /**
   * 把失败的这一次交给 AI SQL 助手。
   *
   * 走的是 Agent 那条路而不是单次问答：最常见的报错就是「字段/表不存在」，而报错里提到的名字
   * 本来就是错的 —— 只看这条 SQL 提到的表根本查不出正确名称，得能搜结构、查词典、看历史写法。
   *
   * 用的是编辑器里此刻的 SQL 与结果区显示的报错原文 —— 用户看到的那一屏才是要诊断的东西，
   * 不去 SQL 历史里翻。落进的是当前连接上那段会话，所以「刚才让它生成的这条」有上下文可接。
   */
  function askAiToDiagnose() {
    if (!selected || !activeTab.errorDetail) return;
    setAiChatFailure({
      sql: draftRef.current || activeTab.sql,
      errorMessage: activeTab.errorDetail
    });
    setAiChatOpen(true);
  }

  /**
   * 把这条执行计划交给 AI 解读。
   *
   * 一并发过去的是确定性规则已经算出的结论：模型不该重新判断「这是不是全表扫描」，
   * 它要做的是在这些事实之上解释原因、给出改法。
   */
  const askAiToExplain = useStableEvent((result: SqlStatementResult, findings: ExplainFinding[]) => {
    if (!selected) return;
    const columns = result.result.columns.map((column) => column.label);
    setAiRequest({
      action: 'explain-insight',
      title: 'AI 解读执行计划',
      body: {
        connectionId: selected.id,
        schemaName: activeSchema,
        sql: result.sql,
        plan: explainPlanText(columns, result.result.rows),
        findings: explainFindingsText(findings)
      }
    });
  });

  /**
   * 把这批结果交给 AI 解读。
   *
   * 这是唯一会把真实数据发出去的入口，所以按钮只在连接开了样本档时出现，
   * 后端还会再拦一次。发出去的是截好的前几行，不是整个结果集。
   */
  const askAiToInterpret = useStableEvent((result: SqlStatementResult) => {
    if (!selected) return;
    setAiRequest({
      action: 'interpret',
      title: 'AI 解读查询结果',
      body: {
        connectionId: selected.id,
        schemaName: activeSchema,
        sql: result.sql,
        preview: resultPreviewText(result.result.columns, result.result.rows),
        chartCandidates: chartCandidateText(result.result.columns, result.result.rows)
      }
    });
  });

  function askAiToDocument() {
    if (!selected || aiDocumentTables.length === 0) return;
    setAiDocumentOpen(false);
    setAiRequest({
      action: 'document',
      title: 'AI 生成数据字典',
      body: { connectionId: selected.id, schemaName: activeSchema, tables: aiDocumentTables }
    });
  }

  /**
   * AI 给的 SQL 只落到编辑器里，执行与否由用户决定。
   *
   * 诊断的结果追加到当前标签页（用户正对着这条 SQL 改），生成的结果开新标签页
   * （手里那条还没写完，不该被覆盖）。
   */
  function insertAiSql(sql: string) {
    if (aiRequest?.action === 'generate') {
      onOpenSqlInNewTab(sql, 'AI 生成的 SQL');
    } else {
      updateDraft(`${draftRef.current}${draftRef.current.trim() ? '\n\n' : ''}${sql}`);
      commitDraft();
    }
    setAiRequest(undefined);
  }

  function appendSelectTemplate() {
    const template = selectSqlTemplate(selected?.dbType);
    updateDraft(`${draftRef.current}${draftRef.current.trim() ? '\n\n' : ''}${template}`);
  }

  const activeResultKey = activeTab.activeResultKey || (activeTab.results[0] ? statementResultKey(activeTab.results[0]) : undefined);
  const activeResult = activeTab.results.find((result) => statementResultKey(result) === activeResultKey) || activeTab.results[0];
  const resultItems = useMemo(() => activeTab.results.map((result) => {
    const resultKey = statementResultKey(result);
    return {
      key: resultKey,
      label: sqlStatementResultLabel(result),
      children: (
        <StatementResultPanel
          result={result}
          selectedConnectionId={selected?.id}
          dbType={selected?.dbType}
          active={activeResultKey === resultKey}
          pagingLoading={pagingResultKey === `${activeTab.id}:${resultKey}`}
          paneMode={resultPaneMode}
          showIdentity={false}
          onPaneModeChange={setResultPaneMode}
          onPageChange={handleResultPageChange}
          connectionId={selected?.id}
          onCommitEdits={canWrite ? onCommitResultEdits : undefined}
          onAskAiExplain={aiQueryAvailable ? askAiToExplain : undefined}
          onAskAiInterpret={aiSampleAllowed && canQuery ? askAiToInterpret : undefined}
        />
      )
    };
  }), [activeResultKey, activeTab.id, activeTab.results, aiQueryAvailable, aiSampleAllowed, askAiToExplain, askAiToInterpret, canQuery, canWrite, handleResultPageChange, onCommitResultEdits, pagingResultKey, resultPaneMode, selected?.dbType, selected?.id]);
  // 用户拖过分隔条就完全听用户的；没拖过时按「有没有结果 + SQL 有多少行」推算，
  // 而不是无论内容如何都给编辑器固定的一半。
  const preferredSplitRatio = resolveEditorSplitRatio({
    touched: editorSplitRatioTouched,
    storedRatio: editorSplitRatio,
    hasResults: activeTab.results.length > 0,
    sql: draftSql,
    containerHeight: splitHeight
  });
  const splitLimits = editorSplitLimits(splitHeight, preferredSplitRatio);
  const moreMenu: MenuProps = {
    items: [
      { key: 'sql-file', icon: <FileTextOutlined />, label: '执行本地 SQL 文件', disabled: !canExecute },
      { key: 'sql-file-tasks', icon: <ProfileOutlined />, label: '查看 SQL 文件任务', disabled: !canExecute },
      { type: 'divider' },
      { key: 'snippets', icon: <BookOutlined />, label: 'SQL 片段库' },
      { key: 'save-snippet', icon: <SaveOutlined />, label: '把当前 SQL 保存为片段', disabled: !draftSql.trim() },
      { type: 'divider' },
      {
        key: 'export',
        icon: <DownloadOutlined />,
        label: '重新查询并导出',
        disabled: !canExport || loading,
        children: [
          { key: 'export:csv', label: '重新查询并导出 CSV' },
          { key: 'export:json', label: '重新查询并导出 JSON' },
          { key: 'export:sql', label: '重新查询并导出 SQL' },
          { key: 'export:xml', label: '重新查询并导出 XML' },
          { key: 'export:xlsx', label: '重新查询并导出 Excel' }
        ]
      },
      {
        key: 'explain',
        icon: <FundProjectionScreenOutlined />,
        label: '查看执行计划',
        disabled: !canQuery || loading || !selected?.capabilities?.explain
      },
      ...(aiQueryAvailable ? [
        { type: 'divider' as const },
        {
          key: 'ai-document',
          icon: <BulbOutlined />,
          label: 'AI 生成数据字典',
          disabled: schemaTables.length === 0
        }
      ] : []),
      { type: 'divider' },
      { key: 'rename-tab', label: '重命名当前标签' },
      { key: 'duplicate-tab', label: '复制当前标签' }
    ],
    onClick: ({ key }) => {
      if (key === 'sql-file') {
        sqlFileInputRef.current?.click();
        return;
      }
      if (key === 'sql-file-tasks') {
        onOpenSqlFileTasks();
        return;
      }
      if (key === 'snippets') {
        onOpenSnippets();
        return;
      }
      if (key === 'save-snippet') {
        commitDraft();
        onSaveSnippet(draftRef.current);
        return;
      }
      if (key === 'explain') {
        commitDraft();
        onExplain(draftRef.current);
        return;
      }
      if (key === 'ai-document') {
        setAiDocumentTables(schemaTables.slice(0, MAX_DOCUMENT_TABLES));
        setAiDocumentOpen(true);
        return;
      }
      if (key === 'rename-tab') {
        onTabRename(activeTabId);
        return;
      }
      if (key === 'duplicate-tab') {
        commitDraft();
        onTabDuplicate(activeTabId, draftRef.current);
        return;
      }
      if (key.startsWith('export:')) {
        commitDraft();
        onExport(key.slice('export:'.length) as ExportFormat, draftRef.current);
      }
    }
  };

  return (
    <div className={`workspace sql-workspace${selected?.readonly ? ' is-readonly' : ''}`}>
      <Header className="workspace-toolbar">
        <Tooltip title={selected?.jdbcUrl} placement="bottomLeft">
          <div className="toolbar-title sql-workspace-title">
            <Text strong>SQL 工作台</Text>
            <Text type="secondary" className="ellipsis-text">
              {selected ? `${selected.name} · ${namespaceKind === 'CATALOG' ? '数据库' : 'Schema'} ${activeSchema || '连接默认值'}` : '请先选择数据库连接'}
              {/* 顶栏和资源管理器已各有一个「只读」标签，这里不再占一整条横幅，只补一句说明。 */}
              {selected?.readonly ? ' · 只读连接，写入和 DDL 会被拒绝' : ''}
            </Text>
          </div>
        </Tooltip>
        <div className="sql-toolbar-actions">
          <Space size={4} className="sql-toolbar-group">
            <Tooltip title="格式化 SQL（Ctrl/Cmd+Shift+F）">
              <Button className="sql-toolbar-button" size="small" icon={<FormatPainterOutlined />} aria-label="格式化当前 SQL 语句" disabled={loading} onClick={() => { commitDraft(); onFormat(draftRef.current); }}>
                <span className="sql-toolbar-label">格式化</span>
              </Button>
            </Tooltip>
            {aiQueryAvailable && (
              <Tooltip title="用自然语言描述需求，AI 会查找表、字段、注释和外键，并支持继续对话修正">
                <Button
                  className="sql-toolbar-button"
                  size="small"
                  icon={<BulbOutlined />}
                  aria-label="用自然语言生成 SQL"
                  onClick={() => setAiChatOpen(true)}
                >
                  <span className="sql-toolbar-label">AI 生成</span>
                </Button>
              </Tooltip>
            )}
            <Tooltip title="SQL 历史">
              <Button className="sql-toolbar-button" size="small" icon={<HistoryOutlined />} aria-label="查看 SQL 历史" disabled={!canQuery || historyLoading} loading={historyLoading} onClick={onOpenHistory}>
                <span className="sql-toolbar-label">历史</span>
              </Button>
            </Tooltip>
            <Dropdown trigger={['click']} menu={moreMenu}>
              <Button className="sql-toolbar-button" size="small" icon={<MoreOutlined />} aria-label="更多 SQL 操作">
                <span className="sql-toolbar-label">更多</span>
              </Button>
            </Dropdown>
          </Space>
          <Space size={4} className="sql-toolbar-group sql-toolbar-transaction-group">
            <Tooltip title={transactionTooltip(transactionState)}>
              {transactionState.transaction ? (
                <Space.Compact>
                  <Button className="sql-toolbar-button" size="small" disabled>
                    <span className="sql-transaction-badge">{transactionBadge(transactionState)}</span>
                  </Button>
                  <Button
                    className="sql-toolbar-button"
                    size="small"
                    loading={transactionState.pending}
                    onClick={() => onFinishTransaction(false)}
                  >
                    回滚
                  </Button>
                  <Button
                    className="sql-toolbar-button"
                    size="small"
                    type="primary"
                    loading={transactionState.pending}
                    onClick={() => onFinishTransaction(true)}
                  >
                    提交
                  </Button>
                </Space.Compact>
              ) : (
                <Button
                  className="sql-toolbar-button"
                  size="small"
                  icon={<BranchesOutlined />}
                  disabled={!canWrite || selected?.readonly || loading}
                  loading={transactionState.pending}
                  onClick={onBeginTransaction}
                >
                  <span className="sql-toolbar-label">手动事务</span>
                </Button>
              )}
            </Tooltip>
          </Space>
          <Space size={4} className="sql-toolbar-group sql-toolbar-execution-group">
            <Tooltip title={loading && cancellable ? '请求数据库取消当前 SQL' : '执行当前或选中 SQL（Ctrl/Cmd+Enter）'}>
              {loading && cancellable ? (
                <Button className="sql-toolbar-button sql-execute-button" size="small" danger icon={<StopOutlined />} aria-label="取消执行 SQL" loading={cancelling} onClick={onCancel}>
                  <span className="sql-toolbar-label">{cancelling ? '正在取消' : `取消 ${formatElapsed(executionElapsedMs)}`}</span>
                </Button>
              ) : loading ? (
                <Button className="sql-toolbar-button sql-execute-button" size="small" type="primary" aria-label="SQL 处理中" loading disabled>
                  <span className="sql-toolbar-label">处理中 {formatElapsed(executionElapsedMs)}</span>
                </Button>
              ) : (
                <Button className="sql-toolbar-button sql-execute-button" size="small" type="primary" danger={selected?.environment === 'prod'} icon={<PlayCircleOutlined />} aria-label={selected?.environment === 'prod' ? '在生产环境执行当前或选中 SQL' : '执行当前或选中 SQL'} disabled={!canExecute} onClick={() => { commitDraft(); onExecute(draftRef.current); }}>
                  <span className="sql-toolbar-label">{selected?.environment === 'prod' ? '生产执行' : '执行'}</span>
                </Button>
              )}
            </Tooltip>
          </Space>
          <input
            ref={sqlFileInputRef}
            type="file"
            accept=".sql"
            hidden
            onChange={(event) => {
              const file = event.currentTarget.files?.[0];
              if (file) onSqlFileSelect(file);
              event.currentTarget.value = '';
            }}
          />
        </div>
      </Header>
      <Tabs
        className="sql-tabs"
        type="editable-card"
        activeKey={activeTabId}
        onChange={(tabId) => { commitDraft(); onTabChange(tabId); }}
        onEdit={(targetKey, action) => {
          if (action === 'add') {
            commitDraft();
            onTabAdd();
          } else {
            const tabId = String(targetKey);
            if (tabId === activeTabId) commitDraft();
            onTabClose(tabId, tabId === activeTabId ? draftRef.current : undefined);
          }
        }}
        hideAdd={false}
        addIcon={<Tooltip title={`新建 SQL 标签页（${SHORTCUT_HINTS.newSqlTab}），${SHORTCUT_HINTS.selectSqlTab} 切换标签页`}><span aria-hidden="true">+</span></Tooltip>}
        items={tabs.map((tab) => ({
          key: tab.id,
          label: (
            <span onDoubleClick={(event) => { event.preventDefault(); event.stopPropagation(); onTabRename(tab.id); }}>
              {tab.dirty && <span aria-label="包含会话草稿">● </span>}
              {tab.title}
            </span>
          ),
          closable: tabs.length > 1
        }))}
      />
      <div ref={splitRef} className="sql-split" id="sql-split-workspace">
        {resultPaneMode !== 'maximized' && <div className={`editor${resultPaneMode === 'collapsed' ? ' editor-with-collapsed-results' : ''}`} style={resultPaneMode === 'normal' ? { flexBasis: `${splitLimits.value * 100}%` } : undefined}>
          <SqlEditorSurface
            value={draftSql}
            themeMode={themeMode}
            executeDisabled={!canExecute || loading}
            onMount={onEditorMount}
            completionSource={completionSource}
            onDefinitionProbe={onDefinitionProbe}
            onDefinitionActivate={onDefinitionActivate}
            onChange={editorChangeEvent}
            onFormat={editorFormatEvent}
            onExecute={editorExecuteEvent}
          />
        </div>}
        {resultPaneMode === 'normal' && <PaneResizer
          direction="vertical"
          unit="ratio"
          commitOnRelease
          value={splitLimits.value}
          min={splitLimits.min}
          max={splitLimits.max}
          disabled={splitLimits.max - splitLimits.min < 0.01}
          ariaLabel="调整 SQL 编辑器和结果区高度"
          controlsId="sql-split-workspace"
          onChange={onEditorSplitRatioChange}
        />}
        <div className={`sql-results-pane is-${resultPaneMode}`}>
          {/* 上一次的结果还在时，失败必须以横幅形式压在结果上方：这是最常见的场景
              （跑一次、改一版、再跑一次失败），此前失败只写进底部状态栏，而结果区
              还显示着上一次的数据，看起来像执行成功了。 */}
          {activeTab.errorDetail && activeTab.results.length > 0 && resultPaneMode !== 'collapsed' && (
            <SqlExecutionErrorBanner
              detail={activeTab.errorDetail}
              retryDisabled={!canExecute || loading || !draftSql.trim()}
              onRetry={() => { commitDraft(); onExecute(draftRef.current); }}
              onDiagnose={aiQueryAvailable ? askAiToDiagnose : undefined}
            />
          )}
          {resultPaneMode === 'collapsed' && activeResult ? (
            <CollapsedResultHeader result={activeResult} paneMode={resultPaneMode} onPaneModeChange={setResultPaneMode} />
          ) : activeTab.results.length === 1 ? (
            <div className="single-result-panel">
              <StatementResultPanel result={activeTab.results[0]} selectedConnectionId={selected?.id} dbType={selected?.dbType} active pagingLoading={pagingResultKey === `${activeTab.id}:${statementResultKey(activeTab.results[0])}`} paneMode={resultPaneMode} showIdentity onPaneModeChange={setResultPaneMode} onPageChange={handleResultPageChange} connectionId={selected?.id} onCommitEdits={canWrite ? onCommitResultEdits : undefined} onAskAiExplain={aiQueryAvailable ? askAiToExplain : undefined} onAskAiInterpret={aiSampleAllowed && canQuery ? askAiToInterpret : undefined} />
            </div>
          ) : resultItems.length > 1 ? (
            <Tabs className="result-tabs" activeKey={activeResultKey} onChange={onResultTabChange} items={resultItems} />
          ) : activeTab.errorDetail ? (
            <SqlExecutionErrorPanel
              detail={activeTab.errorDetail}
              retryDisabled={!canExecute || loading || !draftSql.trim()}
              onRetry={() => { commitDraft(); onExecute(draftRef.current); }}
              onDiagnose={aiQueryAvailable ? askAiToDiagnose : undefined}
            />
          ) : (
            <div className="sql-result-empty-state">
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚无执行结果" />
              <Text type="secondary">选中 SQL 后按 Ctrl/Cmd+Enter，或直接执行光标所在语句。</Text>
              <Space wrap>
                <Button type="primary" icon={<PlayCircleOutlined />} danger={selected?.environment === 'prod'} disabled={!canExecute || !draftSql.trim()} onClick={() => { commitDraft(); onExecute(draftRef.current); }}>
                  {selected?.environment === 'prod' ? '在生产环境执行' : '执行当前 SQL'}
                </Button>
                <Button icon={<HistoryOutlined />} disabled={!canQuery} onClick={onOpenHistory}>查看历史</Button>
                <Button icon={<FileTextOutlined />} onClick={appendSelectTemplate}>追加 SELECT 模板</Button>
                <Button icon={<BookOutlined />} onClick={onOpenSnippets}>SQL 片段库</Button>
              </Space>
            </div>
          )}
        </div>
      </div>
      <WorkspaceStatusBar status={status} trailing={<Text type="secondary">{loading ? `${cancelling ? '正在取消' : '执行中'} · ${formatElapsed(executionElapsedMs)} · ` : ''}{tabs.length} 个查询标签</Text>} />
      <Modal
        open={aiDocumentOpen}
        title="AI 生成数据字典"
        okText="生成"
        cancelText="取消"
        okButtonProps={{ disabled: aiDocumentTables.length === 0 }}
        onOk={askAiToDocument}
        onCancel={() => setAiDocumentOpen(false)}
      >
        <Select
          mode="multiple"
          className="full-width"
          value={aiDocumentTables}
          maxTagCount={8}
          placeholder="选择要写进文档的表"
          options={schemaTables.map((name) => ({ value: name, label: name }))}
          onChange={(value) => setAiDocumentTables(value.slice(0, MAX_DOCUMENT_TABLES))}
        />
        <Text type="secondary">
          一次最多 {MAX_DOCUMENT_TABLES} 张表。只发送表结构与注释，不发送任何行数据；同一条连接上同时只允许一个文档任务。
        </Text>
      </Modal>
      {aiRequest && (
        <Suspense fallback={null}>
          <AiAssistantPanel request={aiRequest} onClose={() => setAiRequest(undefined)} onInsertSql={insertAiSql} />
        </Suspense>
      )}
      {aiChatOpen && selected && (
        <Suspense fallback={null}>
          <AiSqlChatPanel
            open
            connectionId={selected.id}
            schemaName={activeSchema}
            currentSql={draftSql}
            failure={aiChatFailure}
            onFailureConsumed={() => setAiChatFailure(undefined)}
            onClose={() => { setAiChatOpen(false); setAiChatFailure(undefined); }}
            onInsertSql={onOpenSqlInNewTab}
          />
        </Suspense>
      )}
    </div>
  );
});

/**
 * 整次执行失败时占据结果区。
 *
 * 之前失败只写进底部状态栏那条单行里：驱动原文动辄上百字符（还带着后端自动补的
 * LIMIT/OFFSET），在状态栏里会被截断，也没法选中复制，而结果区还停在「尚无执行结果」，
 * 看起来像什么都没发生。
 */
const SqlExecutionErrorPanel = memo(function SqlExecutionErrorPanel({ detail, retryDisabled, onRetry, onDiagnose }: {
  detail: string;
  retryDisabled: boolean;
  onRetry: () => void;
  /** AI 不可用（功能关着或这条连接未授权）时不传，按钮就不出现。 */
  onDiagnose?: () => void;
}) {
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1_600);
    return () => window.clearTimeout(timer);
  }, [copied]);
  useEffect(() => setCopied(false), [detail]);

  return (
    <div className="sql-result-error-state" role="alert">
      <div className="sql-result-error-heading">
        <CloseCircleFilled className="sql-result-error-icon" />
        <Text strong>SQL 执行失败</Text>
        <Space size={4}>
          <Button
            size="small"
            icon={copied ? <CheckOutlined /> : <CopyOutlined />}
            onClick={() => {
              void navigator.clipboard?.writeText(detail).then(() => setCopied(true)).catch(() => undefined);
            }}
          >
            {copied ? '已复制' : '复制错误'}
          </Button>
          {onDiagnose && <Button size="small" icon={<BulbOutlined />} onClick={onDiagnose}>AI 诊断</Button>}
          <Button size="small" type="primary" icon={<PlayCircleOutlined />} disabled={retryDisabled} onClick={onRetry}>重试</Button>
        </Space>
      </div>
      <pre className="sql-result-error-detail">{detail}</pre>
    </div>
  );
});

/** 失败发生时上一次的结果仍在屏幕上，用横幅明确「下面是上一次的结果」。 */
const SqlExecutionErrorBanner = memo(function SqlExecutionErrorBanner({ detail, retryDisabled, onRetry, onDiagnose }: {
  detail: string;
  retryDisabled: boolean;
  onRetry: () => void;
  onDiagnose?: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  useEffect(() => setExpanded(false), [detail]);

  return (
    <div className="sql-result-error-banner" role="alert">
      <div className="sql-result-error-banner-line">
        <CloseCircleFilled className="sql-result-error-icon" />
        <Text strong>本次执行失败</Text>
        <Text type="secondary" className="ellipsis-text">{detail}</Text>
        <Space size={4}>
          <Button size="small" type="text" onClick={() => setExpanded((current) => !current)}>
            {expanded ? '收起' : '展开'}
          </Button>
          {onDiagnose && <Button size="small" icon={<BulbOutlined />} onClick={onDiagnose}>AI 诊断</Button>}
          <Button size="small" icon={<PlayCircleOutlined />} disabled={retryDisabled} onClick={onRetry}>重试</Button>
        </Space>
      </div>
      {expanded && <pre className="sql-result-error-detail">{detail}</pre>}
      <Text type="secondary" className="sql-result-error-stale">下方仍是上一次执行的结果。</Text>
    </div>
  );
});

const StatementResultPanel = memo(function StatementResultPanel({ result, selectedConnectionId, dbType, active, pagingLoading, paneMode, showIdentity, onPaneModeChange, onPageChange, connectionId, onCommitEdits, onAskAiExplain, onAskAiInterpret }: {
  result: SqlStatementResult;
  selectedConnectionId?: number;
  dbType?: string;
  active: boolean;
  pagingLoading: boolean;
  paneMode: ResultPaneMode;
  showIdentity: boolean;
  onPaneModeChange: (mode: ResultPaneMode) => void;
  onPageChange: (result: SqlStatementResult, navigation: SqlPageNavigation) => void;
  connectionId?: number;
  onCommitEdits?: (request: ResultEditCommit) => Promise<void>;
  /** 把这条执行计划交给 AI 解读；AI 不可用时不传。 */
  onAskAiExplain?: (result: SqlStatementResult, findings: ExplainFinding[]) => void;
  /** 把这批结果交给 AI 解读；连接没开样本档时不传。 */
  onAskAiInterpret?: (result: SqlStatementResult) => void;
}) {
  const rowCount = result.result.resultSet ? result.result.rows.length : 0;
  const pagingEnabled = !result.result.page || selectedConnectionId === result.result.page.connectionId;
  const handlePageChange = useCallback((navigation: SqlPageNavigation) => {
    onPageChange(result, navigation);
  }, [onPageChange, result]);
  return (
    <div className="statement-result-panel">
      <div className="statement-result-meta">
        <div className="statement-result-summary">
          {showIdentity && <Text strong className="statement-result-title">{sqlStatementResultLabel(result)}</Text>}
          <Text type="secondary">
            {result.result.resultSet ? `${rowCount} 行` : `影响 ${result.result.affectedRows} 行`} · {result.result.elapsedMs}ms
            {result.result.truncated ? ' · 结果已截断' : ''}
          </Text>
        </div>
        <div className="statement-result-actions">
          {result.result.page && (
            <Tooltip title="翻页会重新执行原 SQL；未使用 ORDER BY 时结果顺序可能变化。">
              <Button type="text" size="small" icon={<InfoCircleOutlined />} aria-label="查看结果翻页说明" />
            </Tooltip>
          )}
          {onAskAiInterpret && result.result.resultSet && result.result.rows.length > 0 && (
            <Tooltip title="把前几行结果发给模型解读，并推荐合适的图表">
              <Button type="text" size="small" icon={<BulbOutlined />} aria-label="AI 解读查询结果" onClick={() => onAskAiInterpret(result)} />
            </Tooltip>
          )}
          <StatementSqlButton result={result} />
          <ResultPaneModeButtons mode={paneMode} onChange={onPaneModeChange} />
        </div>
      </div>
      {result.status === 'FAILED' ? (
        <Alert type="error" showIcon title={`第 ${result.index} 条 SQL 执行失败`} description={result.errorMessage || '数据库返回未知错误'} />
      ) : (
        <div className="statement-result-content">
          <div className="statement-result-notices">
            {result.result.page && !pagingEnabled && <Alert type="warning" showIcon title="该结果来自其他连接，请切回原连接后再翻页。" />}
            {result.result.resultSet && (
              <ExplainInsightsPanel
                columns={result.result.columns.map((column) => column.label)}
                rows={result.result.rows}
                onAskAi={onAskAiExplain ? (findings) => onAskAiExplain(result, findings) : undefined}
              />
            )}
          </div>
          <ResultGrid result={result.result} fill active={active} pagingLoading={pagingLoading} pagingEnabled={pagingEnabled} dbType={dbType} sourceSql={result.sql} connectionId={connectionId} onPageChange={handlePageChange} onCommitEdits={onCommitEdits} />
        </div>
      )}
    </div>
  );
});

function CollapsedResultHeader({ result, paneMode, onPaneModeChange }: {
  result: SqlStatementResult;
  paneMode: ResultPaneMode;
  onPaneModeChange: (mode: ResultPaneMode) => void;
}) {
  const rowCount = result.result.resultSet ? result.result.rows.length : result.result.affectedRows;
  return (
    <div className="collapsed-result-header">
      <div className="statement-result-summary">
        <Text strong className="statement-result-title">{sqlStatementResultLabel(result)}</Text>
        <Text type="secondary">{result.result.resultSet ? `${rowCount} 行` : `影响 ${rowCount} 行`} · {result.result.elapsedMs}ms</Text>
      </div>
      <div className="statement-result-actions">
        <StatementSqlButton result={result} />
        <ResultPaneModeButtons mode={paneMode} onChange={onPaneModeChange} />
      </div>
    </div>
  );
}

function StatementSqlButton({ result }: { result: SqlStatementResult }) {
  return (
    <Popover
      trigger="click"
      placement="topRight"
      title={`第 ${result.index} 条执行语句`}
      content={<pre className="statement-sql statement-sql-popover">{result.sql}</pre>}
    >
      <Button type="text" size="small" icon={<FileTextOutlined />} aria-label={`查看第 ${result.index} 条执行语句`} />
    </Popover>
  );
}

function ResultPaneModeButtons({ mode, onChange }: {
  mode: ResultPaneMode;
  onChange: (mode: ResultPaneMode) => void;
}) {
  return (
    <Space.Compact size="small" className="result-pane-mode-buttons">
      <Tooltip title={mode === 'collapsed' ? '展开结果区' : '收起结果区'}>
        <Button
          type="text"
          size="small"
          icon={mode === 'collapsed' ? <UpOutlined /> : <DownOutlined />}
          aria-label={mode === 'collapsed' ? '展开结果区' : '收起结果区'}
          onClick={() => onChange(nextResultPaneMode(mode, 'toggle-collapse'))}
        />
      </Tooltip>
      <Tooltip title={mode === 'maximized' ? '恢复分栏' : '最大化结果区'}>
        <Button
          type="text"
          size="small"
          icon={mode === 'maximized' ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
          aria-label={mode === 'maximized' ? '恢复编辑器与结果分栏' : '最大化结果区'}
          onClick={() => onChange(nextResultPaneMode(mode, 'toggle-maximize'))}
        />
      </Tooltip>
    </Space.Compact>
  );
}

function statementResultKey(result: SqlStatementResult) {
  return `statement-${result.index}`;
}

function formatElapsed(elapsedMs: number) {
  if (elapsedMs < 1_000) return `${Math.max(0, Math.round(elapsedMs))}ms`;
  if (elapsedMs < 60_000) return `${(elapsedMs / 1_000).toFixed(1)}s`;
  const minutes = Math.floor(elapsedMs / 60_000);
  const seconds = Math.floor((elapsedMs % 60_000) / 1_000);
  return `${minutes}m ${seconds}s`;
}

function editorSplitLimits(containerHeight: number | undefined, preferredValue: number) {
  if (!containerHeight || containerHeight <= 0) {
    return { min: 0.2, max: 0.8, value: Math.min(0.8, Math.max(0.2, preferredValue)) };
  }

  const min = Math.min(0.45, Math.max(0.2, MIN_EDITOR_HEIGHT / containerHeight));
  const heightLimitedMax = (containerHeight - RESIZER_HEIGHT - MIN_RESULTS_HEIGHT) / containerHeight;
  const max = Math.max(min, Math.min(0.8, heightLimitedMax));
  return { min, max, value: Math.min(max, Math.max(min, preferredValue)) };
}

function useVisibleElementHeight() {
  const elementRef = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState<number>();

  useLayoutEffect(() => {
    const element = elementRef.current;
    if (!element) return;
    let animationFrame = 0;

    const measure = () => {
      animationFrame = 0;
      const bounds = element.getBoundingClientRect();
      if (element.getClientRects().length > 0 && bounds.height > 0) {
        setHeight((current) => current === bounds.height ? current : bounds.height);
      }
    };
    const scheduleMeasure = () => {
      if (animationFrame) cancelAnimationFrame(animationFrame);
      animationFrame = requestAnimationFrame(measure);
    };

    scheduleMeasure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(scheduleMeasure);
    observer?.observe(element);
    window.addEventListener('resize', scheduleMeasure);
    return () => {
      if (animationFrame) cancelAnimationFrame(animationFrame);
      observer?.disconnect();
      window.removeEventListener('resize', scheduleMeasure);
    };
  }, []);

  return { elementRef, height };
}
