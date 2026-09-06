import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PanelEmpty } from './PanelState';
import { Alert, Button, Modal, Space, Spin, Switch, Tag, Tooltip, Typography } from 'antd';
import { LockOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import { api } from '../api';
import { useVisiblePolling } from '../hooks/useVisiblePolling';
import { localizeError } from '../utils';
import { productionConfirmationHeaders } from '../productionConfirmation';
import {
  analyzeBlocking,
  blockedCountLabel,
  blockingHighlights,
  blockingSummary,
  canKillSession,
  filterRunningSessions,
  isIdle,
  isLongRunning,
  orderSessions,
  sessionDurationLabel,
  sessionLabel,
  sessionSummary,
  SESSION_POLL_INTERVAL_MS,
  type BlockingNode,
  type DatabaseSession,
  type DatabaseSessionPage
} from '../databaseSessions';

const { Text } = Typography;

/**
 * 目标库的活动会话面板。
 *
 * 默认自动刷新（5 秒）—— 排查锁等待时静态快照没有意义；可以关掉，避免在生产上持续查询
 * 系统视图。
 */
/** 活动会话面板。与审计日志同样改成纯内容，由管理抽屉统一承载外壳。 */
export const SessionPanel = memo(function SessionPanel({ open, connectionId, connectionName, productionConfirmationText, onRequestConfirmation }: {
  open: boolean;
  connectionId?: number;
  connectionName?: string;
  /** 生产连接需要确认串才能终止会话。 */
  productionConfirmationText?: string;
  onRequestConfirmation: (action: string) => Promise<string | undefined>;
}) {
  const [page, setPage] = useState<DatabaseSessionPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [autoRefresh, setAutoRefresh] = useState(true);
  // 默认展示全部：连接总数本身就是有用的信息（「怎么开了这么多连接」）。要排查锁等待时
  // 再切到只看执行中。
  const [runningOnly, setRunningOnly] = useState(false);
  const [pendingKill, setPendingKill] = useState<DatabaseSession | null>(null);
  const requestSeqRef = useRef(0);

  const load = useCallback(async () => {
    if (!connectionId) return;
    const requestId = ++requestSeqRef.current;
    setLoading(true);
    try {
      const result = await api<DatabaseSessionPage>(`/sessions?connectionId=${connectionId}`);
      if (requestId !== requestSeqRef.current) return;
      setPage(result);
      setError('');
    } catch (e) {
      if (requestId !== requestSeqRef.current) return;
      setError(localizeError(e));
    } finally {
      if (requestId === requestSeqRef.current) setLoading(false);
    }
  }, [connectionId]);

  useEffect(() => {
    if (!open) return;
    setPage(null);
    setError('');
    void load();
  }, [load, open]);

  useVisiblePolling({
    enabled: open && autoRefresh && Boolean(connectionId) && (page?.supported ?? true),
    intervalMs: SESSION_POLL_INTERVAL_MS,
    resetKey: connectionId,
    task: load
  });

  async function kill(session: DatabaseSession) {
    setPendingKill(null);
    if (!connectionId || !session.sessionId) return;
    try {
      const confirmation = productionConfirmationText ? await onRequestConfirmation('终止数据库会话') : undefined;
      if (productionConfirmationText && !confirmation) return;
      await api<{ ok: boolean }>(
        `/sessions/${encodeURIComponent(session.sessionId)}/kill?connectionId=${connectionId}`,
        // 走共享助手而不是就地拼头：HTTP 头值只能是 ISO-8859-1，中文连接名必须先编码。
        { method: 'POST', headers: productionConfirmationHeaders(confirmation) }
      );
      await load();
    } catch (e) {
      setError(localizeError(e));
    }
  }

  const sessions = filterRunningSessions(orderSessions(page?.sessions || []), runningOnly);
  // 阻塞关系是排查锁等待时唯一真正要看的东西，所以它算在 page 变化上而不是跟着筛选走。
  const blocking = useMemo(
    () => analyzeBlocking({ blocks: page?.blocks || [], sessions: page?.sessions || [] }),
    [page]
  );
  const highlights = useMemo(() => blockingHighlights(page?.blocks || []), [page]);

  /**
   * 等待链。
   *
   * <p>自绘嵌套结构而不是引 Tree 组件：这里要展示的是「根在哪、它堵住了几个」，节点内容是
   * 会话卡片而不是一行文字，套用树组件反而要跟它的渲染约定较劲。</p>
   */
  function renderNode(node: BlockingNode, depth: number) {
    const killable = page && canKillSession(page, { sessionId: node.sessionId });
    return (
      <div className="session-block-node" key={`${depth}-${node.sessionId}`}>
        <div className={`session-block-row${depth === 0 ? ' is-root' : ''}`}>
          <Space size={6} wrap>
            {depth === 0 ? <Tag color="red">源头</Tag> : <Tag>等待中</Tag>}
            <Text strong>#{node.sessionId}</Text>
            {node.session && <Text type="secondary">{sessionLabel(node.session)}</Text>}
            {!node.session && (
              <Tooltip title="这个会话不在上面的列表里：列表有条数上限，账号权限也可能只让人看到自己的会话">
                <Tag color="default">不在列表中</Tag>
              </Tooltip>
            )}
            {node.waitObject && <Tag color="orange">等 {node.waitObject}</Tag>}
            {node.waitSeconds != null && <Tag>{sessionDurationLabel({ durationSeconds: node.waitSeconds, sql: 'waiting' })}</Tag>}
            {blockedCountLabel(node) && <Tag color="volcano">{blockedCountLabel(node)}</Tag>}
            {blocking.cycleSessionIds.includes(node.sessionId) && <Tag color="red">互相等待</Tag>}
          </Space>
          {depth === 0 && killable && (
            <Tooltip title="终止这个源头会话，它下游等待的会话就能继续">
              <Button
                size="small"
                danger
                type="text"
                icon={<StopOutlined />}
                onClick={() => setPendingKill(node.session || { sessionId: node.sessionId })}
              >
                终止
              </Button>
            </Tooltip>
          )}
        </div>
        {node.blockedSql && <pre className="session-item-sql">{node.blockedSql}</pre>}
        {node.children.length > 0 && (
          <div className="session-block-children">{node.children.map((child) => renderNode(child, depth + 1))}</div>
        )}
      </div>
    );
  }

  return (
    <div className="management-section">
      <header className="management-section-header">
        <Text strong>{connectionName ? `活动会话 · ${connectionName}` : '活动会话'}</Text>
        <Space size={8}>
          <Tooltip title="只保留正在执行语句的会话。其余多是各客户端连接池常驻的空闲连接，不代表数据库在忙。">
            <Space size={4}>
              <Text type="secondary">只看执行中</Text>
              <Switch size="small" checked={runningOnly} onChange={setRunningOnly} />
            </Space>
          </Tooltip>
          <Tooltip title="每 5 秒自动刷新。排查锁等待时静态快照没有意义；在生产上可以关掉，避免持续查询系统视图。">
            <Space size={4}>
              <Text type="secondary">自动刷新</Text>
              <Switch size="small" checked={autoRefresh} onChange={setAutoRefresh} />
            </Space>
          </Tooltip>
          <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={() => void load()} />
        </Space>
      </header>
      {error && <Alert className="session-alert" type="error" showIcon title="读取活动会话失败" description={error} />}
      {page && !page.supported && <Alert className="session-alert" type="info" showIcon title={sessionSummary(page)} />}
      {page?.supported && page.message && <Alert className="session-alert" type="warning" showIcon title={page.message} />}

      {page?.supported && (
        <section className="session-blocking">
          <header className="session-blocking-header">
            <Space size={6}>
              <LockOutlined />
              <Text strong>锁等待</Text>
            </Space>
            <Text type={blocking.cycleSessionIds.length > 0 ? 'danger' : 'secondary'}>
              {blockingSummary(page, blocking)}
            </Text>
          </header>
          {blocking.trees.length > 0 && (
            <div className="session-block-tree">{blocking.trees.map((tree) => renderNode(tree, 0))}</div>
          )}
        </section>
      )}

      {!page && loading ? (
        <div className="session-loading"><Spin size="small" /> <Text type="secondary">正在读取活动会话…</Text></div>
      ) : sessions.length === 0 ? (
        page?.supported && !page.message
          ? <PanelEmpty title={runningOnly ? '当前没有正在执行的会话' : '当前没有活动会话'} description={runningOnly ? '关掉「只看执行中」可以看到全部连接。' : undefined} />
          : null
      ) : (
        <>
          <Text type="secondary" className="session-summary">{sessionSummary(page!)}</Text>
          <div className="session-list">
            {sessions.map((session, index) => (
              <article
                className={`session-item${isLongRunning(session) ? ' is-long' : ''}${isIdle(session) ? ' is-idle' : ''}`}
                key={`${session.sessionId || 'session'}-${index}`}
              >
                <div className="session-item-heading">
                  <Space size={6} wrap>
                    <Text strong>{sessionLabel(session)}</Text>
                    {session.sessionId && <Tag>#{session.sessionId}</Tag>}
                    {session.database && <Tag color="blue">{session.database}</Tag>}
                    {session.state && <Tag color={isIdle(session) ? undefined : 'green'}>{session.state}</Tag>}
                    <Tag color={isLongRunning(session) ? 'warning' : undefined}>{sessionDurationLabel(session)}</Tag>
                    {session.sessionId && highlights.blocking.has(session.sessionId) && (
                      <Tooltip title="其他会话正在等它释放锁"><Tag color="volcano">阻塞他人</Tag></Tooltip>
                    )}
                    {session.sessionId && highlights.blocked.has(session.sessionId) && (
                      <Tooltip title="它正在等别的会话释放锁"><Tag color="orange">等待锁</Tag></Tooltip>
                    )}
                  </Space>
                  {canKillSession(page!, session) && (
                    <Tooltip title="终止该会话">
                      <Button size="small" danger type="text" icon={<StopOutlined />} onClick={() => setPendingKill(session)}>终止</Button>
                    </Tooltip>
                  )}
                </div>
                {session.sql ? <pre className="session-item-sql">{session.sql}</pre> : <Text type="secondary">当前没有正在执行的语句</Text>}
              </article>
            ))}
          </div>
        </>
      )}

      <Modal
        open={pendingKill !== null}
        title={pendingKill ? `终止会话 #${pendingKill.sessionId}？` : undefined}
        okText="终止会话"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        onOk={() => pendingKill && void kill(pendingKill)}
        onCancel={() => setPendingKill(null)}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          该会话中未提交的事务会被数据库回滚，正在执行的语句会被中断。
        </Typography.Paragraph>
        {pendingKill?.sql && <pre className="session-item-sql">{pendingKill.sql}</pre>}
      </Modal>
    </div>
  );
});
