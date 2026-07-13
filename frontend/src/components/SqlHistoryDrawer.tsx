import { useEffect, useMemo, useState } from 'react';
import { Button, Drawer, Empty, Input, Pagination, Space, Tag, Tooltip, Typography } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import type { SqlHistory } from '../types';
import { formatHistoryTime } from '../utils';

const { Text } = Typography;

export function SqlHistoryDrawer({ open, history, onClose, onPick }: {
  open: boolean;
  history: SqlHistory[];
  onClose: () => void;
  onPick: (history: SqlHistory) => void;
}) {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const filteredHistory = useMemo(() => {
    const normalized = keyword.trim().toLocaleLowerCase();
    if (!normalized) return history;
    return history.filter((item) => item.sql.toLocaleLowerCase().includes(normalized) || item.errorMessage?.toLocaleLowerCase().includes(normalized));
  }, [history, keyword]);
  const visibleHistory = filteredHistory.slice((page - 1) * 10, page * 10);
  useEffect(() => setPage((current) => Math.min(current, Math.max(1, Math.ceil(filteredHistory.length / 10)))), [filteredHistory.length]);

  return (
    <Drawer title="SQL 执行历史" size={520} open={open} onClose={onClose}>
      <Input.Search allowClear className="history-search" placeholder="搜索 SQL 或错误信息" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage(1); }} />
      {filteredHistory.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={history.length === 0 ? '暂无 SQL 历史' : '没有匹配的 SQL 历史'} />
      ) : (
        <div className="history-list">
          {visibleHistory.map((item) => (
            <article className="history-item" key={item.id}>
              <div className="history-item-heading">
                <Space size={6} wrap>
                  <Tag color={item.type === 'EXPLAIN' ? 'purple' : 'blue'}>{item.type === 'EXPLAIN' ? '执行计划' : '执行'}</Tag>
                  <Tag color={item.status === 'SUCCESS' ? 'green' : 'red'}>{item.status === 'SUCCESS' ? '成功' : '失败'}</Tag>
                  <Text type="secondary">{formatHistoryTime(item.createdAt)} · {item.elapsedMs}ms</Text>
                </Space>
                <Space size={4}>
                  <Tooltip title="复制 SQL"><Button size="small" type="text" icon={<CopyOutlined />} aria-label="复制 SQL" onClick={() => void navigator.clipboard.writeText(item.sql)} /></Tooltip>
                  <Button size="small" onClick={() => onPick(item)}>回填</Button>
                </Space>
              </div>
              <Space orientation="vertical" size={4} className="full-width">
                <pre className="history-sql">{item.sql}</pre>
                {item.errorMessage && <Text type="danger">{item.errorMessage}</Text>}
              </Space>
            </article>
          ))}
          {filteredHistory.length > 10 && <Pagination size="small" current={page} pageSize={10} total={filteredHistory.length} showSizeChanger={false} onChange={setPage} />}
        </div>
      )}
    </Drawer>
  );
}
