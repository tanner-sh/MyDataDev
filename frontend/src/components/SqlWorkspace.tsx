import Editor from '@monaco-editor/react';
import type { OnMount } from '@monaco-editor/react';
import { Alert, Button, Dropdown, Layout, Space, Tabs, Tooltip, Typography } from 'antd';
import { DownloadOutlined, HistoryOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { Connection, ExportFormat, SqlStatementResult, SqlTab, WorkspaceStatus } from '../types';
import { ResultGrid } from './ResultGrid';
import { PaneResizer } from './PaneResizer';
import { WorkspaceStatusBar } from './WorkspaceStatusBar';

const { Header } = Layout;
const { Text } = Typography;

export function SqlWorkspace({ selected, tabs, activeTabId, activeTab, status, loading, themeMode, editorSplitRatio, onEditorSplitRatioChange, onTabChange, onTabAdd, onTabClose, onSqlChange, onEditorMount, onFormat, onExplain, onExecute, onExport, onOpenHistory, onResultTabChange }: {
  selected: Connection | null;
  tabs: SqlTab[];
  activeTabId: string;
  activeTab: SqlTab;
  status: WorkspaceStatus;
  loading: boolean;
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
  onExport: (format: ExportFormat) => void;
  onOpenHistory: () => void;
  onResultTabChange: (key: string) => void;
}) {
  const resultItems = activeTab.results.map((result) => ({
    key: statementResultKey(result),
    label: result.status === 'FAILED' ? `错误 ${result.index}` : result.result.resultSet ? `结果 ${result.index}` : `影响 ${result.index}`,
    children: <StatementResultPanel result={result} />
  }));
  const activeResultKey = activeTab.activeResultKey || resultItems[0]?.key;

  return (
    <div className="workspace sql-workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Text strong>SQL 查询工作台</Text>
          <Text type="secondary" className="ellipsis-text">{selected?.jdbcUrl || '请先选择数据库连接'}</Text>
        </div>
        <Space size={8} wrap>
          <Tooltip title="格式化 SQL（Ctrl/Cmd+Shift+F）">
            <Button size="small" disabled={loading} onClick={onFormat}>格式化</Button>
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
              onClick: ({ key }) => onExport(key as ExportFormat)
            }}
          >
            <Button size="small" icon={<DownloadOutlined />} disabled={!selected || loading}>导出</Button>
          </Dropdown>
          <Button size="small" disabled={!selected || loading} onClick={onExplain}>执行计划</Button>
          <Tooltip title="执行当前或选中 SQL（Ctrl/Cmd+Enter）">
            <Button size="small" type="primary" icon={<PlayCircleOutlined />} disabled={!selected || loading} loading={loading} onClick={onExecute}>执行</Button>
          </Tooltip>
        </Space>
      </Header>
      <Tabs
        className="sql-tabs"
        type="editable-card"
        activeKey={activeTabId}
        onChange={onTabChange}
        onEdit={(targetKey, action) => {
          if (action === 'add') {
            onTabAdd();
          } else {
            onTabClose(String(targetKey));
          }
        }}
        hideAdd={false}
        items={tabs.map((tab) => ({ key: tab.id, label: tab.title, closable: tabs.length > 1 }))}
      />
      <div className="sql-split" id="sql-split-workspace">
        <div className="editor" style={{ flexBasis: `${editorSplitRatio * 100}%` }}>
          <Editor
            height="100%"
            language="sql"
            value={activeTab.sql}
            onMount={onEditorMount}
            onChange={(value) => onSqlChange(value || '')}
            theme={themeMode === 'dark' ? 'vs-dark' : 'vs'}
            options={{
              minimap: { enabled: false },
              fontSize: 14,
              lineHeight: 22,
              padding: { top: 12, bottom: 12 },
              smoothScrolling: true,
              scrollBeyondLastLine: false,
              automaticLayout: true
            }}
          />
        </div>
        <PaneResizer
          direction="vertical"
          unit="ratio"
          value={editorSplitRatio}
          min={0.2}
          max={0.8}
          ariaLabel="调整 SQL 编辑器和结果区高度"
          controlsId="sql-split-workspace"
          onChange={onEditorSplitRatioChange}
        />
        <div className="sql-results-pane">
          {resultItems.length > 0 ? (
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

function StatementResultPanel({ result }: { result: SqlStatementResult }) {
  return (
    <div className="statement-result-panel">
      <div className="statement-result-meta">
        <Text type="secondary">第 {result.index} 条 · 用时 {result.result.elapsedMs}ms</Text>
        <pre className="statement-sql">{result.sql}</pre>
      </div>
      {result.status === 'FAILED' ? (
        <Alert type="error" showIcon message={`第 ${result.index} 条 SQL 执行失败`} description={result.errorMessage || '数据库返回未知错误'} />
      ) : (
        <ResultGrid result={result.result} fill />
      )}
    </div>
  );
}

function statementResultKey(result: SqlStatementResult) {
  return `statement-${result.index}`;
}
