import Editor from '@monaco-editor/react';
import type { OnMount } from '@monaco-editor/react';
import { Alert, Button, Dropdown, Layout, Space, Tabs, Typography } from 'antd';
import { DownloadOutlined, HistoryOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { Connection, SqlTab } from '../types';
import { ResultGrid } from './ResultGrid';

const { Header } = Layout;
const { Text } = Typography;

export function SqlWorkspace({ selected, tabs, activeTabId, activeTab, statusMessage, loading, onTabChange, onTabAdd, onTabClose, onSqlChange, onEditorMount, onFormat, onExplain, onExecute, onExport, onOpenHistory }: {
  selected: Connection | null;
  tabs: SqlTab[];
  activeTabId: string;
  activeTab: SqlTab;
  statusMessage: string;
  loading: boolean;
  onTabChange: (tabId: string) => void;
  onTabAdd: () => void;
  onTabClose: (tabId: string) => void;
  onSqlChange: (sql: string) => void;
  onEditorMount: OnMount;
  onFormat: () => void;
  onExplain: () => void;
  onExecute: () => void;
  onExport: (format: 'csv' | 'json') => void;
  onOpenHistory: () => void;
}) {
  return (
    <div className="workspace">
      <Header className="workspace-toolbar">
        <div className="toolbar-title">
          <Text strong>{selected ? selected.name : '未选择连接'}</Text>
          <Text type="secondary" className="ellipsis-text">{selected?.jdbcUrl || '请选择左侧数据库连接'}</Text>
        </div>
        <Space size={8} wrap>
          <Button size="small" onClick={onFormat}>格式化</Button>
          <Button size="small" icon={<HistoryOutlined />} disabled={!selected} onClick={onOpenHistory}>历史</Button>
          <Dropdown
            menu={{
              items: [
                { key: 'csv', label: '导出 CSV' },
                { key: 'json', label: '导出 JSON' }
              ],
              onClick: ({ key }) => onExport(key as 'csv' | 'json')
            }}
          >
            <Button size="small" icon={<DownloadOutlined />} disabled={!selected || loading}>导出</Button>
          </Dropdown>
          <Button size="small" disabled={!selected || loading} onClick={onExplain}>执行计划</Button>
          <Button size="small" type="primary" icon={<PlayCircleOutlined />} disabled={!selected || loading} loading={loading} onClick={onExecute}>执行</Button>
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
      <div className="editor">
        <Editor height="100%" language="sql" value={activeTab.sql} onMount={onEditorMount} onChange={(value) => onSqlChange(value || '')} theme="vs-dark" options={{ minimap: { enabled: false }, fontSize: 14 }} />
      </div>
      <Alert className="status-alert" type={loading ? 'info' : 'success'} message={statusMessage} showIcon />
      <ResultGrid result={activeTab.result} />
    </div>
  );
}
