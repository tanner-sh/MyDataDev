import { useCallback, useState } from 'react';
import { Alert, Button, Popconfirm, Progress, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { DisconnectOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '../api';
import { formatIdleFor, poolCloseWarning, poolPressure, poolWarnings } from '../connectionPools';
import { useVisiblePolling } from '../hooks/useVisiblePolling';
import type { ConnectionPoolOverview, ConnectionPoolStatus } from '../types';
import { localizeError } from '../utils';

const { Text } = Typography;

const LEVEL_COLOR: Record<'ok' | 'tight' | 'full', string> = {
  ok: 'var(--ant-color-success, #52c41a)',
  tight: 'var(--ant-color-warning, #faad14)',
  full: 'var(--ant-color-error, #ff4d4f)'
};

/**
 * 远程连接池的现状。
 *
 * <p>后端为每条连接维护一个 HikariCP 池，总数有上限。这些池此前只在报出
 * REMOTE_POOL_EXHAUSTED 的那一刻才「被看见」，而那条报错既说不出名额被谁占着，也说不出
 * 哪个早就闲了 —— 于是「等一会儿再试」成了唯一的建议。</p>
 */
export function ConnectionPoolPanel({ open }: { open: boolean }) {
  const [toast, holder] = message.useMessage();
  const [overview, setOverview] = useState<ConnectionPoolOverview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [closingId, setClosingId] = useState<number | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setOverview(await api<ConnectionPoolOverview>('/connections/pools'));
      setError(null);
    } catch (failure) {
      setError(localizeError(failure));
    } finally {
      setLoading(false);
    }
  }, []);

  // 池的状态每秒都在变，但它不是主界面 —— 面板收起或标签页切走时不该继续问。
  useVisiblePolling({ enabled: open, intervalMs: 5_000, task: reload, immediate: true, resetKey: open });

  async function closePool(pool: ConnectionPoolStatus) {
    setClosingId(pool.connectionId);
    try {
      await api(`/connections/${pool.connectionId}/pool`, { method: 'DELETE' });
      toast.success(`已关闭「${pool.connectionName}」的连接池，下次请求会重新建立。`);
      await reload();
    } catch (failure) {
      toast.error(localizeError(failure));
    } finally {
      setClosingId(null);
    }
  }

  if (!overview && !error) return null;
  const pressure = overview ? poolPressure(overview) : null;

  return (
    <div className="connection-pool-panel">
      {holder}
      <Space size={8} align="center" style={{ width: '100%', justifyContent: 'space-between' }}>
        <Space size={10} align="center">
          <Text strong>连接池</Text>
          {pressure && (
            <>
              <Progress
                type="line"
                percent={Math.round((pressure.used / pressure.capacity) * 100)}
                strokeColor={LEVEL_COLOR[pressure.level]}
                showInfo={false}
                style={{ width: 120, margin: 0 }}
              />
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                {pressure.used} / {pressure.capacity} · {pressure.hint}
              </Text>
            </>
          )}
        </Space>
        <Button size="small" type="text" icon={<ReloadOutlined />} loading={loading} onClick={() => void reload()} />
      </Space>

      {error && <Alert type="error" showIcon message={error} style={{ marginTop: 'var(--space-2)' }} />}

      {overview && overview.pools.length > 0 && (
        <Table<ConnectionPoolStatus>
          rowKey="connectionId"
          size="small"
          pagination={false}
          dataSource={overview.pools}
          style={{ marginTop: 'var(--space-2)' }}
          columns={[
            {
              title: '连接',
              key: 'name',
              render: (_, pool) => {
                const warnings = poolWarnings(pool);
                return (
                  <Space size={6}>
                    <Text>{pool.connectionName}</Text>
                    {pool.tunnelAlive === true && <Tag color="blue">隧道</Tag>}
                    {warnings.map((warning) => (
                      <Tooltip key={warning} title={warning}>
                        <Tag color={warning.includes('隧道') ? 'red' : 'gold'}>
                          {warning.includes('隧道') ? '隧道已断' : `排队 ${pool.waiting}`}
                        </Tag>
                      </Tooltip>
                    ))}
                  </Space>
                );
              }
            },
            {
              title: '连接数',
              key: 'counts',
              width: 150,
              render: (_, pool) => (
                <Tooltip title={`活跃 ${pool.active} · 空闲 ${pool.idle} · 上限 ${pool.maxPoolSize}`}>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                    活跃 {pool.active} / 共 {pool.total}（上限 {pool.maxPoolSize}）
                  </Text>
                </Tooltip>
              )
            },
            {
              title: '最近使用',
              key: 'idle',
              width: 110,
              render: (_, pool) => (
                <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{formatIdleFor(pool.idleMillis)}</Text>
              )
            },
            {
              title: '',
              key: 'actions',
              width: 44,
              render: (_, pool) => (
                <Popconfirm
                  title="关闭这条连接的池？"
                  description={poolCloseWarning(pool) || '池是空闲的，下一次请求会重新建立。'}
                  okButtonProps={{ danger: Boolean(poolCloseWarning(pool)) }}
                  onConfirm={() => void closePool(pool)}
                >
                  <Tooltip title="关闭连接池">
                    <Button size="small" type="text" icon={<DisconnectOutlined />} loading={closingId === pool.connectionId} />
                  </Tooltip>
                </Popconfirm>
              )
            }
          ]}
        />
      )}
    </div>
  );
}
