import { Button, Empty, List, Space, Tag, Typography } from 'antd';
import type { BackupTask, Connection } from '../types';
import { backupScopeLabel, backupStatusLabel } from '../utils';

const { Text } = Typography;

export function BackupPanel({ backups, selected, loading, onCreate, onRun }: {
  backups: BackupTask[];
  selected: Connection | null;
  loading: boolean;
  onCreate: () => void;
  onRun: (id: number) => void;
}) {
  return (
    <section className="inspector-section">
      <div className="inspector-section-header">
        <Text strong>备份任务</Text>
      </div>
      <Space direction="vertical" size={10} className="full-width">
        <Button size="small" block disabled={!selected || loading} onClick={onCreate}>创建全量备份任务</Button>
        <List
          size="small"
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无备份任务" /> }}
          dataSource={backups}
          renderItem={(backup) => (
            <List.Item
              actions={[<Button key="run" size="small" onClick={() => onRun(backup.id)}>执行</Button>]}
            >
              <List.Item.Meta
                title={backup.name}
                description={(
                  <Space direction="vertical" size={2}>
                    <Text type="secondary">{backupScopeLabel(backup.scope)} · {backup.cron || '手动执行'}</Text>
                    <Tag color={backup.lastStatus === 'SUCCESS' ? 'green' : backup.lastStatus === 'FAILED' ? 'red' : 'default'}>{backupStatusLabel(backup.lastStatus)}</Tag>
                  </Space>
                )}
              />
            </List.Item>
          )}
        />
      </Space>
    </section>
  );
}
