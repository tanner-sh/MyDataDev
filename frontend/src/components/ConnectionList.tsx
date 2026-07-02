import { Alert, Button, Card, Empty, List, Popconfirm, Skeleton, Space, Tag, Typography } from 'antd';
import { CopyOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import type { Connection } from '../types';
import { dbTypeLabel, environmentLabel } from '../utils';

const { Text } = Typography;

export function ConnectionList({ connections, selectedId, connectionsLoading, connectionsError, connectionsReady, testingConnectionId, onEdit, onTest, onDuplicate, onDelete }: {
  connections: Connection[];
  selectedId?: number;
  connectionsLoading: boolean;
  connectionsError: string;
  connectionsReady: boolean;
  testingConnectionId: number | null;
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
            <div className="connection-row">
              <button className="connection-title-button connection-main-info" onClick={() => onEdit(connection)}>
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
              <Space size={4} wrap className="connection-actions">
                <Button size="small" loading={testingConnectionId === connection.id} onClick={() => onTest(connection)}>测试</Button>
                <Button size="small" icon={<EditOutlined />} onClick={() => onEdit(connection)}>编辑</Button>
                <Button size="small" icon={<CopyOutlined />} onClick={() => onDuplicate(connection)}>复制</Button>
                <Popconfirm
                  title="删除连接"
                  description="确定删除该连接吗？有关联备份任务的连接会被后端拒绝删除。"
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  onConfirm={() => onDelete(connection)}
                >
                  <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                </Popconfirm>
              </Space>
            </div>
          </List.Item>
        )}
      />
    </Space>
  );
}
