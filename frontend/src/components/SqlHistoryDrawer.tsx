import { Button, Drawer, Empty, List, Space, Tag, Typography } from 'antd';
import type { SqlHistory } from '../types';
import { formatHistoryTime } from '../utils';

const { Text } = Typography;

export function SqlHistoryDrawer({ open, history, onClose, onPick }: {
  open: boolean;
  history: SqlHistory[];
  onClose: () => void;
  onPick: (history: SqlHistory) => void;
}) {
  return (
    <Drawer title="SQL 执行历史" width={520} open={open} onClose={onClose}>
      <List
        dataSource={history}
        pagination={history.length > 10 ? { pageSize: 10, showSizeChanger: false, size: 'small' } : false}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 SQL 历史" /> }}
        renderItem={(item) => (
          <List.Item
            actions={[<Button key="use" size="small" onClick={() => onPick(item)}>回填</Button>]}
          >
            <List.Item.Meta
              title={(
                <Space size={6} wrap>
                  <Tag color={item.type === 'EXPLAIN' ? 'purple' : 'blue'}>{item.type === 'EXPLAIN' ? '执行计划' : '执行'}</Tag>
                  <Tag color={item.status === 'SUCCESS' ? 'green' : 'red'}>{item.status === 'SUCCESS' ? '成功' : '失败'}</Tag>
                  <Text type="secondary">{formatHistoryTime(item.createdAt)} · {item.elapsedMs}ms</Text>
                </Space>
              )}
              description={(
                <Space direction="vertical" size={4} className="full-width">
                  <pre className="history-sql">{item.sql}</pre>
                  {item.errorMessage && <Text type="danger">{item.errorMessage}</Text>}
                </Space>
              )}
            />
          </List.Item>
        )}
      />
    </Drawer>
  );
}
