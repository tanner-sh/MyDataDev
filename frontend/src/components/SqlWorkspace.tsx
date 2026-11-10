import { lazy, memo, Suspense, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Divider, Dropdown, Empty, Input, Layout, Modal, Popover, Segmented, Select, Space, Tabs, Tag, Tooltip, Typography } from 'antd';
import { BookOutlined, BranchesOutlined, BulbOutlined, CheckOutlined, CloseCircleFilled, CodeOutlined, CopyOutlined, DownOutlined, ExportOutlined, FileTextOutlined, FormatPainterOutlined, FullscreenExitOutlined, FullscreenOutlined, FundProjectionScreenOutlined, HistoryOutlined, InfoCircleOutlined, MoreOutlined, PlayCircleOutlined, ProfileOutlined, SaveOutlined, StopOutlined, UpOutlined, QuestionCircleOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import type { AiExecutionFailure, AiExecutionOutcome, AiExecutionPlan, Connection, ExportFormat, SqlCommandView, SqlPageNavigation, SqlStatementResult, SqlTab, WorkspaceStatus } from '../types';
import { resultShape, resultShapeText } from '../aiResultShape';
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
import { HeaderDock, useHeaderDocked } from './HeaderDock';
import { isMacPlatform, SHORTCUT_HINTS, shortcutKeycap } from '../keyboardShortcuts';
import { hasAnyConnectionPermission, hasConnectionPermission } from '../accessControl';
import type { AiAskRequest } from './AiAssistantPanel';
import { ScriptExecutionLog } from './ScriptExecutionLog';
import { PanelLoading } from './PanelState';
import { commandOptionsLabel, effectiveCommandOptions, isCommandTab, supportsScriptTransaction, type CommandWindowOptions } from '../commandWindow';
import { needsScriptLog, resolveResultTabKey, SCRIPT_LOG_KEY, tabbedResults } from '../scriptExecutionLog';
import { customDelimiterProblem, DELIMITER_MODE_OPTIONS, type SqlDelimiterMode, type SqlDelimiterSetting } from '../scriptDelimiter';

const macPlatform = isMacPlatform();
// 命令窗口只在跑过一次命令之后才出现：懒加载，不进可输入工作台的静态依赖。
const CommandConsole = lazy(() => import('./CommandConsole').then((module) => ({ default: module.CommandConsole })));
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

export const SqlWorkspace = memo(function SqlWorkspace({ draftSaveState, aiAvailable, aiSampleAllowed, schemaTables, onOpenSqlInNewTab, selected, activeSchema, namespaceKind, schemaOptions, onSchemaChange, unavailableReason, sessionConnectionId, tabs, activeTabId, activeTab, status, loading, cancelling, cancellable, historyLoading, pagingResultKey, themeMode, editorSplitRatio, editorSplitRatioTouched, onEditorSplitRatioChange, onOpenInCommandTab, onSqlChange, onEditorMount, completionSource, onResolveUnknownObjects, onDefinitionProbe, onDefinitionActivate, onFormat, onExplain, onExecute, onCancel, onExport, onOpenHistory, onSqlFileSelect, onOpenSqlFileTasks, onOpenSnippets, onSaveSnippet, onResultTabChange, onResultPageChange, onPreviewResultEdits, onCommitResultEdits, transactionState, onBeginTransaction, onFinishTransaction, scriptTransactionMode, scriptEndAction, onScriptTransactionModeChange, onScriptEndActionChange, onDelimiterChange, onExecuteCommand, onCommandOptionsChange, onLocateCommandLines, onCloseCommand, onCommandFinished }: {
  draftSaveState?: string;
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
  /**
   * 这个查询标签可选的 Schema（或 MySQL 的数据库）。每个标签各自记着自己的那一个，
   * 和 DBeaver 一样 —— 以前所有标签都跟着资源管理器走，在一个标签里查 A 库、另一个标签里改 B 库，
   * 来回切标签就得来回改资源管理器。为空表示这种库没有可切的命名空间，不显示选择器。
   */
  schemaOptions?: string[];
  onSchemaChange?: (schema: string) => void;
  /** 这个标签绑定的连接不可用（被删、没权限）时的说明：执行与导出都禁用，提示里写清楚怎么办。 */
  unavailableReason?: string;
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
  /** 新开一个独立的命令窗口标签。 */
  /** 把当前查询窗口里的脚本搬到一个新的命令窗口（当前标签不动）。 */
  onOpenInCommandTab: (sql: string) => void;
  onSqlChange: (connectionId: number | null, tabId: string, sql: string) => void;
  onEditorMount: SqlEditorOnMount;
  completionSource: SqlEditorProps['completionSource'];
  onResolveUnknownObjects: SqlEditorProps['onResolveUnknownObjects'];
  onDefinitionProbe: SqlEditorProps['onDefinitionProbe'];
  onDefinitionActivate: SqlEditorProps['onDefinitionActivate'];
  onFormat: (liveSql?: string) => void;
  onExplain: (liveSql?: string) => void;
  onExecute: (liveSql?: string) => void;
  onCancel: () => void;
  /** 重新查询并导出。exactSql 给定时导出的就是这条（结果区的「重新执行原 SQL 导出」），否则取编辑器的选中或全文。 */
  onExport: (format: ExportFormat, liveSql?: string, exactSql?: string) => void;
  onOpenHistory: () => void;
  onSqlFileSelect: (file: File) => void;
  onOpenSqlFileTasks: () => void;
  onOpenSnippets: () => void;
  onSaveSnippet: (sql: string) => void;
  onResultTabChange: (key: string) => void;
  onResultPageChange: (result: SqlStatementResult, navigation: SqlPageNavigation) => void;
  /** 生成将要执行的语句，提交前给用户确认。 */
  onPreviewResultEdits: (request: ResultEditCommit) => Promise<string[]>;
  onCommitResultEdits: (request: ResultEditCommit) => Promise<void>;
  transactionState: SqlTransactionState;
  scriptTransactionMode: 'AUTO' | 'SCRIPT';
  scriptEndAction: 'COMMIT' | 'ROLLBACK';
  onScriptTransactionModeChange: (mode: 'AUTO' | 'SCRIPT') => void;
  /** 当前标签页的语句分隔方式（分号 / 空行 / 自定义独占行符号），按标签页记。 */
  onDelimiterChange: (delimiter: SqlDelimiterSetting) => void;
  /** 命令窗口：把当前或选中的脚本交给后台逐条执行，结果是滚动日志。 */
  onExecuteCommand: (liveSql?: string) => void;
  onCommandOptionsChange: (options: CommandWindowOptions) => void;
  /** 日志里的行号（相对提交的那段文字）在编辑器里选中。 */
  onLocateCommandLines: (startLine: number, endLine: number) => void;
  onCloseCommand: () => void;
  onCommandFinished: (view: SqlCommandView) => void;
  onScriptEndActionChange: (action: 'COMMIT' | 'ROLLBACK') => void;
  onBeginTransaction: () => void;
  onFinishTransaction: (commit: boolean) => void;
}) {
  const canQuery = Boolean(selected && hasConnectionPermission(selected, 'QUERY'));
  const aiQueryAvailable = aiAvailable && canQuery;
  const canWrite = Boolean(selected && hasConnectionPermission(selected, 'DATA_WRITE'));
  const canExecute = Boolean(selected && !unavailableReason && hasAnyConnectionPermission(selected, ['QUERY', 'DATA_WRITE', 'DDL']));
  const canExport = canQuery && Boolean(selected && hasConnectionPermission(selected, 'EXPORT'));
  const [draftSql, setDraftSql] = useState(activeTab.sql);
  const [resultPaneMode, setResultPaneMode] = useState<ResultPaneMode>('normal');
  const [aiRequest, setAiRequest] = useState<AiAskRequest>();
  const [aiChatOpen, setAiChatOpen] = useState(false);
  const [aiChatFailure, setAiChatFailure] = useState<AiExecutionFailure>();
  const [aiChatOutcome, setAiChatOutcome] = useState<AiExecutionOutcome>();
  const [aiChatPlan, setAiChatPlan] = useState<AiExecutionPlan>();
  const [aiDocumentOpen, setAiDocumentOpen] = useState(false);
  const [executionMenuOpen, setExecutionMenuOpen] = useState(false);
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
  // 命令窗口标签里，执行（含 Ctrl/Cmd+Enter、重试、空状态按钮）一律走后台逐条执行。
  const commandTab = isCommandTab(activeTab);
  function runActive() {
    commitDraft();
    if (commandTab) onExecuteCommand(draftRef.current);
    else onExecute(draftRef.current);
  }
  const editorExecuteEvent = useStableEvent(runActive);

  /**
   * 结果跑通了但看着不对，交给 AI 复盘。
   *
   * 只发形状不发数据：行数、耗时、每列有多少空值和多少个不同取值。查错真正需要的信号本来就是
   * 计数 —— 零行、某列全空、行数爆炸，说的都是关联写错了，不是某一行的值是什么。所以这条路
   * 在「只发结构」档下就能用，不像结果解读那样要求连接开到样本档。
   */
  const askAiToReviewResult = useStableEvent((result: SqlStatementResult) => {
    const shape = resultShape(result.result);
    if (!shape) return;
    setAiChatFailure(undefined);
    setAiChatPlan(undefined);
    setAiChatOutcome({ sql: result.sql, shape: resultShapeText(shape) });
    setAiChatOpen(true);
  });

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
    setAiChatOutcome(undefined);
    setAiChatPlan(undefined);
    setAiChatFailure({
      sql: draftRef.current || activeTab.sql,
      errorMessage: activeTab.errorDetail
    });
    setAiChatOpen(true);
  }

  /**
   * 把这条执行计划交给 AI 解读。
   *
   * 走 Agent 那条路而不是单次问答：只有计划文本的话，模型看不到这张表上真实存在哪些索引，
   * 只能泛泛地说「加个索引」，甚至建议一个已经有了的。进了 Agent 它才能读字段和索引、
   * 看这个库里同类查询怎么写。产出的建索引语句同样只写进编辑器，由人执行。
   *
   * 一并发过去的是确定性规则已经算出的结论：模型不该重新判断「这是不是全表扫描」，
   * 它要做的是在这些事实之上解释原因、给出改法。
   */
  const askAiToExplain = useStableEvent((result: SqlStatementResult, findings: ExplainFinding[]) => {
    if (!selected) return;
    const columns = result.result.columns.map((column) => column.label);
    setAiChatFailure(undefined);
    setAiChatOutcome(undefined);
    setAiChatPlan({
      sql: result.sql,
      plan: explainPlanText(columns, result.result.rows),
      findings: explainFindingsText(findings)
    });
    setAiChatOpen(true);
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

  // 结果区「重新执行原 SQL 导出」：重新执行产生这份结果的那条 SQL，而不是编辑器里此刻的内容 ——
  // 脚本里有多条语句、或者跑完之后又改了编辑器时，两者不是一回事。
  const exportFullResult = useStableEvent((result: SqlStatementResult, format: ExportFormat) => {
    commitDraft();
    onExport(format, draftRef.current, result.sql);
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
   * 单轮问答（解读结果、数据字典、同步脚本审阅）产出的 SQL 一律追加到当前标签页 ——
   * 用户正对着这条 SQL 在看。要开新标签页的是 Agent 抽屉那条路，它直接用
   * {@code onOpenSqlInNewTab}（见下面的 onInsertSql）。
   */
  function insertAiSql(sql: string) {
    updateDraft(`${draftRef.current}${draftRef.current.trim() ? '\n\n' : ''}${sql}`);
    commitDraft();
    setAiRequest(undefined);
  }

  // 多条语句时，DML/DDL 合进「执行日志」一个标签；只有查询结果与失败的那条单独成标签。
  const showScriptLog = needsScriptLog(activeTab.results);
  const tabResults = useMemo(() => showScriptLog ? tabbedResults(activeTab.results) : activeTab.results, [activeTab.results, showScriptLog]);
  const tabKeys = useMemo(() => [...(showScriptLog ? [SCRIPT_LOG_KEY] : []), ...tabResults.map((result) => statementResultKey(result))],
    [showScriptLog, tabResults]);
  const commandOptions = effectiveCommandOptions(activeTab.commandOptions, selected?.dbType);
  const activeResultKey = resolveResultTabKey(activeTab.activeResultKey || (activeTab.results[0] ? statementResultKey(activeTab.results[0]) : undefined), tabKeys);
  const activeResult = activeTab.results.find((result) => statementResultKey(result) === activeResultKey) || tabResults[0] || activeTab.results[0];
  const resultItems = useMemo(() => [...(showScriptLog ? [{
    key: SCRIPT_LOG_KEY,
    label: `执行日志 ${activeTab.results.length}`,
    children: <ScriptExecutionLog results={activeTab.results} notices={activeTab.scriptNotices} />
  }] : []), ...tabResults.map((result) => {
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
          onPreviewEdits={canWrite ? onPreviewResultEdits : undefined}
          onCommitEdits={canWrite ? onCommitResultEdits : undefined}
          onAskAiExplain={aiQueryAvailable ? askAiToExplain : undefined}
          onAskAiInterpret={aiSampleAllowed && canQuery ? askAiToInterpret : undefined}
          onAskAiReview={aiQueryAvailable ? askAiToReviewResult : undefined}
          onExportFull={canExport ? exportFullResult : undefined}
        />
      )
    };
  })], [showScriptLog, tabResults, activeTab.scriptNotices, activeResultKey, activeTab.id, activeTab.results, aiQueryAvailable, aiSampleAllowed, askAiToExplain, askAiToInterpret, canExport, canQuery, canWrite, exportFullResult, handleResultPageChange, onCommitResultEdits, onPreviewResultEdits, pagingResultKey, resultPaneMode, selected?.dbType, selected?.id]);
  // 用户拖过分隔条就完全听用户的；没拖过时按「有没有结果 + SQL 有多少行」推算，
  // 而不是无论内容如何都给编辑器固定的一半。
  const preferredSplitRatio = resolveEditorSplitRatio({
    touched: editorSplitRatioTouched,
    storedRatio: editorSplitRatio,
    hasResults: activeTab.results.length > 0 || Boolean(activeTab.command),
    sql: draftSql,
    containerHeight: splitHeight
  });
  const splitLimits = editorSplitLimits(splitHeight, preferredSplitRatio);
  /*
    「更多」只放编辑之外、执行之外的东西。执行相关的（命令窗口、本地 SQL 文件、文件任务）
    归到执行按钮右侧的下拉里，标签相关的（重命名、复制、找回）归到标签栏 —— 以前命令窗口
    在工具栏、标签栏、「更多」里各有一个入口，找东西靠记忆而不是靠位置。
  */
  const moreMenu: MenuProps = {
    items: [
      { key: 'snippets', icon: <BookOutlined />, label: 'SQL 片段库' },
      { key: 'save-snippet', icon: <SaveOutlined />, label: '把当前 SQL 保存为片段', disabled: !draftSql.trim() },
      // 导出只在结果区有一个入口（当前批次 / 重新执行原 SQL 两组）；这里原来还有一份「重新查询并导出」。
      ...(commandTab ? [] : [{ type: 'divider' as const }, {
        key: 'explain',
        icon: <FundProjectionScreenOutlined />,
        label: '查看执行计划',
        disabled: !canQuery || loading || !selected?.capabilities?.explain
      }]),
      ...(aiQueryAvailable ? [
        { type: 'divider' as const },
        {
          key: 'ai-document',
          icon: <BulbOutlined />,
          label: 'AI 生成数据字典',
          disabled: schemaTables.length === 0
        }
      ] : [])
    ],
    onClick: ({ key }) => {
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
    }
  };


  const delimiterMode = activeTab.delimiter?.mode ?? 'AUTO';
  const delimiterOption = DELIMITER_MODE_OPTIONS.find((option) => option.value === delimiterMode);
  // 窗口级「脚本控制事务」只对 Oracle 系的查询窗口有意义；命令窗口在后台独立连接上跑，不受它影响。
  const windowTransactionAvailable = !commandTab && Boolean(selected && ['oracle', 'oceanbase-oracle'].includes(selected.dbType));
  /*
    这些设置收进了执行下拉，但不是默认值的时候必须在工具栏上看得见：分隔方式改了会让同一段
    脚本切成不同的语句，脚本控制事务会让执行完的写入停在未提交状态 —— 藏起来的非默认设置
    就是下一次「怎么跟我想的不一样」。
  */
  const executionNotices = [
    delimiterMode !== 'AUTO'
      ? `分隔：${delimiterOption?.short ?? delimiterMode}${delimiterMode === 'CUSTOM' && activeTab.delimiter?.token ? ` ${activeTab.delimiter.token}` : ''}`
      : null,
    windowTransactionAvailable && scriptTransactionMode === 'SCRIPT'
      ? `脚本控制事务 · ${scriptEndAction === 'COMMIT' ? '成功后提交' : '结束时回滚'}`
      : null
  ].filter((notice): notice is string => Boolean(notice));

  function runExecutionAction(action: () => void) {
    setExecutionMenuOpen(false);
    action();
  }

  const commandOptionsContent = (
    <div className="command-options">
      <Text type="secondary">遇到错误</Text>
      <Segmented size="small" value={commandOptions.onError} disabled={loading}
        options={[{ value: 'STOP', label: '停止' }, { value: 'CONTINUE', label: '继续执行' }]}
        onChange={(value) => onCommandOptionsChange({ ...commandOptions, onError: value as CommandWindowOptions['onError'] })} />
      <Text type="secondary">事务</Text>
      <Segmented size="small" value={commandOptions.transactionMode} disabled={loading}
        options={[
          { value: 'AUTOCOMMIT', label: '逐条提交' },
          { value: 'SCRIPT', label: '脚本控制', disabled: !supportsScriptTransaction(selected?.dbType) }
        ]}
        onChange={(value) => onCommandOptionsChange({ ...commandOptions, transactionMode: value as CommandWindowOptions['transactionMode'] })} />
      {commandOptions.transactionMode === 'SCRIPT' && <>
        <Text type="secondary">脚本结束时</Text>
        <Segmented size="small" value={commandOptions.endOfFileAction} disabled={loading}
          options={[{ value: 'ROLLBACK', label: '回滚剩余事务' }, { value: 'COMMIT', label: '提交剩余事务' }]}
          onChange={(value) => onCommandOptionsChange({ ...commandOptions, endOfFileAction: value as CommandWindowOptions['endOfFileAction'] })} />
      </>}
      <Text type="secondary" className="command-options-hint">
        {commandOptions.transactionMode === 'SCRIPT'
          ? '按脚本里的 COMMIT / ROLLBACK 走；失败的那条由数据库回滚，事务本身保留。'
          : '每条成功即生效，失败的那条不生效。'}
        脚本里的 WHENEVER SQLERROR CONTINUE / EXIT 会在执行到那一行时改变遇错策略。
      </Text>
    </div>
  );

  /*
    执行按钮右侧的下拉：其它执行方式 + 执行选项。原来这些散在工具栏的三个下拉、
    命令窗口按钮的气泡和「更多」菜单里，常驻工具栏的却是最少改动的那几项。
  */
  const executionMenuContent = (
    <div className="execution-menu">
      <div className="execution-menu-actions">
        {!commandTab && (
          <Button type="text" block icon={<CodeOutlined />} disabled={!canExecute || loading}
            onClick={() => runExecutionAction(() => { commitDraft(); onExecuteCommand(draftRef.current); })}>
            在命令窗口执行
          </Button>
        )}
        {!commandTab && (
          <Button type="text" block icon={<ExportOutlined />} disabled={!draftSql.trim()}
            onClick={() => runExecutionAction(() => { commitDraft(); onOpenInCommandTab(draftRef.current); })}>
            在新命令窗口中打开
          </Button>
        )}
        <Button type="text" block icon={<FileTextOutlined />} disabled={!canExecute}
          onClick={() => runExecutionAction(() => sqlFileInputRef.current?.click())}>
          执行本地 SQL 文件…
        </Button>
        <Button type="text" block icon={<ProfileOutlined />} disabled={!canExecute}
          onClick={() => runExecutionAction(onOpenSqlFileTasks)}>
          查看 SQL 文件任务
        </Button>
      </div>
      <Divider className="execution-menu-divider" />
      <div className="command-options">
        <Text type="secondary">语句分隔</Text>
        <Space size={4}>
          <Segmented size="small" value={delimiterMode} disabled={loading}
            options={DELIMITER_MODE_OPTIONS.map((option) => ({ value: option.value, label: option.short }))}
            onChange={(value) => {
              const mode = value as SqlDelimiterMode;
              onDelimiterChange(mode === 'CUSTOM' ? { mode, token: activeTab.delimiter?.token ?? '!' } : { mode });
            }} />
          {delimiterMode === 'CUSTOM' && (
            <Input size="small" aria-label="自定义分隔符" className="sql-delimiter-token" value={activeTab.delimiter?.token ?? ''}
              disabled={loading}
              status={customDelimiterProblem(activeTab.delimiter?.token ?? '') ? 'error' : undefined}
              title={customDelimiterProblem(activeTab.delimiter?.token ?? '') ?? '独占一行的这个符号就是语句边界'}
              onChange={(event) => onDelimiterChange({ mode: 'CUSTOM', token: event.target.value })} />
          )}
        </Space>
        <Text type="secondary" className="command-options-hint">{delimiterOption?.hint}</Text>
        {windowTransactionAvailable && <>
          <Text type="secondary">窗口事务</Text>
          <Segmented size="small" value={scriptTransactionMode}
            disabled={loading || Boolean(transactionState.transaction) || transactionState.pending}
            options={[{ value: 'AUTO', label: '普通执行' }, { value: 'SCRIPT', label: '脚本控制' }]}
            onChange={(value) => onScriptTransactionModeChange(value as 'AUTO' | 'SCRIPT')} />
          {scriptTransactionMode === 'SCRIPT' && <>
            <Text type="secondary">脚本结束时</Text>
            <Segmented size="small" value={scriptEndAction} disabled={loading}
              options={[{ value: 'ROLLBACK', label: '回滚剩余事务' }, { value: 'COMMIT', label: '成功后提交' }]}
              onChange={(value) => onScriptEndActionChange(value as 'COMMIT' | 'ROLLBACK')} />
          </>}
        </>}
      </div>
      <Divider className="execution-menu-divider" titlePlacement="start" plain>命令窗口</Divider>
      {commandOptionsContent}
    </div>
  );

  const namespaceLabel = namespaceKind === 'CATALOG' ? '数据库' : 'Schema';
  // 宽屏时按钮停靠到顶栏正中间、Schema 停到连接选择器旁边（见 HeaderDock），工作区自己那一行工具栏省掉，
  // 编辑器多出 44px。窄屏顶栏放不下，两样都留在工作区里。
  const docked = useHeaderDocked();
  // 命令窗口在后台的独立连接上按连接默认 Schema 执行，不认这个选择：显示出来就是一个不起作用的开关。
  const schemaSelect = selected && !commandTab && schemaOptions && schemaOptions.length > 0 && onSchemaChange && (
            // 说明放在原生 title 上而不是 Tooltip：气泡会盖住刚展开的下拉列表。
            <span className="sql-schema-select-wrap" title={transactionState.transaction
              ? `手动事务开着时${namespaceLabel}固定为事务开启时的那个，提交或回滚后才能切换。`
              : `这个查询标签在哪个${namespaceLabel}下执行。每个标签各自记着，切换标签或在资源管理器里换${namespaceLabel}都不会影响它。`}>
              <Select
                className="sql-schema-select"
                size="small"
                showSearch
                aria-label={`当前标签的${namespaceLabel}`}
                value={activeSchema}
                placeholder={`${namespaceLabel}：连接默认值`}
                // 事务绑定在开启时的 Schema 上，中途换了也不会生效，只会让界面和实际执行对不上。
                disabled={loading || Boolean(transactionState.transaction)}
                options={schemaOptions.map((schema) => ({ value: schema, label: schema }))}
                labelRender={({ label }) => <span className="sql-schema-select-label"><span className="sql-schema-select-kind">{namespaceLabel}</span>{label}</span>}
                onChange={onSchemaChange}
              />
            </span>
          );
  const toolbarActions = (
        <div className={`sql-toolbar-actions${docked ? ' is-docked' : ''}`}>
          {!docked && schemaSelect}
          <Space size={4} className="sql-toolbar-group">
            <Tooltip title={`格式化 SQL（${SHORTCUT_HINTS.formatSql}）`}>
              <Button className="sql-toolbar-button" size="small" icon={<FormatPainterOutlined />} aria-label="格式化当前 SQL 语句" disabled={loading} onClick={() => { commitDraft(); onFormat(draftRef.current); }}>
                <span className="sql-toolbar-label">格式化</span>
                <kbd className="sql-toolbar-keycap">{shortcutKeycap(SHORTCUT_HINTS.formatSql, macPlatform)}</kbd>
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
            {executionNotices.length > 0 && (
              <Tooltip title="非默认的执行设置，可在执行按钮右侧的下拉里修改">
                <Tag className="sql-execution-notice" variant="filled" onClick={() => setExecutionMenuOpen(true)}>
                  {executionNotices.join(' · ')}
                </Tag>
              </Tooltip>
            )}
            {/* 命令窗口在后台的独立连接上执行，这个窗口的手动事务与它无关，不显示。
                只读连接或没有写权限时也不显示：一个永远点不了的按钮只是占地方。已经开着的事务照常显示，
                否则提交 / 回滚就没了入口。 */}
            {!commandTab && (transactionState.transaction || (canWrite && !selected?.readonly)) && <Tooltip title={transactionTooltip(transactionState)}>
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
                  disabled={loading || scriptTransactionMode === 'SCRIPT'}
                  loading={transactionState.pending}
                  onClick={onBeginTransaction}
                >
                  <span className="sql-toolbar-label">手动事务</span>
                </Button>
              )}
            </Tooltip>}
          </Space>
          <Space size={4} className="sql-toolbar-group sql-toolbar-execution-group">
            <Space.Compact>
              {commandTab ? (
                <Tooltip title={unavailableReason || `交给后台逐条执行选中内容或整段脚本（${SHORTCUT_HINTS.executeSql}），结果是一条滚动日志（${commandOptionsLabel(commandOptions)}）。执行期间可以继续编辑。`}>
                  <Button className="sql-toolbar-button sql-execute-button" size="small" type="primary" danger={selected?.environment === 'prod'} icon={<CodeOutlined />}
                    aria-label={selected?.environment === 'prod' ? '在生产环境执行命令窗口脚本' : '执行命令窗口脚本'} disabled={!canExecute || loading}
                    onClick={runActive}>
                    <span className="sql-toolbar-label">{selected?.environment === 'prod' ? '生产执行' : '执行'}</span>
                    <kbd className="sql-toolbar-keycap">{shortcutKeycap(SHORTCUT_HINTS.executeSql, macPlatform)}</kbd>
                  </Button>
                </Tooltip>
              ) : (
                <Tooltip title={loading && cancellable ? '请求数据库取消当前 SQL' : unavailableReason || `执行当前或选中 SQL（${SHORTCUT_HINTS.executeSql}）`}>
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
                      <kbd className="sql-toolbar-keycap">{shortcutKeycap(SHORTCUT_HINTS.executeSql, macPlatform)}</kbd>
                    </Button>
                  )}
                </Tooltip>
              )}
              <Popover trigger="click" placement="bottomRight" open={executionMenuOpen} onOpenChange={setExecutionMenuOpen}
                title={commandTab ? '执行方式与命令窗口选项' : '其它执行方式与选项'} content={executionMenuContent}>
                <Button className="sql-toolbar-button" size="small" type={loading ? 'default' : 'primary'} danger={selected?.environment === 'prod'}
                  icon={<DownOutlined />} aria-label="更多执行方式与选项" disabled={!selected} />
              </Popover>
            </Space.Compact>
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
  );

  return (
    <div className={`workspace sql-workspace${docked ? ' is-toolbar-docked' : ''}`}>
      {docked ? <>
        <HeaderDock slot="toolbar">{toolbarActions}</HeaderDock>
        <HeaderDock slot="context">{schemaSelect}</HeaderDock>
      </> : (
        <Header className="workspace-toolbar">
          <Tooltip title={selected?.jdbcUrl} placement="bottomLeft">
            <div className="toolbar-title sql-workspace-title">
              <Text type="secondary" className="ellipsis-text">
                {selected ? selected.name : '请先选择数据库连接'}
                {/* 顶栏和资源管理器已各有一个「只读」标签，这里不再占一整条横幅，只补一句说明。 */}
                {selected?.readonly ? ' · 只读连接，写入和 DDL 会被拒绝' : ''}
              </Text>
            </div>
          </Tooltip>
          {toolbarActions}
        </Header>
      )}
      <div ref={splitRef} className="sql-split" id="sql-split-workspace">
        {resultPaneMode !== 'maximized' && <div className={`editor${resultPaneMode === 'collapsed' ? ' editor-with-collapsed-results' : ''}`} style={resultPaneMode === 'normal' ? { flexBasis: `${splitLimits.value * 100}%` } : undefined}>
          <SqlEditorSurface
            value={draftSql}
            themeMode={themeMode}
            executeDisabled={!canExecute || loading}
            onMount={onEditorMount}
            completionSource={completionSource}
            onResolveUnknownObjects={onResolveUnknownObjects}
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
              onRetry={runActive}
              onDiagnose={aiQueryAvailable ? askAiToDiagnose : undefined}
            />
          )}
          {activeTab.command ? (
            <Suspense fallback={<PanelLoading compact text="正在打开命令窗口…" />}>
              <CommandConsole jobId={activeTab.command.jobId} onLocate={onLocateCommandLines} onClose={onCloseCommand} onFinished={onCommandFinished} />
            </Suspense>
          ) : resultPaneMode === 'collapsed' && activeResult ? (
            <CollapsedResultHeader result={activeResult} paneMode={resultPaneMode} onPaneModeChange={setResultPaneMode} />
          ) : activeTab.results.length === 1 ? (
            <div className="single-result-panel">
              <StatementResultPanel result={activeTab.results[0]} selectedConnectionId={selected?.id} dbType={selected?.dbType} active pagingLoading={pagingResultKey === `${activeTab.id}:${statementResultKey(activeTab.results[0])}`} paneMode={resultPaneMode} showIdentity onPaneModeChange={setResultPaneMode} onPageChange={handleResultPageChange} connectionId={selected?.id} onPreviewEdits={canWrite ? onPreviewResultEdits : undefined} onCommitEdits={canWrite ? onCommitResultEdits : undefined} onAskAiExplain={aiQueryAvailable ? askAiToExplain : undefined} onAskAiInterpret={aiSampleAllowed && canQuery ? askAiToInterpret : undefined} onAskAiReview={aiQueryAvailable ? askAiToReviewResult : undefined} onExportFull={canExport ? exportFullResult : undefined} />
            </div>
          ) : showScriptLog || resultItems.length > 1 ? (
            <Tabs className="result-tabs" activeKey={activeResultKey} onChange={onResultTabChange} items={resultItems} />
          ) : activeTab.errorDetail ? (
            <SqlExecutionErrorPanel
              detail={activeTab.errorDetail}
              retryDisabled={!canExecute || loading || !draftSql.trim()}
              onRetry={runActive}
              onDiagnose={aiQueryAvailable ? askAiToDiagnose : undefined}
            />
          ) : commandTab ? (
            <div className="sql-result-empty-state">
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="命令窗口" />
              <Text type="secondary">
                在上方编写脚本，按 Ctrl/Cmd+Enter 交给后台逐条执行（有选中内容时只执行选中部分）。
                结果是一条滚动日志：每条语句的影响行数、查询的前几行、DBMS_OUTPUT 与报错行号；支持 PL/SQL 块、PROMPT 与 WHENEVER SQLERROR。
              </Text>
              <Text type="secondary">当前选项：{commandOptionsLabel(commandOptions)}（在执行按钮右侧的下拉里修改）</Text>
            </div>
          ) : (
            <div className="sql-result-empty-state">
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚无执行结果" />
              {/* 执行、历史、片段库在上方工具栏都有，空状态里再摆一排同样的按钮只是噪音。 */}
              <Text type="secondary">选中 SQL 后按 Ctrl/Cmd+Enter，或直接执行光标所在语句。</Text>
            </div>
          )}
        </div>
      </div>
      <WorkspaceStatusBar status={status} trailing={<Text type="secondary">{loading ? `${cancelling ? '正在取消' : '执行中'} · ${formatElapsed(executionElapsedMs)} · ` : ''}{tabs.length} 个标签 · {draftSaveState}</Text>} />
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
            outcome={aiChatOutcome}
            plan={aiChatPlan}
            onFailureConsumed={() => { setAiChatFailure(undefined); setAiChatOutcome(undefined); setAiChatPlan(undefined); }}
            onClose={() => {
              setAiChatOpen(false);
              setAiChatFailure(undefined);
              setAiChatOutcome(undefined);
              setAiChatPlan(undefined);
            }}
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

