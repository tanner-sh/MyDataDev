import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Dropdown, Layout, Popover, Space, Tabs, Tooltip, Typography } from 'antd';
import { DownloadOutlined, DownOutlined, FileTextOutlined, FormatPainterOutlined, FullscreenExitOutlined, FullscreenOutlined, FundProjectionScreenOutlined, HistoryOutlined, InfoCircleOutlined, MoreOutlined, PlayCircleOutlined, ProfileOutlined, StopOutlined, UpOutlined } from '@ant-design/icons';
import type { MenuProps } from 'antd';
import type { Connection, ExportFormat, SqlPageNavigation, SqlStatementResult, SqlTab, WorkspaceStatus } from '../types';
import { ResultGrid } from './ResultGrid';
import { PaneResizer } from './PaneResizer';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';
import { SqlEditorSurface } from './SqlEditorSurface';
import { nextResultPaneMode, sqlStatementResultLabel, type ResultPaneMode } from '../sqlResultWorkspace';
import type { SqlEditorOnMount } from '../sqlEditorTypes';

const { Header } = Layout;
const { Text } = Typography;
const EDITOR_OPTIONS = {
  minimap: { enabled: false },
  fontSize: 14,
  lineHeight: 22,
  padding: { top: 12, bottom: 12 },
  smoothScrolling: true,
  scrollBeyondLastLine: false,
  quickSuggestions: { other: 'on', comments: 'off', strings: 'off' },
  suggestOnTriggerCharacters: true,
  automaticLayout: true
} as const;
const MIN_EDITOR_HEIGHT = 120;
const MIN_RESULTS_HEIGHT = 240;
const RESIZER_HEIGHT = 5;

