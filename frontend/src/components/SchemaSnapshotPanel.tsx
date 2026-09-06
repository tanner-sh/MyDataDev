import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Collapse, Form, Input, InputNumber, List, Select, Space, Switch, Table, Tag, Tooltip, Typography } from 'antd';
import { CameraOutlined, HistoryOutlined, SaveOutlined } from '@ant-design/icons';
import { PanelEmpty } from './PanelState';
import { api } from '../api';
import { localizeError } from '../utils';
import {
  canCaptureSnapshot,
  captureQuery,
  captureResultMessage,
  DEFAULT_SNAPSHOT_SCHEDULE,
  driftAuditNotice,
  driftStatusColor,
  driftStatusLabel,
  driftSummary,
  EMPTY_SNAPSHOT_FORM,
  formatMoment,
  scheduleFormOf,
  scheduleSummary,
  snapshotLabel,
  snapshotPeriod,
  type SchemaSnapshotForm,
  type SnapshotScheduleForm
} from '../schemaSnapshot';
import type {
  Connection,
  SchemaDriftResponse,
  SchemaDriftTable,
  SchemaSnapshotCaptureResponse,
  SchemaSnapshotSummary,
  SchemaSnapshotTarget
} from '../types';

const { Text } = Typography;

/**
 * 结构快照与漂移追踪。
 *
 * <p>「结构对比」回答的是「这两个库现在一不一样」，回答不了「这个字段是什么时候加的」。生产库
 * 上后一个问题往往更急。</p>
 *
 * <p>刻意不提供「改回去」的脚本：快照是一份记录，不是一份要被回放的期望状态。要脚本请用结构
 * 对比，拿一个真正的基准环境作源端。</p>
 */
