import Editor from '@monaco-editor/react';
import type { OnMount } from '@monaco-editor/react';
import { memo, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Alert, Button, Dropdown, InputNumber, Layout, Space, Tabs, Tooltip, Typography } from 'antd';
import { DownloadOutlined, HistoryOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { Connection, ExportFormat, SqlStatementResult, SqlTab, WorkspaceStatus } from '../types';
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

export function SqlWorkspace({ selected, tabs, activeTabId, activeTab, status, loading, themeMode, editorSplitRatio, maxRows, onMaxRowsChange, onEditorSplitRatioChange, onTabChange, onTabAdd, onTabClose, onSqlChange, onEditorMount, onFormat, onExplain, onExecute, onExport, onOpenHistory, onResultTabChange }: {
  selected: Connection | null;
  tabs: SqlTab[];
  activeTabId: string;
  activeTab: SqlTab;
  status: WorkspaceStatus;
  loading: boolean;
  themeMode: 'light' | 'dark';
  editorSplitRatio: number;
  maxRows: number;
  onMaxRowsChange: (value: number) => void;
  onEditorSplitRatioChange: (value: number) => void;
  onTabChange: (tabId: string) => void;
  onTabAdd: () => void;
  onTabClose: (tabId: string) => void;
  onSqlChange: (sql: string) => void;
  onEditorMount: OnMount;
  onFormat: () => void;
  onExplain: () => void;
  onExecute: () => void;
  onExport: (format: ExportFormat) => void;
  onOpenHistory: () => void;
  onResultTabChange: (key: string) => void;
}) {
  const [draftSql, setDraftSql] = useState(activeTab.sql);
  const draftRef = useRef(activeTab.sql);
  const onSqlChangeRef = useRef(onSqlChange);
  const { elementRef: splitRef, height: splitHeight } = useVisibleElementHeight();

  useEffect(() => {
    onSqlChangeRef.current = onSqlChange;
  }, [onSqlChange]);

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

  const resultItems = activeTab.results.map((result) => ({
    key: statementResultKey(result),
    label: result.status === 'FAILED' ? `错误 ${result.index}` : result.result.resultSet ? `结果 ${result.index}` : `影响 ${result.index}`,
    children: <StatementResultPanel result={result} />
  }));
  const activeResultKey = activeTab.activeResultKey || resultItems[0]?.key;
  const splitLimits = editorSplitLimits(splitHeight, editorSplitRatio);

  return (
    <div className="workspace sql-workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Text strong>SQL 查询工作台</Text>
          <Text type="secondary" className="ellipsis-text">{selected?.jdbcUrl || '请先选择数据库连接'}</Text>
        </div>
        <Space size={8} wrap>
          <Tooltip title="单条查询最多返回的行数，结果达到上限时会提示截断">
            <Space.Compact size="small">
              <Button size="small" disabled>最大行数</Button>
              <InputNumber
                size="small"
                min={1}
                max={10_000}
                step={100}
                value={maxRows}
                disabled={loading}
                aria-label="查询最大返回行数"
                onChange={(value) => onMaxRowsChange(value || 500)}
              />
            </Space.Compact>
          </Tooltip>
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
          <Button size="small" disabled={!selected || loading} onClick={() => { commitDraft(); onExplain(); }}>执行计划</Button>
          <Tooltip title="执行当前或选中 SQL（Ctrl/Cmd+Enter）">
            <Button size="small" type="primary" icon={<PlayCircleOutlined />} disabled={!selected || loading} loading={loading} onClick={() => { commitDraft(); onExecute(); }}>执行</Button>
          </Tooltip>
        </Space>
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
              <StatementResultPanel result={activeTab.results[0]} />
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

const StatementResultPanel = memo(function StatementResultPanel({ result }: { result: SqlStatementResult }) {
  const rowCount = result.result.resultSet ? result.result.rows.length : 0;
  return (
    <div className="statement-result-panel">
      <div className="statement-result-meta">
        <Text type="secondary">
          第 {result.index} 条 · 用时 {result.result.elapsedMs}ms
          {result.result.resultSet ? ` · 返回 ${rowCount} 行` : ''}
          {result.result.truncated ? ` · 已达到 ${result.result.maxRows || rowCount} 行上限，结果可能不完整` : ''}
        </Text>
        <details className="statement-sql-details">
          <summary>查看执行语句</summary>
          <pre className="statement-sql">{result.sql}</pre>
        </details>
      </div>
      {result.status === 'FAILED' ? (
        <Alert type="error" showIcon message={`第 ${result.index} 条 SQL 执行失败`} description={result.errorMessage || '数据库返回未知错误'} />
      ) : (
        <ResultGrid result={result.result} fill />
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
