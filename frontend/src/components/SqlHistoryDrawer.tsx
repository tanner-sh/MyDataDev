import { memo, useEffect, useRef, useState } from 'react';
import { DRAWER_WIDTH } from '../constants';
import { Button, Drawer, Empty, Input, Segmented, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { CopyOutlined, DownOutlined, PlusOutlined, SwapOutlined } from '@ant-design/icons';
import type { SqlHistory, SqlHistoryStats } from '../types';
import { api } from '../api';
import { localizeError } from '../utils';
import { formatHistoryTime } from '../utils';
import { historyLoadedSummary, historyStatsSummary, SQL_HISTORY_SEARCH_DEBOUNCE_MS } from '../sqlHistoryQuery';

const { Text } = Typography;

/**
 * 执行统计。
 *
 * <p>回答三个此前只能靠翻列表才能回答的问题：哪条最慢、哪条一直在失败、谁在跑。数据本来
 * 就都记在 sql_history 里，只是从来没被聚合过。</p>
 */
function HistoryStats({ stats, loading, error }: { stats?: SqlHistoryStats; loading: boolean; error: string }) {
  if (error) return <Text type="danger">{error}</Text>;
  if (loading && !stats) return <div className="history-loading"><Spin size="small" /> <Text type="secondary">正在统计…</Text></div>;
  if (!stats) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无统计数据" />;

  return (
    <div className="history-list">
      <Text>{historyStatsSummary(stats)}</Text>
      <StatsSection title="最慢的查询" empty="这段时间没有成功执行的查询。">
        {stats.slowest.map((item) => (
          <article className="history-item" key={item.id}>
            <div className="history-item-heading">
              <Space size={6} wrap>
                <Tag color="orange">{item.elapsedMs}ms</Tag>
                <Text type="secondary">{formatHistoryTime(item.createdAt)}{item.actor ? ` · ${item.actor}` : ''}</Text>
              </Space>
            </div>
            <pre className="history-sql">{item.sql}</pre>
          </article>
        ))}
      </StatsSection>
      <StatsSection title="失败最多的语句" empty="这段时间没有失败的执行。">
        {stats.failures.map((item) => (
          <article className="history-item" key={item.text}>
            <div className="history-item-heading">
              <Space size={6} wrap>
                <Tag color="red">{item.hits} 次</Tag>
                <Text type="secondary">最近 {formatHistoryTime(item.lastSeenAt)}</Text>
              </Space>
            </div>
            <pre className="history-sql">{item.text}</pre>
          </article>
        ))}
      </StatsSection>
      <StatsSection title="执行最多的人" empty="这段时间没有执行记录。">
        <Space size={6} wrap>
          {stats.busiest.map((item) => (
            <Tag key={item.text}>{item.text} · {item.hits} 次 · 平均 {item.averageMs}ms</Tag>
          ))}
        </Space>
      </StatsSection>
    </div>
  );
}

function StatsSection({ title, empty, children }: { title: string; empty: string; children: React.ReactNode }) {
  const hasContent = Array.isArray(children) ? children.length > 0 : Boolean(children);
  return (
    <section className="history-stats-section">
      <Text strong>{title}</Text>
      {hasContent ? children : <Text type="secondary">{empty}</Text>}
    </section>
  );
}

export const SqlHistoryDrawer = memo(function SqlHistoryDrawer({
  open,
  connectionId,
  history,
  keyword,
  scope,
  allowAll,
  loading,
  hasMore,
  atLimit,
  onClose,
  onPick,
  onSearch,
  onScopeChange,
  onLoadMore
}: {
  open: boolean;
  connectionId?: number;
  history: SqlHistory[];
  keyword: string;
  scope: 'mine' | 'all';
  allowAll: boolean;
  loading: boolean;
  hasMore: boolean;
  atLimit: boolean;
  onClose: () => void;
  onPick: (history: SqlHistory, mode: 'new-tab' | 'replace-current') => void;
  onSearch: (keyword: string) => void;
  onScopeChange: (scope: 'mine' | 'all') => void;
  onLoadMore: () => void;
}) {
  const [draft, setDraft] = useState(keyword);
  const [view, setView] = useState<'list' | 'stats'>('list');
  const [stats, setStats] = useState<SqlHistoryStats>();
  const [statsLoading, setStatsLoading] = useState(false);
  const [statsError, setStatsError] = useState('');
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

  // 统计按需取：切到那一屏才发请求，关掉抽屉再打开会重新取一次（历史一直在增长）。
  useEffect(() => {
    if (!open || view !== 'stats' || !connectionId) return;
    let active = true;
    setStatsLoading(true);
    setStatsError('');
    api<SqlHistoryStats>(`/sql/history/stats?connectionId=${connectionId}&days=7&scope=${scope}`)
      .then((result) => { if (active) setStats(result); })
      .catch((error) => { if (active) setStatsError(localizeError(error)); })
      .finally(() => { if (active) setStatsLoading(false); });
    return () => { active = false; };
  }, [connectionId, open, scope, view]);

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
    <Drawer title="SQL 执行历史" size={DRAWER_WIDTH.form} open={open} onClose={onClose}>
      <Segmented
        block
        className="history-scope"
        value={view}
        options={[{ value: 'list', label: '历史列表' }, { value: 'stats', label: '执行统计' }]}
        onChange={(value) => setView(value as 'list' | 'stats')}
      />
      {allowAll && (
        <Segmented
          block
          className="history-scope"
          value={scope}
          options={[{ value: 'mine', label: '我的历史' }, { value: 'all', label: '连接全部' }]}
          onChange={(value) => onScopeChange(value as 'mine' | 'all')}
        />
      )}
      {view === 'list' && <Input.Search
        allowClear
        className="history-search"
        // 过滤在服务端进行，所以能搜到保留期内的全部历史，而不只是已加载的这一页。
        placeholder="搜索 SQL 或错误信息（搜索服务端保留的全部历史）"
        value={draft}
        loading={loading}
        onChange={(event) => queueSearch(event.target.value)}
        onSearch={searchNow}
      />}
      {view === 'stats' ? (
        <HistoryStats stats={stats} loading={statsLoading} error={statsError} />
      ) : history.length === 0 ? (
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