export const SchemaSnapshotPanel = memo(function SchemaSnapshotPanel({ connections, defaultConnectionId, defaultSchema }: {
  connections: Connection[];
  defaultConnectionId?: number;
  defaultSchema?: string;
}) {
  const [form, setForm] = useState<SchemaSnapshotForm>({
    ...EMPTY_SNAPSHOT_FORM,
    connectionId: defaultConnectionId,
    schemaName: defaultSchema || ''
  });
  const [timeline, setTimeline] = useState<SchemaSnapshotSummary[]>([]);
  const [target, setTarget] = useState<SchemaSnapshotTarget>();
  const [schedule, setSchedule] = useState<SnapshotScheduleForm>(DEFAULT_SNAPSHOT_SCHEDULE);
  const [capture, setCapture] = useState<SchemaSnapshotCaptureResponse>();
  const [drift, setDrift] = useState<SchemaDriftResponse>();
  const [compareTo, setCompareTo] = useState<number | 'live'>('live');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const connectionOptions = useMemo(
    () => connections.map((connection) => ({
      value: connection.id,
      label: `${connection.name}（${connection.dbType}${connection.environment === 'prod' ? ' · 生产' : ''}）`
    })),
    [connections]
  );

  const load = useCallback(async () => {
    if (!form.connectionId) return;
    setError('');
    try {
      const query = new URLSearchParams({ connectionId: String(form.connectionId) });
      if (form.schemaName.trim()) query.set('schemaName', form.schemaName.trim());
      const [snapshots, targets] = await Promise.all([
        api<SchemaSnapshotSummary[]>(`/schema-snapshots?${query.toString()}`),
        api<SchemaSnapshotTarget[]>(`/schema-snapshots/targets?connectionId=${form.connectionId}`)
      ]);
      setTimeline(snapshots);
      const scope = snapshots[0]?.schemaName || form.schemaName.trim();
      const matched = targets.find((item) => !scope || item.schemaName === scope) || targets[0];
      setTarget(matched);
      setSchedule(scheduleFormOf(matched));
    } catch (e) {
      setError(localizeError(e));
    }
  }, [form.connectionId, form.schemaName]);

  useEffect(() => { void load(); }, [load]);

  async function runCapture() {
    setBusy(true);
    setError('');
    try {
      const response = await api<SchemaSnapshotCaptureResponse>(
        `/schema-snapshots/capture?${captureQuery(form)}`, { method: 'POST' });
      setCapture(response);
      await load();
    } catch (e) {
      setCapture(undefined);
      setError(localizeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function runDrift(baselineId: number) {
    setBusy(true);
    setError('');
    try {
      const suffix = compareTo === 'live' ? '' : `?targetSnapshotId=${compareTo}`;
      setDrift(await api<SchemaDriftResponse>(`/schema-snapshots/${baselineId}/drift${suffix}`));
    } catch (e) {
      setDrift(undefined);
      setError(localizeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveSchedule() {
    if (!form.connectionId) return;
    setBusy(true);
    setError('');
    try {
      await api<SchemaSnapshotTarget>('/schema-snapshots/targets', {
        method: 'POST',
        body: JSON.stringify({
          connectionId: form.connectionId,
          schemaName: (timeline[0]?.schemaName || form.schemaName.trim()) || undefined,
          cron: schedule.cron.trim(),
          scheduleZone: schedule.scheduleZone.trim() || undefined,
          enabled: schedule.enabled,
          keepSnapshots: schedule.keepSnapshots
        })
      });
      await load();
    } catch (e) {
      setError(localizeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function removeSchedule() {
    if (!target) return;
    setBusy(true);
    try {
      await api<void>(`/schema-snapshots/targets/${target.id}`, { method: 'DELETE' });
      await load();
    } catch (e) {
      setError(localizeError(e));
    } finally {
      setBusy(false);
    }
  }

  const compareOptions = [
    { value: 'live' as const, label: '当前结构' },
    ...timeline.map((item) => ({ value: item.id, label: `${snapshotLabel(item)} · ${formatMoment(item.capturedAt)}` }))
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="快照是一份记录，不是一份可以回放的期望状态"
        description="这里只告诉你结构什么时候变的、变了什么，不生成「改回去」的脚本 —— 一个字段可能是上周有人有意加的，照着旧快照删掉它才是真正的事故。要迁移脚本请用「结构对比」，拿一个真正的基准环境作源端。"
      />

      <Form layout="vertical" size="small" className="schema-diff-endpoints">
        <Space size={12} wrap align="start">
          <Form.Item label="连接">
            <Select
              style={{ width: 240 }}
              value={form.connectionId}
              options={connectionOptions}
              placeholder="选择连接"
              onChange={(value) => { setForm({ ...form, connectionId: value }); setDrift(undefined); setCapture(undefined); }}
            />
          </Form.Item>
          <Form.Item label="Schema" tooltip="留空则用连接当前所在的 Schema/数据库">
            <Input
              style={{ width: 160 }}
              value={form.schemaName}
              placeholder="默认"
              onChange={(event) => setForm({ ...form, schemaName: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="备注" tooltip="给这份快照起个名字，例如「v2.3 上线前」">
            <Input
              style={{ width: 200 }}
              value={form.label}
              placeholder="可留空"
              onChange={(event) => setForm({ ...form, label: event.target.value })}
            />
          </Form.Item>
          <Form.Item label=" ">
            <Button
              type="primary"
              size="small"
              icon={<CameraOutlined />}
              loading={busy}
              disabled={!canCaptureSnapshot(form)}
              onClick={() => void runCapture()}
            >
              采集快照
            </Button>
          </Form.Item>
        </Space>
      </Form>

      {error && <Alert type="error" showIcon message="操作失败" description={error} />}

      {capture && (
        <Alert
          type={capture.changed ? 'success' : 'info'}
          showIcon
          message={captureResultMessage(capture)}
          description={capture.warnings.length > 0 && (
            <Space direction="vertical" size={2}>
              {capture.warnings.map((warning) => <Text key={warning} type="warning">{warning}</Text>)}
            </Space>
          )}
        />
      )}

      <Collapse
        ghost
        size="small"
        items={[{
          key: 'schedule',
          label: <Text type="secondary">定期采集 · {scheduleSummary(target)}</Text>,
          children: (
            <Space size={12} wrap align="end">
              <Form layout="vertical" size="small">
                <Space size={12} wrap align="end">
                  <Form.Item label="cron" tooltip="六段式，与备份、定时导出同一套写法">
                    <Input
                      style={{ width: 160 }}
                      value={schedule.cron}
                      onChange={(event) => setSchedule({ ...schedule, cron: event.target.value })}
                    />
                  </Form.Item>
                  <Form.Item label="时区" tooltip="留空用服务器默认时区">
                    <Input
                      style={{ width: 160 }}
                      value={schedule.scheduleZone}
                      placeholder="Asia/Shanghai"
                      onChange={(event) => setSchedule({ ...schedule, scheduleZone: event.target.value })}
                    />
                  </Form.Item>
                  <Form.Item label="保留份数" tooltip="快照存的是整份结构，不封顶会让元数据库一直长">
                    <InputNumber
                      min={1}
                      max={365}
                      value={schedule.keepSnapshots}
                      onChange={(value) => setSchedule({ ...schedule, keepSnapshots: value ?? 30 })}
                    />
                  </Form.Item>
                  <Form.Item label="启用">
                    <Switch size="small" checked={schedule.enabled} onChange={(value) => setSchedule({ ...schedule, enabled: value })} />
                  </Form.Item>
                  <Form.Item label=" ">
                    <Space size={8}>
                      <Button size="small" icon={<SaveOutlined />} loading={busy} onClick={() => void saveSchedule()}>保存</Button>
                      {target && <Button size="small" danger loading={busy} onClick={() => void removeSchedule()}>删除任务</Button>}
                    </Space>
                  </Form.Item>
                </Space>
              </Form>
            </Space>
          )
        }]}
      />

      <Space size={8} wrap align="center">
        <HistoryOutlined />
        <Text strong>时间线</Text>
        <Text type="secondary">对比到</Text>
        <Select
          size="small"
          style={{ width: 240 }}
          value={compareTo}
          options={compareOptions}
          onChange={(value) => setCompareTo(value)}
        />
      </Space>

      {timeline.length === 0 ? (
        <PanelEmpty title="还没有快照" description="先采集一份作为基线；之后每次结构变化才会新增一条记录。" />
      ) : (
        <List<SchemaSnapshotSummary>
          size="small"
          bordered
          dataSource={timeline}
          renderItem={(item) => (
            <List.Item
              key={item.id}
              actions={[
                <Button
                  key="drift"
                  size="small"
                  loading={busy}
                  disabled={compareTo === item.id}
                  onClick={() => void runDrift(item.id)}
                >
                  看变化
                </Button>
              ]}
            >
              <List.Item.Meta
                title={
                  <Space size={8} wrap>
                    <Text strong>{snapshotLabel(item)}</Text>
                    <Tag>{item.tableCount} 张表</Tag>
                    {item.capturedBy && <Text type="secondary">{item.capturedBy}</Text>}
                  </Space>
                }
                description={<Text type="secondary">{snapshotPeriod(item)}</Text>}
              />
            </List.Item>
          )}
        />
      )}

      {drift && (
        <>
          <Alert
            type={drift.tables.length === 0 ? 'success' : 'warning'}
            showIcon
            message={driftSummary(drift)}
            description={
              <Space direction="vertical" size={2}>
                <Text type="secondary">对比到：{drift.targetLabel}</Text>
                <Text type="secondary">{driftAuditNotice(drift)}</Text>
                {drift.warnings.map((warning) => <Text key={warning} type="warning">{warning}</Text>)}
              </Space>
            }
          />

          {drift.auditEvents.length > 0 && (
            <List
              size="small"
              bordered
              header={<Text strong>这段时间里本工具做过的结构变更</Text>}
              dataSource={drift.auditEvents}
              renderItem={(event) => (
                <List.Item key={`${event.at}:${event.action}`}>
                  <Space size={8} wrap>
                    <Text type="secondary">{formatMoment(event.at)}</Text>
                    <Tag>{event.action}</Tag>
                    <Text>{event.actor || '未知操作者'}</Text>
                    <Text type="secondary">{event.target}</Text>
                  </Space>
                </List.Item>
              )}
            />
          )}

          {drift.tables.length > 0 && (
            <Table<SchemaDriftTable>
              size="small"
              rowKey="tableName"
              dataSource={drift.tables}
              pagination={{ pageSize: 20, size: 'small' }}
              columns={[
                {
                  title: '变化', dataIndex: 'status', width: 100,
                  render: (_: string, row) => <Tag color={driftStatusColor(row)}>{driftStatusLabel(row)}</Tag>
                },
                { title: '表', dataIndex: 'tableName', width: 220 },
                {
                  title: '改动明细', dataIndex: 'items',
                  render: (_: unknown, row) => row.items.length === 0
                    ? <Text type="secondary">整张表</Text>
                    : (
                      <Space direction="vertical" size={2}>
                        {row.items.map((item) => (
                          <Tooltip key={`${item.category}:${item.name}`} title={`${item.source ?? '—'} → ${item.target ?? '—'}`}>
                            <Text>
                              <Tag>{item.change}</Tag>{item.category} {item.name}
                            </Text>
                          </Tooltip>
                        ))}
                      </Space>
                    )
                }
              ]}
            />
          )}
        </>
      )}
    </Space>
  );
});
