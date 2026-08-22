import { memo, useEffect, useRef, useState } from 'react';
import { Button, Drawer, Empty, Input, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { CopyOutlined, DownOutlined, PlusOutlined, SwapOutlined } from '@ant-design/icons';
import type { SqlHistory } from '../types';
import { formatHistoryTime } from '../utils';
import { historyLoadedSummary, SQL_HISTORY_SEARCH_DEBOUNCE_MS } from '../sqlHistoryQuery';

const { Text } = Typography;

export const SqlHistoryDrawer = memo(function SqlHistoryDrawer({
  open,
  history,
  keyword,
  loading,
  hasMore,
  atLimit,
  onClose,
  onPick,
  onSearch,
  onLoadMore
}: {
  open: boolean;
  history: SqlHistory[];
  keyword: string;
  loading: boolean;
  hasMore: boolean;
  atLimit: boolean;
  onClose: () => void;
  onPick: (history: SqlHistory, mode: 'new-tab' | 'replace-current') => void;
  onSearch: (keyword: string) => void;
  onLoadMore: () => void;
}) {
  const [draft, setDraft] = useState(keyword);
  const debounceRef = useRef<number | null>(null);
  const keywordRef = useRef(keyword);
  const wasOpenRef = useRef(false);
  keywordRef.current = keyword;

  // 只在抽屉从关闭变为打开时同步一次。若把 keyword 放进依赖，一次搜索返回后回写的
  // keyword 会把用户在等待期间继续敲进去的字符抹掉。
  useEffect(() => {
    if (open && !wasOpenRef.current) setDraft(keywordRef.current);
    wasOpenRef.current = open;
  }, [open]);

  useEffect(() => () => {
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
  }, []);

  function queueSearch(next: string) {
    setDraft(next);
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      debounceRef.current = null;
      onSearch(next);
    }, SQL_HISTORY_SEARCH_DEBOUNCE_MS);
  }

  function searchNow(next: string) {
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
    debounceRef.current = null;
    onSearch(next);
  }

  return (
    <Drawer title="SQL 执行历史" size={520} open={open} onClose={onClose}>
      <Input.Search
        allowClear
        className="history-search"
        // 过滤在服务端进行，所以能搜到保留期内的全部历史，而不只是已加载的这一页。
        placeholder="搜索 SQL 或错误信息（搜索服务端保留的全部历史）"
        value={draft}
        loading={loading}
        onChange={(event) => queueSearch(event.target.value)}
        onSearch={searchNow}
      />
      {history.length === 0 ? (
        loading
          ? <div className="history-loading"><Spin size="small" /> <Text type="secondary">正在加载历史…</Text></div>
          : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={keyword ? '没有匹配的 SQL 历史' : '暂无 SQL 历史'} />
      ) : (
        <div className="history-list">
          {history.map((item) => (
            <article className="history-item" key={item.id}>
              <div className="history-item-heading">
                <Space size={6} wrap>
                  <Tag color={item.type === 'EXPLAIN' ? 'purple' : 'blue'}>{item.type === 'EXPLAIN' ? '执行计划' : '执行'}</Tag>
                  <Tag color={item.status === 'SUCCESS' ? 'green' : 'red'}>{item.status === 'SUCCESS' ? '成功' : '失败'}</Tag>
                  <Text type="secondary">{formatHistoryTime(item.createdAt)} · {item.elapsedMs}ms</Text>
                </Space>
                <Space size={4}>
                  <Tooltip title="复制 SQL"><Button size="small" type="text" icon={<CopyOutlined />} aria-label="复制 SQL" onClick={() => void navigator.clipboard.writeText(item.sql)} /></Tooltip>
                  <Tooltip title="保留当前草稿，在新 SQL 标签中打开">
                    <Button size="small" type="primary" ghost icon={<PlusOutlined />} aria-label="在新标签打开此历史 SQL" onClick={() => onPick(item, 'new-tab')}>新标签</Button>
                  </Tooltip>
                  <Tooltip title="替换当前标签中的 SQL；有内容时会再次确认">
                    <Button size="small" type="text" icon={<SwapOutlined />} aria-label="用此历史 SQL 替换当前标签" onClick={() => onPick(item, 'replace-current')}>替换</Button>
                  </Tooltip>
                </Space>
              </div>
              <Space orientation="vertical" size={4} className="full-width">
                <pre className="history-sql">{item.sql}</pre>
                {item.errorMessage && <Text type="danger">{item.errorMessage}</Text>}
              </Space>
            </article>
          ))}
          <div className="history-footer">
            <Text type="secondary">{historyLoadedSummary(history.length, hasMore, atLimit)}</Text>
            {hasMore && (
              <Button size="small" type="link" icon={<DownOutlined />} loading={loading} onClick={onLoadMore}>
                加载更多
              </Button>
            )}
          </div>
        </div>
      )}
    </Drawer>
  );
});