const StatementResultPanel = memo(function StatementResultPanel({ result, selectedConnectionId, dbType, active, pagingLoading, paneMode, showIdentity, onPaneModeChange, onPageChange, connectionId, onPreviewEdits, onCommitEdits, onAskAiExplain, onAskAiInterpret, onAskAiReview, onExportFull }: {
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
  onPreviewEdits?: (request: ResultEditCommit) => Promise<string[]>;
  onCommitEdits?: (request: ResultEditCommit) => Promise<void>;
  /** 把这条执行计划交给 AI 解读；AI 不可用时不传。 */
  onAskAiExplain?: (result: SqlStatementResult, findings: ExplainFinding[]) => void;
  /** 把这批结果交给 AI 解读；连接没开样本档时不传。 */
  onAskAiInterpret?: (result: SqlStatementResult) => void;
  /** 结果跑通了但看着不对：把形状交给 AI 复盘。零行也要能点，那恰恰是最需要复盘的一种。 */
  onAskAiReview?: (result: SqlStatementResult) => void;
  /** 重新执行这条结果的 SQL 并由服务端导出；没有导出权限时不传。 */
  onExportFull?: (result: SqlStatementResult, format: ExportFormat) => void;
}) {
  const rowCount = result.result.resultSet ? result.result.rows.length : 0;
  const pagingEnabled = !result.result.page || selectedConnectionId === result.result.page.connectionId;
  const handlePageChange = useCallback((navigation: SqlPageNavigation) => {
    onPageChange(result, navigation);
  }, [onPageChange, result]);
  const handleExportFull = useMemo(() => onExportFull ? (format: ExportFormat) => onExportFull(result, format) : undefined, [onExportFull, result]);
  /*
    两个 AI 入口合成一个下拉。原来是灯泡（解读）和问号（复盘）两个图标并排，问号还和右边的
    说明图标长得一样。两条路发出去的东西不同（解读发前几行数据、复盘只发计数），所以菜单项
    上把这一点写明，而不是只靠悬停提示。
  */
  const aiItems: NonNullable<MenuProps['items']> = [
    ...(onAskAiInterpret && result.result.resultSet && result.result.rows.length > 0
      ? [{ key: 'interpret', icon: <BulbOutlined />, label: '解读结果并推荐图表（发送前几行数据）' }] : []),
    ...(onAskAiReview && result.result.resultSet
      ? [{ key: 'review', icon: <QuestionCircleOutlined />, label: '结果不对，交给 AI 复盘（只发计数）' }] : [])
  ];
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
          {aiItems.length > 0 && (
            <Dropdown trigger={['click']} menu={{
              items: aiItems,
              onClick: ({ key }) => {
                if (key === 'interpret') onAskAiInterpret?.(result);
                if (key === 'review') onAskAiReview?.(result);
              }
            }}>
              <Button type="text" size="small" icon={<BulbOutlined />} aria-label="AI 分析查询结果">AI</Button>
            </Dropdown>
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
          <ResultGrid result={result.result} fill active={active} pagingLoading={pagingLoading} pagingEnabled={pagingEnabled} dbType={dbType} sourceSql={result.sql} connectionId={connectionId} onPageChange={handlePageChange} onPreviewEdits={onPreviewEdits} onCommitEdits={onCommitEdits} onExportFull={handleExportFull} />
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
