import Editor from '@monaco-editor/react';
import type { OnMount } from '@monaco-editor/react';
import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import '../monacoSetup';
import { Alert, Button, Dropdown, Layout, Space, Tabs, Tooltip, Typography } from 'antd';
import { DownloadOutlined, HistoryOutlined, PlayCircleOutlined, StopOutlined } from '@ant-design/icons';
import type { Connection, ExportFormat, SqlPageNavigation, SqlStatementResult, SqlTab, WorkspaceStatus } from '../types';
import { ResultGrid } from './ResultGrid';
import { PaneResizer } from './PaneResizer';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';

const { Header } = Layout;
const { Text } = Typography;
const EDITOR_OPTIONS = {
  minimap: { enabled: false },
  fontSize: 14,
  lineHeight: 22,
  padding: { top: 12, bottom: 12 },
  smoothScrolling: true,
  scrollBeyondLastLine: false,
  automaticLayout: true
} as const;
const MIN_EDITOR_HEIGHT = 120;
const MIN_RESULTS_HEIGHT = 240;
const RESIZER_HEIGHT = 5;

export function SqlWorkspace({ selected, tabs, activeTabId, activeTab, status, loading, cancelling, cancellable, pagingResultKey, themeMode, editorSplitRatio, onEditorSplitRatioChange, onTabChange, onTabAdd, onTabClose, onSqlChange, onEditorMount, onFormat, onExplain, onExecute, onCancel, onExport, onOpenHistory, onResultTabChange, onResultPageChange }: {
  selected: Connection | null;
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
  onTabClose: (tabId: string) => void;
  onSqlChange: (sql: string) => void;
  onEditorMount: OnMount;
  onFormat: () => void;
  onExplain: () => void;
  onExecute: () => void;
  onCancel: () => void;
  onExport: (format: ExportFormat) => void;
  onOpenHistory: () => void;
  onResultTabChange: (key: string) => void;
  onResultPageChange: (result: SqlStatementResult, navigation: SqlPageNavigation) => void;
}) {
  const [draftSql, setDraftSql] = useState(activeTab.sql);
  const draftRef = useRef(activeTab.sql);
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
  }, [activeTabId, activeTab.sql]);

  useEffect(() => () => {
    onSqlChangeRef.current(draftRef.current);
  }, [activeTabId]);

  function updateDraft(value: string) {
    draftRef.current = value;
    setDraftSql(value);
  }

  function commitDraft() {
    onSqlChangeRef.current(draftRef.current);
  }

  const activeResultKey = activeTab.activeResultKey || (activeTab.results[0] ? statementResultKey(activeTab.results[0]) : undefined);
  const resultItems = useMemo(() => activeTab.results.map((result) => {
    const resultKey = statementResultKey(result);
    return {
      key: resultKey,
      label: result.status === 'FAILED' ? `错误 ${result.index}` : result.result.resultSet ? `结果 ${result.index}` : `影响 ${result.index}`,
      children: (
        <StatementResultPanel
          result={result}
          selectedConnectionId={selected?.id}
          active={activeResultKey === resultKey}
          pagingLoading={pagingResultKey === `${activeTab.id}:${resultKey}`}
          onPageChange={handleResultPageChange}
        />
      )
    };
  }), [activeResultKey, activeTab.id, activeTab.results, handleResultPageChange, pagingResultKey, selected?.id]);
  const splitLimits = editorSplitLimits(splitHeight, editorSplitRatio);

  return (
    <div className={`workspace sql-workspace${selected?.readonly ? ' is-readonly' : ''}`}>
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Text strong>SQL 查询工作台</Text>
          <Text type="secondary" className="ellipsis-text">{selected?.jdbcUrl || '请先选择数据库连接'}</Text>
        </div>
        <Space size={8} wrap>
          <Tooltip title="格式化 SQL（Ctrl/Cmd+Shift+F）">
            <Button size="small" disabled={loading} onClick={() => { commitDraft(); onFormat(); }}>格式化</Button>
          </Tooltip>
          <Button size="small" icon={<HistoryOutlined />} disabled={!selected} onClick={onOpenHistory}>历史</Button>
          <Dropdown
            menu={{
              items: [
                { key: 'csv', label: '导出 CSV' },
                { key: 'json', label: '导出 JSON' },
                { key: 'sql', label: '导出 SQL' },
                { key: 'xml', label: '导出 XML' }
              ],
              onClick: ({ key }) => {
                commitDraft();
                onExport(key as ExportFormat);
              }
            }}
          >
            <Button size="small" icon={<DownloadOutlined />} disabled={!selected || loading}>导出</Button>
          </Dropdown>
          <Button size="small" disabled={!selected || loading || !selected.capabilities?.explain} onClick={() => { commitDraft(); onExplain(); }}>执行计划</Button>
          <Tooltip title={loading && cancellable ? '请求数据库取消当前 SQL' : '执行当前或选中 SQL（Ctrl/Cmd+Enter）'}>
            {loading && cancellable ? (
              <Button size="small" danger icon={<StopOutlined />} loading={cancelling} onClick={onCancel}>取消执行</Button>
            ) : loading ? (
              <Button size="small" type="primary" loading disabled>处理中</Button>
            ) : (
              <Button size="small" type="primary" icon={<PlayCircleOutlined />} disabled={!selected} onClick={() => { commitDraft(); onExecute(); }}>执行</Button>
            )}
          </Tooltip>
        </Space>
      </Header>
      {selected?.readonly && <Alert className="sql-readonly-alert" type="warning" showIcon message="只读连接：后端仅允许查询类 SQL，写入和 DDL 会被拒绝。" />}
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
            onTabClose(String(targetKey));
          }
        }}
        hideAdd={false}
        items={tabs.map((tab) => ({ key: tab.id, label: tab.title, closable: tabs.length > 1 }))}
      />
      <div ref={splitRef} className="sql-split" id="sql-split-workspace">
        <div className="editor" style={{ flexBasis: `${splitLimits.value * 100}%` }}>
          <Editor
            height="100%"
            language="sql"
            value={draftSql}
            onMount={onEditorMount}
            onChange={(value) => updateDraft(value || '')}
            theme={themeMode === 'dark' ? 'vs-dark' : 'vs'}
            options={EDITOR_OPTIONS}
          />
        </div>
        <PaneResizer
          direction="vertical"
          unit="ratio"
          value={splitLimits.value}
          min={splitLimits.min}
          max={splitLimits.max}
          disabled={splitLimits.max - splitLimits.min < 0.01}
          ariaLabel="调整 SQL 编辑器和结果区高度"
          controlsId="sql-split-workspace"
          onChange={onEditorSplitRatioChange}
        />
        <div className="sql-results-pane">
          {activeTab.results.length === 1 ? (
            <div className="single-result-panel">
              <StatementResultPanel result={activeTab.results[0]} selectedConnectionId={selected?.id} active pagingLoading={pagingResultKey === `${activeTab.id}:${statementResultKey(activeTab.results[0])}`} onPageChange={handleResultPageChange} />
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
}

const StatementResultPanel = memo(function StatementResultPanel({ result, selectedConnectionId, active, pagingLoading, onPageChange }: {
  result: SqlStatementResult;
  selectedConnectionId?: number;
  active: boolean;
  pagingLoading: boolean;
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
        <Text type="secondary">
          第 {result.index} 条 · 用时 {result.result.elapsedMs}ms
          {result.result.resultSet ? ` · 返回 ${rowCount} 行` : ''}
          {result.result.truncated ? ' · 已达到服务端结果大小上限，结果可能不完整' : ''}
        </Text>
        <details className="statement-sql-details">
          <summary>查看执行语句</summary>
          <pre className="statement-sql">{result.sql}</pre>
        </details>
      </div>
      {result.status === 'FAILED' ? (
        <Alert type="error" showIcon message={`第 ${result.index} 条 SQL 执行失败`} description={result.errorMessage || '数据库返回未知错误'} />
      ) : (
        <div className="statement-result-content">
          <div className="statement-result-notices">
            {result.result.page && !pagingEnabled && <Alert type="warning" showIcon message="该结果来自其他连接，请切回原连接后再翻页。" />}
            {result.result.page && <Text type="secondary" className="result-paging-hint">翻页会重新执行原 SQL；未使用 ORDER BY 时结果顺序可能变化。</Text>}
          </div>
          <ResultGrid result={result.result} fill active={active} pagingLoading={pagingLoading} pagingEnabled={pagingEnabled} onPageChange={handlePageChange} />
        </div>
      )}
    </div>
  );
});

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
