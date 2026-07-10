import { Alert, Button, Card, Empty, List, Popconfirm, Skeleton, Space, Tag, Tooltip, Typography } from 'antd';
import { CopyOutlined, DeleteOutlined, EditOutlined, ThunderboltOutlined } from '@ant-design/icons';
import type { Connection } from '../types';
import { dbTypeLabel, environmentLabel } from '../utils';

const { Text } = Typography;

export function ConnectionList({ connections, selectedId, connectionsLoading, connectionsError, connectionsReady, testingConnectionId, onSelect, onEdit, onTest, onDuplicate, onDelete }: {
  connections: Connection[];
  selectedId?: number;
  connectionsLoading: boolean;
  connectionsError: string;
  connectionsReady: boolean;
  testingConnectionId: number | null;
  onSelect: (connection: Connection) => void;
  onEdit: (connection: Connection) => void;
  onTest: (connection: Connection) => void;
  onDuplicate: (connection: Connection) => void;
  onDelete: (connection: Connection) => void;
}) {
  if (connectionsLoading && connections.length === 0) {
    return (
      <Card size="small">
        <Skeleton active paragraph={{ rows: 4 }} title={{ width: '60%' }} />
      </Card>
    );
  }
  if (connectionsError && connections.length === 0) {
    return <Alert type="warning" showIcon message={connectionsError} />;
  }
  if (!connectionsReady) {
    return <Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="正在准备连接列表" /></Card>;
  }
  if (connections.length === 0) {
    return <Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据库连接" /></Card>;
  }
  return (
    <Space direction="vertical" size={8} className="full-width">
      {connectionsError && <Alert type="warning" showIcon message={connectionsError} />}
      <List
        className="connection-list"
        dataSource={connections}
        renderItem={(connection) => (
          <List.Item className={selectedId === connection.id ? 'connection-item selected' : 'connection-item'}>
            <div className="connection-card">
              <button className="connection-title-button connection-main-info" aria-label={`选择连接 ${connection.name}`} onClick={() => onSelect(connection)}>
                <div className="connection-name-row">
                  <Text strong className="ellipsis-text">{connection.name}</Text>
                  {connection.readonly && <Tag color="orange">只读</Tag>}
                </div>
                <Space size={4} wrap className="connection-tags">
                  <Tag color="blue">{dbTypeLabel(connection.dbType)}</Tag>
                  <Tag>{environmentLabel(connection.environment)}</Tag>
                </Space>
                <Text type="secondary" className="ellipsis-text connection-url">{connection.jdbcUrl}</Text>
              </button>
              <Space size={2} className="connection-actions">
                <Tooltip title="测试连接">
                  <Button size="small" icon={<ThunderboltOutlined />} aria-label={`测试连接 ${connection.name}`} loading={testingConnectionId === connection.id} onClick={() => onTest(connection)} />
                </Tooltip>
                <Tooltip title="编辑连接">
                  <Button size="small" icon={<EditOutlined />} aria-label={`编辑连接 ${connection.name}`} onClick={() => onEdit(connection)} />
                </Tooltip>
                <Tooltip title="复制连接">
                  <Button size="small" icon={<CopyOutlined />} aria-label={`复制连接 ${connection.name}`} onClick={() => onDuplicate(connection)} />
                </Tooltip>
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
          </List.Item>
        )}
      />
    </Space>
  );
}
