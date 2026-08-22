import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Drawer, Empty, Modal, Space, Spin, Switch, Tag, Tooltip, Typography } from 'antd';
import { ReloadOutlined, StopOutlined } from '@ant-design/icons';
import { api } from '../api';
import { useVisiblePolling } from '../hooks/useVisiblePolling';
import { localizeError } from '../utils';
import { productionConfirmationHeaders } from '../productionConfirmation';
import {
  canKillSession,
  formatSessionDuration,
  isIdle,
  isLongRunning,
  orderSessions,
  sessionLabel,
  sessionSummary,
  SESSION_POLL_INTERVAL_MS,
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
export const SessionDrawer = memo(function SessionDrawer({ open, connectionId, connectionName, productionConfirmationText, onClose, onRequestConfirmation }: {
  open: boolean;
  connectionId?: number;
  connectionName?: string;
  /** 生产连接需要确认串才能终止会话。 */
  productionConfirmationText?: string;
  onClose: () => void;
  onRequestConfirmation: (action: string) => Promise<string | undefined>;
}) {
  const [page, setPage] = useState<DatabaseSessionPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [autoRefresh, setAutoRefresh] = useState(true);
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

  const sessions = orderSessions(page?.sessions || []);

  return (
    <Drawer
      title={connectionName ? `活动会话 · ${connectionName}` : '活动会话'}
      size={720}
      open={open}
      rootClassName="management-drawer"
      extra={
        <Space size={8}>
          <Tooltip title="每 5 秒自动刷新。排查锁等待时静态快照没有意义；在生产上可以关掉，避免持续查询系统视图。">
            <Space size={4}>
              <Text type="secondary">自动刷新</Text>
              <Switch size="small" checked={autoRefresh} onChange={setAutoRefresh} />
            </Space>
          </Tooltip>
          <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={() => void load()} />
        </Space>
      }
      onClose={onClose}
    >
      {error && <Alert className="session-alert" type="error" showIcon title="读取活动会话失败" description={error} />}
      {page && !page.supported && <Alert className="session-alert" type="info" showIcon title={sessionSummary(page)} />}
      {page?.supported && page.message && <Alert className="session-alert" type="warning" showIcon title={page.message} />}

      {!page && loading ? (
        <div className="session-loading"><Spin size="small" /> <Text type="secondary">正在读取活动会话…</Text></div>
      ) : sessions.length === 0 ? (
        page?.supported && !page.message ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有活动会话" /> : null
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
                    <Tag color={isLongRunning(session) ? 'warning' : undefined}>{formatSessionDuration(session.durationSeconds)}</Tag>
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
    </Drawer>
  );
});