export const SqlWorkspace = memo(function SqlWorkspace({ selected, activeSchema, namespaceKind, sessionConnectionId, tabs, activeTabId, activeTab, status, loading, cancelling, cancellable, pagingResultKey, themeMode, editorSplitRatio, onEditorSplitRatioChange, onTabChange, onTabAdd, onTabClose, onTabRename, onTabDuplicate, onSqlChange, onEditorMount, onFormat, onExplain, onExecute, onCancel, onExport, onOpenHistory, onSqlFileSelect, onOpenSqlFileTasks, onResultTabChange, onResultPageChange }: {
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
  pagingResultKey: string | null;
  themeMode: 'light' | 'dark';
  editorSplitRatio: number;
  onEditorSplitRatioChange: (value: number) => void;
  onTabChange: (tabId: string) => void;
  onTabAdd: () => void;
  onTabClose: (tabId: string, liveSql?: string) => void;
  onTabRename: (tabId: string) => void;
  onTabDuplicate: (tabId: string, liveSql?: string) => void;
  onSqlChange: (connectionId: number | null, tabId: string, sql: string) => void;
  onEditorMount: SqlEditorOnMount;
  onFormat: (liveSql?: string) => void;
  onExplain: (liveSql?: string) => void;
  onExecute: (liveSql?: string) => void;
  onCancel: () => void;
  onExport: (format: ExportFormat, liveSql?: string) => void;
  onOpenHistory: () => void;
  onSqlFileSelect: (file: File) => void;
  onOpenSqlFileTasks: () => void;
  onResultTabChange: (key: string) => void;
  onResultPageChange: (result: SqlStatementResult, navigation: SqlPageNavigation) => void;
}) {
  const [draftSql, setDraftSql] = useState(activeTab.sql);
  const [resultPaneMode, setResultPaneMode] = useState<ResultPaneMode>('normal');
  const draftRef = useRef(activeTab.sql);
  const draftCommitTimerRef = useRef<number | null>(null);
  const previousResultsRef = useRef(activeTab.results);
  const sqlFileInputRef = useRef<HTMLInputElement>(null);
  const onSqlChangeRef = useRef(onSqlChange);
  const onResultPageChangeRef = useRef(onResultPageChange);
  const { elementRef: splitRef, height: splitHeight } = useVisibleElementHeight();

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
        />
      )
    };
  }), [activeResultKey, activeTab.id, activeTab.results, handleResultPageChange, pagingResultKey, resultPaneMode, selected?.dbType, selected?.id]);
  const splitLimits = editorSplitLimits(splitHeight, editorSplitRatio);
  const moreMenu: MenuProps = {
    items: [
      { key: 'sql-file', icon: <FileTextOutlined />, label: '执行本地 SQL 文件', disabled: !selected },
      { key: 'sql-file-tasks', icon: <ProfileOutlined />, label: '查看 SQL 文件任务' },
      { type: 'divider' },
      {
        key: 'export',
        icon: <DownloadOutlined />,
        label: '重新查询并导出',
        disabled: !selected || loading,
        children: [
          { key: 'export:csv', label: '重新查询并导出 CSV' },
          { key: 'export:json', label: '重新查询并导出 JSON' },
          { key: 'export:sql', label: '重新查询并导出 SQL' },
          { key: 'export:xml', label: '重新查询并导出 XML' }
        ]
      },
      {
        key: 'explain',
        icon: <FundProjectionScreenOutlined />,
        label: '查看执行计划',
        disabled: !selected || loading || !selected.capabilities?.explain
      },
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
      if (key === 'explain') {
        commitDraft();
        onExplain(draftRef.current);
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
            <Tooltip title="SQL 历史">
              <Button className="sql-toolbar-button" size="small" icon={<HistoryOutlined />} aria-label="查看 SQL 历史" disabled={!selected} onClick={onOpenHistory}>
                <span className="sql-toolbar-label">历史</span>
              </Button>
            </Tooltip>
            <Dropdown trigger={['click']} menu={moreMenu}>
              <Button className="sql-toolbar-button" size="small" icon={<MoreOutlined />} aria-label="更多 SQL 操作">
                <span className="sql-toolbar-label">更多</span>
              </Button>
            </Dropdown>
          </Space>
          <Space size={4} className="sql-toolbar-group sql-toolbar-execution-group">
            <Tooltip title={loading && cancellable ? '请求数据库取消当前 SQL' : '执行当前或选中 SQL（Ctrl/Cmd+Enter）'}>
              {loading && cancellable ? (
                <Button className="sql-toolbar-button sql-execute-button" size="small" danger icon={<StopOutlined />} aria-label="取消执行 SQL" loading={cancelling} onClick={onCancel}>
                  <span className="sql-toolbar-label">取消</span>
                </Button>
              ) : loading ? (
                <Button className="sql-toolbar-button sql-execute-button" size="small" type="primary" aria-label="SQL 处理中" loading disabled>
                  <span className="sql-toolbar-label">处理中</span>
                </Button>
              ) : (
                <Button className="sql-toolbar-button sql-execute-button" size="small" type="primary" icon={<PlayCircleOutlined />} aria-label="执行当前或选中 SQL" disabled={!selected} onClick={() => { commitDraft(); onExecute(draftRef.current); }}>
                  <span className="sql-toolbar-label">执行</span>
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
      {selected?.readonly && <Alert className="sql-readonly-alert" type="warning" showIcon title="只读连接：后端仅允许查询类 SQL，写入和 DDL 会被拒绝。" />}
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
            options={EDITOR_OPTIONS}
            executeDisabled={!selected || loading}
            onMount={onEditorMount}
            onChange={updateDraft}
            onFormat={() => { commitDraft(); onFormat(draftRef.current); }}
            onExecute={() => { commitDraft(); onExecute(draftRef.current); }}
          />
        </div>}
        {resultPaneMode === 'normal' && <PaneResizer
          direction="vertical"
          unit="ratio"
          value={splitLimits.value}
          min={splitLimits.min}
          max={splitLimits.max}
          disabled={splitLimits.max - splitLimits.min < 0.01}
          ariaLabel="调整 SQL 编辑器和结果区高度"
          controlsId="sql-split-workspace"
          onChange={onEditorSplitRatioChange}
        />}
        <div className={`sql-results-pane is-${resultPaneMode}`}>
          {resultPaneMode === 'collapsed' && activeResult ? (
            <CollapsedResultHeader result={activeResult} paneMode={resultPaneMode} onPaneModeChange={setResultPaneMode} />
          ) : activeTab.results.length === 1 ? (
            <div className="single-result-panel">
              <StatementResultPanel result={activeTab.results[0]} selectedConnectionId={selected?.id} dbType={selected?.dbType} active pagingLoading={pagingResultKey === `${activeTab.id}:${statementResultKey(activeTab.results[0])}`} paneMode={resultPaneMode} showIdentity onPaneModeChange={setResultPaneMode} onPageChange={handleResultPageChange} />
            </div>
          ) : resultItems.length > 1 ? (
            <Tabs className="result-tabs" activeKey={activeResultKey} onChange={onResultTabChange} items={resultItems} />
          ) : (
            <ResultGrid result={null} fill />
          )}
        </div>
      </div>
      <WorkspaceStatusBar status={status} trailing={<Text type="secondary">{tabs.length} 个查询标签</Text>} />
    </div>
  );
});

const StatementResultPanel = memo(function StatementResultPanel({ result, selectedConnectionId, dbType, active, pagingLoading, paneMode, showIdentity, onPaneModeChange, onPageChange }: {
  result: SqlStatementResult;
  selectedConnectionId?: number;
  dbType?: string;
  active: boolean;
  pagingLoading: boolean;
  paneMode: ResultPaneMode;
  showIdentity: boolean;
  onPaneModeChange: (mode: ResultPaneMode) => void;
  onPageChange: (result: SqlStatementResult, navigation: SqlPageNavigation) => void;
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
          </div>
          <ResultGrid result={result.result} fill active={active} pagingLoading={pagingLoading} pagingEnabled={pagingEnabled} dbType={dbType} sourceSql={result.sql} onPageChange={handlePageChange} />
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
