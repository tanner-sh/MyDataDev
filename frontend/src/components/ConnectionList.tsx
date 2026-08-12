import { Alert, Button, Card, Dropdown, Empty, Input, Popconfirm, Select, Skeleton, Space, Tag, Tooltip, Typography } from 'antd';
import { CopyOutlined, DeleteOutlined, EditOutlined, MoreOutlined, StarFilled, StarOutlined, SwapOutlined, ThunderboltOutlined } from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { useMemo, useState } from 'react';
import type { Connection } from '../types';
import { dbTypeLabel, environmentLabel } from '../utils';

const { Text } = Typography;

export function ConnectionList({ connections, favoriteConnectionIds, selectedId, connectionsLoading, connectionsError, connectionsReady, testingConnectionId, onSwitch, onEdit, onTest, onDuplicate, onDelete, onToggleFavorite }: {
  connections: Connection[];
  favoriteConnectionIds: number[];
  selectedId?: number;
  connectionsLoading: boolean;
  connectionsError: string;
  connectionsReady: boolean;
  testingConnectionId: number | null;
  onSwitch: (connection: Connection) => void;
  onEdit: (connection: Connection) => void;
  onTest: (connection: Connection) => void;
  onDuplicate: (connection: Connection) => void;
  onDelete: (connection: Connection) => void;
  onToggleFavorite: (connectionId: number) => void;
}) {
  const [keyword, setKeyword] = useState('');
  const [environment, setEnvironment] = useState<string>('all');
  const [dbType, setDbType] = useState<string>('all');
  const [favoriteOnly, setFavoriteOnly] = useState(false);
  const favoriteIds = useMemo(() => new Set(favoriteConnectionIds), [favoriteConnectionIds]);
  const visibleConnections = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLocaleLowerCase();
    return connections
      .map((connection, index) => ({ connection, index }))
      .filter(({ connection }) => (!normalizedKeyword
        || connection.name.toLocaleLowerCase().includes(normalizedKeyword)
        || connection.jdbcUrl.toLocaleLowerCase().includes(normalizedKeyword))
        && (environment === 'all' || connection.environment === environment)
        && (dbType === 'all' || connection.dbType === dbType)
        && (!favoriteOnly || favoriteIds.has(connection.id)))
      .sort((left, right) => {
        if (left.connection.id === selectedId) return -1;
        if (right.connection.id === selectedId) return 1;
        const favoriteDifference = Number(favoriteIds.has(right.connection.id)) - Number(favoriteIds.has(left.connection.id));
        return favoriteDifference || left.index - right.index;
      })
      .map(({ connection }) => connection);
  }, [connections, dbType, environment, favoriteIds, favoriteOnly, keyword, selectedId]);

  if (connectionsLoading && connections.length === 0) {
    return (
      <Card size="small">
        <Skeleton active paragraph={{ rows: 4 }} title={{ width: '60%' }} />
      </Card>
    );
  }
  if (connectionsError && connections.length === 0) {
    return <Alert type="warning" showIcon title={connectionsError} />;
  }
  if (!connectionsReady) {
    return <Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="正在准备连接列表" /></Card>;
  }
  if (connections.length === 0) {
    return <Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据库连接" /></Card>;
  }
  return (
    <Space orientation="vertical" size={8} className="full-width">
      {connectionsError && <Alert type="warning" showIcon title={connectionsError} />}
      <div className="connection-list-filters">
        <Input.Search allowClear placeholder="搜索名称或 JDBC 地址" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
        <Select
          aria-label="筛选连接环境"
          value={environment}
          options={[{ value: 'all', label: '全部环境' }, ...[...new Set(connections.map((connection) => connection.environment))].map((value) => ({ value, label: environmentLabel(value) }))]}
          onChange={setEnvironment}
        />
        <Select
          aria-label="筛选数据库类型"
          value={dbType}
          options={[{ value: 'all', label: '全部类型' }, ...[...new Set(connections.map((connection) => connection.dbType))].map((value) => ({ value, label: dbTypeLabel(value) }))]}
          onChange={setDbType}
        />
        <Tooltip title={favoriteOnly ? '显示全部连接' : '只显示收藏连接'}>
          <Button
            type={favoriteOnly ? 'primary' : 'default'}
            icon={favoriteOnly ? <StarFilled /> : <StarOutlined />}
            aria-label={favoriteOnly ? '显示全部连接' : '只显示收藏连接'}
            onClick={() => setFavoriteOnly((current) => !current)}
          />
        </Tooltip>
      </div>
      <Text type="secondary" className="connection-filter-summary">显示 {visibleConnections.length} / {connections.length} 个连接，当前连接与收藏连接优先排列</Text>
      <div className="connection-list">
        {visibleConnections.map((connection) => (
          <div key={connection.id} className={selectedId === connection.id ? 'connection-item selected' : 'connection-item'}>
            <div className="connection-card">
              <div className="connection-main-info">
                <div className="connection-name-row">
                  <Tooltip title={favoriteIds.has(connection.id) ? '取消收藏' : '收藏连接'}>
                    <Button
                      className="connection-favorite-button"
                      type="text"
                      size="small"
                      icon={favoriteIds.has(connection.id) ? <StarFilled /> : <StarOutlined />}
                      aria-label={`${favoriteIds.has(connection.id) ? '取消收藏' : '收藏'} ${connection.name}`}
                      onClick={() => onToggleFavorite(connection.id)}
                    />
                  </Tooltip>
                  <Text strong className="ellipsis-text">{connection.name}</Text>
                  {selectedId === connection.id && <Tag color="processing">当前使用</Tag>}
                  {connection.readonly && <Tag color="orange">只读</Tag>}
                </div>
                <Space size={4} wrap className="connection-tags">
                  <Tag color="blue">{dbTypeLabel(connection.dbType)}</Tag>
                  <Tag>{environmentLabel(connection.environment)}</Tag>
                </Space>
                <Text type="secondary" className="ellipsis-text connection-url">{connection.jdbcUrl}</Text>
              </div>
              <Space size={2} className="connection-actions">
                {selectedId !== connection.id && (
                  <Tooltip title="切换使用">
                    <Button size="small" type="primary" icon={<SwapOutlined />} aria-label={`切换使用 ${connection.name}`} onClick={() => onSwitch(connection)} />
                  </Tooltip>
                )}
                <Dropdown
                  trigger={['click']}
                  menu={connectionMenu(connection, testingConnectionId === connection.id, onTest, onEdit, onDuplicate)}
                >
                  <Tooltip title="更多连接操作">
                    <Button size="small" icon={<MoreOutlined />} aria-label={`${connection.name} 更多连接操作`} />
                  </Tooltip>
                </Dropdown>
                <Popconfirm
                  title="删除连接"
                  description="确定删除该连接吗？当前未提交的数据变更会丢失；有关联备份任务的连接会被后端拒绝删除。"
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  onConfirm={() => onDelete(connection)}
                >
                  <Tooltip title="删除连接">
                    <Button size="small" danger icon={<DeleteOutlined />} aria-label={`删除连接 ${connection.name}`} />
                  </Tooltip>
                </Popconfirm>
              </Space>
            </div>
          </div>
        ))}
        {visibleConnections.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的数据库连接" />}
      </div>
    </Space>
  );
}

function connectionMenu(
  connection: Connection,
  testing: boolean,
  onTest: (connection: Connection) => void,
  onEdit: (connection: Connection) => void,
  onDuplicate: (connection: Connection) => void
): MenuProps {
  return {
    items: [
      { key: 'test', icon: <ThunderboltOutlined />, label: testing ? '正在测试连接…' : '测试连接', disabled: testing },
      { key: 'edit', icon: <EditOutlined />, label: '编辑连接' },
      { key: 'duplicate', icon: <CopyOutlined />, label: '复制连接' }
    ],
    onClick: ({ key }) => {
      if (key === 'test') onTest(connection);
      if (key === 'edit') onEdit(connection);
      if (key === 'duplicate') onDuplicate(connection);
    }
  };
}
