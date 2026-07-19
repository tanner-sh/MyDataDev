import { useEffect, useState } from 'react';
import { Button, Empty, List, Pagination, Space, Spin, Tag, Typography, message } from 'antd';
import { DownloadOutlined, RedoOutlined } from '@ant-design/icons';
import { api, downloadFromUrl } from '../api';
import { API } from '../constants';
import type { BackupHistory, BackupHistoryPage, Connection } from '../types';
import { backupStatusLabel, formatFileSize, formatHistoryTime } from '../utils';

const { Text } = Typography;

export function BackupHistoryCenter({ selected, onRestore }: { selected: Connection | null; onRestore: (history: BackupHistory) => void }) {
  const [toast, holder] = message.useMessage();
  const [rows, setRows] = useState<BackupHistory[]>([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setPage(0);
    void load(0);
  }, [selected?.id]);

  async function load(nextPage: number) {
    if (!selected) {
      setRows([]);
      return;
    }
    setLoading(true);
    try {
      const result = await api<BackupHistoryPage>(`/backups/history?connectionId=${selected.id}&page=${nextPage}&pageSize=20`);
      setRows(result.items);
      setPage(result.page);
      setHasMore(result.hasMore);
    } catch (error) {
      toast.error(`加载备份历史失败：${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  }

  return <div className="backup-history-center">
    {holder}
    <Spin spinning={loading}>
      {rows.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={selected ? '暂无备份历史' : '请选择连接'} /> : <List dataSource={rows} renderItem={(history) => {
        const restorable = history.status === 'SUCCESS' && Boolean(history.filePath);
        return <List.Item actions={[
          <Button key="download" size="small" type="text" icon={<DownloadOutlined />} disabled={!history.filePath} onClick={() => downloadFromUrl(`${API}/backups/${history.taskId}/history/${history.id}/download`)}>下载</Button>,
          <Button key="restore" size="small" type="primary" ghost icon={<RedoOutlined />} disabled={!restorable} onClick={() => onRestore(history)}>恢复到…</Button>
        ]}>
          <List.Item.Meta title={<Space wrap><Tag color={statusColor(history.status)}>{backupStatusLabel(history.status)}</Tag><span>{history.fileFormat || history.backupMethod || 'SQL 备份'}</span><Text type="secondary">{formatHistoryTime(history.finishedAt || history.startedAt || '')}</Text></Space>}
            description={<Space orientation="vertical" size={1}><Text type="secondary">{history.sourceDbType || '未知源类型'} · {formatFileSize(history.fileSize || 0)}{history.checksumSha256 ? ` · SHA-256 ${history.checksumSha256.slice(0, 12)}…` : ''}</Text>{history.message && <Text type="secondary">{history.message}</Text>}</Space>} />
        </List.Item>;
      }} />}
    </Spin>
    {(page > 0 || hasMore) && <Pagination simple current={page + 1} total={(page + 1 + (hasMore ? 1 : 0)) * 20} pageSize={20} onChange={(next) => void load(next - 1)} />}
  </div>;
}

function statusColor(status: string) {
  if (status === 'SUCCESS') return 'green';
  if (status === 'FAILED') return 'red';
  if (status === 'CANCELLED') return 'orange';
  return 'blue';
}
