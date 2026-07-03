import { Button, Empty, List, Space, Tag, Typography } from 'antd';
import type { ActiveTable, BackupTask, Connection } from '../types';
import { backupScopeLabel, backupStatusLabel, formatFileSize, formatHistoryTime } from '../utils';

const { Text } = Typography;

export function BackupPanel({ backups, selected, activeTable, loading, onCreateDatabase, onCreateTable, onRun, onDownload }: {
  backups: BackupTask[];
  selected: Connection | null;
  activeTable: ActiveTable | null;
  loading: boolean;
  onCreateDatabase: () => void;
  onCreateTable: () => void;
  onRun: (id: number) => void;
  onDownload: (id: number) => void;
}) {
  return (
    <section className="inspector-section">
      <div className="inspector-section-header">
        <Text strong>备份任务</Text>
      </div>
      <Space direction="vertical" size={10} className="full-width">
        <Button size="small" block disabled={!selected || loading} onClick={onCreateDatabase}>创建全量备份任务</Button>
        <Button size="small" block disabled={!selected || !activeTable || loading} onClick={onCreateTable}>创建当前表备份任务</Button>
        <List
          size="small"
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无备份任务" /> }}
          dataSource={backups}
          renderItem={(backup) => (
            <List.Item
              actions={[
                <Button key="run" size="small" onClick={() => onRun(backup.id)}>执行</Button>,
                <Button key="download" size="small" disabled={!backup.lastFilePath} onClick={() => onDownload(backup.id)}>下载</Button>
              ]}
            >
              <List.Item.Meta
                title={backup.name}
                description={(
                  <Space direction="vertical" size={2}>
                    <Text type="secondary">{backupScopeLabel(backup.scope)} · {backup.cron || '手动执行'}</Text>
                    {backup.lastRunAt && <Text type="secondary">最近执行：{formatHistoryTime(backup.lastRunAt)}</Text>}
                    {backup.lastFileSize ? <Text type="secondary">文件大小：{formatFileSize(backup.lastFileSize)}</Text> : null}
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
