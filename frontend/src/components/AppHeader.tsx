import { Badge, Button, Select, Space, Tag, Tooltip, Typography } from 'antd';
import {
  CloudServerOutlined,
  DatabaseOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MoonOutlined,
  ReloadOutlined,
  SettingOutlined,
  SunOutlined
} from '@ant-design/icons';
import type { Connection } from '../types';
import { dbTypeLabel, environmentLabel } from '../utils';

const { Text } = Typography;

type AppHeaderProps = {
  connections: Connection[];
  selected: Connection | null;
  connectionsLoading: boolean;
  explorerCollapsed: boolean;
  themeMode: 'light' | 'dark';
  onToggleExplorer: () => void;
  onSelectConnection: (connection: Connection) => void;
  onRefreshConnections: () => void;
  onOpenConnections: () => void;
  onOpenBackups: () => void;
  onToggleTheme: () => void;
};

export function AppHeader({
  connections,
  selected,
  connectionsLoading,
  explorerCollapsed,
  themeMode,
  onToggleExplorer,
  onSelectConnection,
  onRefreshConnections,
  onOpenConnections,
  onOpenBackups,
  onToggleTheme
}: AppHeaderProps) {
  return (
    <header className="app-header">
      <div className="app-header-brand">
        <Tooltip title={`${explorerCollapsed ? '展开' : '收起'}资源管理器（Ctrl/Cmd+B）`}>
          <Button
            type="text"
            className="header-icon-button"
            icon={explorerCollapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            aria-label={explorerCollapsed ? '展开资源管理器' : '收起资源管理器'}
            onClick={onToggleExplorer}
          />
        </Tooltip>
        <div className="brand-mark" aria-hidden="true"><DatabaseOutlined /></div>
        <div className="brand-copy">
          <Text strong>MyDataDev</Text>
          <Text type="secondary">数据库工作台</Text>
        </div>
      </div>

      <div className="connection-switcher">
        <Badge status={selected ? 'success' : 'default'} />
        <Select
          variant="borderless"
          className="connection-select"
          value={selected?.id}
          placeholder="选择数据库连接"
          loading={connectionsLoading}
          optionLabelProp="label"
          options={connections.map((connection) => ({
            value: connection.id,
            label: connection.name,
            connection
          }))}
          optionRender={(option) => {
            const connection = option.data.connection as Connection;
            return (
              <div className="connection-option">
                <div className="connection-option-main">
                  <Text strong ellipsis>{connection.name}</Text>
                  <Text type="secondary" ellipsis>{connection.jdbcUrl}</Text>
                </div>
                <Tag variant="filled">{environmentLabel(connection.environment)}</Tag>
              </div>
            );
          }}
          onChange={(id) => {
            const connection = connections.find((item) => item.id === id);
            if (connection) onSelectConnection(connection);
          }}
        />
        {selected && (
          <Space size={4} className="connection-context-tags">
            <Tag color="blue" variant="filled">{dbTypeLabel(selected.dbType)}</Tag>
            <Tag color={selected.environment === 'prod' ? 'red' : 'default'} variant="filled">
              {environmentLabel(selected.environment)}
            </Tag>
            {selected.readonly && <Tag color="orange" variant="filled">只读</Tag>}
          </Space>
        )}
      </div>

      <Space size={4} className="app-header-actions">
        <Tooltip title="刷新连接">
          <Button
            type="text"
            className="header-icon-button"
            icon={<ReloadOutlined />}
            loading={connectionsLoading}
            aria-label="刷新连接"
            onClick={onRefreshConnections}
          />
        </Tooltip>
        <Tooltip title="连接管理">
          <Button type="text" icon={<SettingOutlined />} aria-label="连接管理" onClick={onOpenConnections}>连接管理</Button>
        </Tooltip>
        <Tooltip title="备份任务">
          <Button type="text" icon={<CloudServerOutlined />} aria-label="备份任务" disabled={!selected} onClick={onOpenBackups}>备份任务</Button>
        </Tooltip>
        <Tooltip title={themeMode === 'light' ? '切换深色主题' : '切换浅色主题'}>
          <Button
            type="text"
            className="header-icon-button"
            icon={themeMode === 'light' ? <MoonOutlined /> : <SunOutlined />}
            aria-label={themeMode === 'light' ? '切换深色主题' : '切换浅色主题'}
            onClick={onToggleTheme}
          />
        </Tooltip>
      </Space>
    </header>
  );
}
