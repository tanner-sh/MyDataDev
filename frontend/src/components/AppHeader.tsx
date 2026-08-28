import { Badge, Button, Select, Space, Tag, Tooltip, Typography } from 'antd';
import {
  CloudServerOutlined,
  DatabaseOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MoonOutlined,
  ReloadOutlined,
  SettingOutlined,
  StarFilled,
  SunOutlined,
  SyncOutlined,
  ThunderboltOutlined
} from '@ant-design/icons';
import type { Connection } from '../types';
import { dbTypeLabel, environmentLabel } from '../utils';
import { backgroundTaskLabel, type BackgroundTaskSummary } from '../backgroundTasks';
import { SHORTCUT_HINTS } from '../keyboardShortcuts';
import { memo, useMemo } from 'react';

const { Text } = Typography;

type AppHeaderProps = {
  connections: Connection[];
  favoriteConnectionIds: number[];
  selected: Connection | null;
  connectionsLoading: boolean;
  backgroundTasks: BackgroundTaskSummary;
  explorerCollapsed: boolean;
  themeMode: 'light' | 'dark';
  onToggleExplorer: () => void;
  onSelectConnection: (connection: Connection) => void;
  onRefreshConnections: () => void;
  /** 打开管理抽屉（默认落在连接管理）。 */
  onOpenManagement: () => void;
  /** 后台任务徽标：直接落到备份分区。 */
  onOpenBackups: () => void;
  onToggleTheme: () => void;
};

export const AppHeader = memo(function AppHeader({
  connections,
  favoriteConnectionIds,
  selected,
  connectionsLoading,
  backgroundTasks,
  explorerCollapsed,
  themeMode,
  onToggleExplorer,
  onSelectConnection,
  onRefreshConnections,
  onOpenManagement,
  onOpenBackups,
  onToggleTheme
}: AppHeaderProps) {
  const connectionOptions = useMemo(() => {
    const favorites = new Set(favoriteConnectionIds);
    return connections
      .map((connection, index) => ({ connection, index }))
      .sort((left, right) => {
        if (left.connection.id === selected?.id) return -1;
        if (right.connection.id === selected?.id) return 1;
        const favoriteDifference = Number(favorites.has(right.connection.id)) - Number(favorites.has(left.connection.id));
        return favoriteDifference || left.index - right.index;
      })
      .map(({ connection }) => ({ value: connection.id, label: connection.name, connection }));
  }, [connections, favoriteConnectionIds, selected?.id]);

  return (
    <header className="app-header">
      <div className="app-header-brand">
        <Tooltip title={`${explorerCollapsed ? '展开' : '收起'}资源管理器（${SHORTCUT_HINTS.toggleExplorer}）`}>
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
        <Badge status={selected?.environment === 'prod' ? 'error' : selected ? 'success' : 'default'} />
        <Select
          variant="borderless"
          className="connection-select"
          value={selected?.id}
          placeholder="选择数据库连接"
          loading={connectionsLoading}
          optionLabelProp="label"
          options={connectionOptions}
          optionRender={(option) => {
            const connection = option.data.connection as Connection;
            return (
              <div className="connection-option">
                <div className="connection-option-main">
                  <Text strong ellipsis>{favoriteConnectionIds.includes(connection.id) && <StarFilled className="favorite-icon" />} {connection.name}</Text>
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
            <Tag className="connection-db-tag" color="blue" variant="filled">{dbTypeLabel(selected.dbType)}</Tag>
            <Tag className="connection-environment-tag" color={selected.environment === 'prod' ? 'red' : 'default'} variant="filled">
              {environmentLabel(selected.environment)}
            </Tag>
            {selected.readonly && <Tag className="connection-readonly-tag" color="orange" variant="filled">只读</Tag>}
          </Space>
        )}
      </div>

      <Space size={4} className="app-header-actions">
        {backgroundTasks.total > 0 && (
          <Tooltip title={`${backgroundTaskLabel(backgroundTasks)}，点击查看`}>
            <Badge count={backgroundTasks.total} size="small" offset={[-4, 4]}>
              <Button
                type="text"
                className="header-background-tasks"
                icon={<SyncOutlined spin />}
                aria-label={backgroundTaskLabel(backgroundTasks)}
                onClick={onOpenBackups}
              >
                后台任务
              </Button>
            </Badge>
          </Tooltip>
        )}
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
        {/*
          管理类入口只留一个。之前这里有 6 个按钮，窄屏下会收成 6 个纯图标，其中「结构对比」
          与「审计」的字形几乎一样；把它们收进带左侧导航的管理抽屉之后，这个问题从根上没了。
        */}
        <Tooltip title="管理：连接、备份、结构对比、MCP、会话、审计">
          <Button type="text" icon={<SettingOutlined />} aria-label="打开管理面板" onClick={onOpenManagement}>管理</Button>
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
});
