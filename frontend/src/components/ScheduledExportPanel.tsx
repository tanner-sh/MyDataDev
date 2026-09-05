import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  ExportOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined
} from '@ant-design/icons';
import { api } from '../api';
import {
  WEEKDAY_OPTIONS,
  browserTimeZone,
  cronFromSchedule,
  describeBackupSchedule,
  isSixFieldCron,
  scheduleFieldsFromCron,
  timeZoneOptions
} from '../backupSchedule';
import type { BackupScheduleKind, CronWeekday } from '../backupSchedule';
import { SCHEDULED_EXPORT_FORMATS, nextRunLabel, scheduledExportStatus, validateScheduledExport } from '../scheduledExport';
import type { Connection, ScheduledExportRequest, ScheduledExportResponse } from '../types';
import { formatHistoryTime, localizeError } from '../utils';
import { PanelEmpty, PanelLoading } from './PanelState';

const { Text, Paragraph } = Typography;

type ScheduledExportFormValues = {
  connectionId: number;
  name: string;
  sql: string;
  exportFormat: string;
  scheduleKind: Exclude<BackupScheduleKind, 'MANUAL'>;
  scheduleTime?: string;
  weeklyDays?: CronWeekday[];
  monthlyDay?: string;
  advancedCron?: string;
  scheduleZone: string;
  enabled: boolean;
  productionConfirmation?: string;
};

type ScheduledExportPanelProps = {
  connections: Connection[];
  defaultConnectionId?: number;
  /** 把任务的 SQL 送进 SQL 工作台 —— 在那里改和试，改好了再回来存。 */
  onOpenInSqlTab?: (sql: string, title: string) => void;
};

/**
 * 定时导出。
 *
 * <p>调度此前只服务备份一件事，而「每天早上把这条查询导成 CSV」是同一套东西。这里复用
 * 备份那套 cron 编辑器与时区语义，执行则走现成的导出管线。</p>
 */
export function ScheduledExportPanel({ connections, defaultConnectionId, onOpenInSqlTab }: ScheduledExportPanelProps) {
  const [form] = Form.useForm<ScheduledExportFormValues>();
  const [toast, holder] = message.useMessage();
  const [tasks, setTasks] = useState<ScheduledExportResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [runningId, setRunningId] = useState<number | null>(null);

  const scheduleKind = Form.useWatch('scheduleKind', form) || 'DAILY';
  const scheduleZone = Form.useWatch('scheduleZone', form) || browserTimeZone();
  const editingConnectionId = Form.useWatch('connectionId', form);
  const zoneOptions = useMemo(() => timeZoneOptions(scheduleZone), [scheduleZone]);
  const editingConnection = connections.find((item) => item.id === editingConnectionId);
  const isProduction = editingConnection?.environment === 'prod';

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setTasks(await api<ScheduledExportResponse[]>('/scheduled-queries'));
      setError(null);
    } catch (failure) {
      setError(localizeError(failure));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  function openEditor(existing?: ScheduledExportResponse) {
    const task = existing?.task;
    const parsed = scheduleFieldsFromCron(task?.cron);
    setEditingId(task?.id ?? null);
    form.resetFields();
    form.setFieldsValue({
      connectionId: task?.connectionId ?? defaultConnectionId ?? connections[0]?.id,
      name: task?.name ?? '',
      sql: task?.sql ?? '',
      exportFormat: task?.exportFormat ?? 'csv',
      // 没有「仅手动」这一档：没有周期的定时任务永远不会跑。
      scheduleKind: parsed.scheduleKind === 'MANUAL' ? 'DAILY' : parsed.scheduleKind,
      scheduleTime: parsed.scheduleTime,
      weeklyDays: parsed.weeklyDays,
      monthlyDay: parsed.monthlyDay,
      advancedCron: parsed.advancedCron,
      scheduleZone: task?.scheduleZone || browserTimeZone(),
      enabled: task?.enabled ?? true,
      productionConfirmation: ''
    });
    setEditorOpen(true);
  }

  async function save() {
    const values = await form.validateFields();
    const connection = connections.find((item) => item.id === values.connectionId);
    const cron = cronFromSchedule({
      scheduleKind: values.scheduleKind,
      scheduleTime: values.scheduleTime,
      weeklyDays: values.weeklyDays,
      monthlyDay: values.monthlyDay,
      advancedCron: values.advancedCron
    });
    const problem = validateScheduledExport({ ...values, cron }, connection);
    if (problem) {
      toast.error(problem);
      return;
    }
    const request: ScheduledExportRequest = {
      connectionId: values.connectionId,
      name: values.name.trim(),
      sql: values.sql.trim(),
      exportFormat: values.exportFormat,
      cron,
      scheduleZone: values.scheduleZone,
      enabled: values.enabled,
      productionConfirmation: values.productionConfirmation?.trim() || undefined
    };
    setSaving(true);
    try {
      await api<ScheduledExportResponse>(editingId ? `/scheduled-queries/${editingId}` : '/scheduled-queries', {
        method: editingId ? 'PUT' : 'POST',
        body: JSON.stringify(request)
      });
      toast.success(editingId ? '定时导出任务已保存' : '定时导出任务已创建');
      setEditorOpen(false);
      await reload();
    } catch (failure) {
      toast.error(localizeError(failure));
    } finally {
      setSaving(false);
    }
  }

  async function runNow(id: number) {
    setRunningId(id);
    try {
      const result = await api<ScheduledExportResponse>(`/scheduled-queries/${id}/run`, { method: 'POST' });
      // 立即运行与到点自动运行走同一条路：失败也记在任务上，所以这里读回来的状态才是结论。
      if (result.task.lastStatus === 'SUCCESS') toast.success(result.task.lastMessage || '导出完成');
      else toast.error(result.task.lastMessage || '导出失败');
      await reload();
    } catch (failure) {
      toast.error(localizeError(failure));
    } finally {
      setRunningId(null);
    }
  }

  async function toggle(id: number, enabled: boolean) {
    try {
      await api<ScheduledExportResponse>(`/scheduled-queries/${id}/enabled?enabled=${enabled}`, { method: 'PATCH' });
      await reload();
    } catch (failure) {
      toast.error(localizeError(failure));
    }
  }

  async function remove(id: number) {
    try {
      await api<void>(`/scheduled-queries/${id}`, { method: 'DELETE' });
      toast.success('任务已删除');
      await reload();
    } catch (failure) {
      toast.error(localizeError(failure));
    }
  }

  if (loading && !tasks.length) return <PanelLoading text="正在读取定时导出任务…" />;

  return (
    <div className="management-section">
      {holder}
      <header className="management-section-header">
        <Text strong>定时导出</Text>
        <Space size={8}>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void reload()} loading={loading}>刷新</Button>
          <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openEditor()} disabled={!connections.length}>
            新建任务
          </Button>
        </Space>
      </header>

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 'var(--space-md)' }} />}

      {!tasks.length && !error ? (
        <PanelEmpty
          title="还没有定时导出任务"
          description="定时跑一条查询并把结果写成文件 —— 产物留在服务端的导出目录里，只保留最近若干份。"
          action={<Button type="primary" icon={<PlusOutlined />} onClick={() => openEditor()} disabled={!connections.length}>新建任务</Button>}
        />
      ) : (
        <Table<ScheduledExportResponse>
          rowKey={(row) => row.task.id}
          size="small"
          dataSource={tasks}
          pagination={false}
          columns={[
            {
              title: '任务',
              key: 'name',
              render: (_, row) => (
                <Space direction="vertical" size={0}>
                  <Text strong>{row.task.name}</Text>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                    {connections.find((item) => item.id === row.task.connectionId)?.name || `连接 #${row.task.connectionId}`}
                    {' · '}
                    {row.task.exportFormat.toUpperCase()}
                  </Text>
                </Space>
              )
            },
            {
              title: '执行计划',
              key: 'cron',
              render: (_, row) => (
                <Space direction="vertical" size={0}>
                  <Text>{describeBackupSchedule(row.task.cron)}</Text>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                    {row.task.zoneId} · 下次 {nextRunLabel(row.task, row.nextRunAt)}
                  </Text>
                </Space>
              )
            },
            {
              title: '上次执行',
              key: 'last',
              render: (_, row) => {
                const status = scheduledExportStatus(row.task);
                return (
                  <Space direction="vertical" size={0}>
                    <Space size={6}>
                      <Tag color={status.color}>{status.label}</Tag>
                      {row.task.lastRunAt && <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{formatHistoryTime(row.task.lastRunAt)}</Text>}
                    </Space>
                    {row.task.lastMessage && (
                      <Tooltip title={row.task.lastMessage}>
                        <Text type="secondary" ellipsis style={{ fontSize: 'var(--text-xs)', maxWidth: 260 }}>
                          {row.task.lastFile ? row.task.lastFile.split(/[\\/]/).pop() : row.task.lastMessage}
                        </Text>
                      </Tooltip>
                    )}
                  </Space>
                );
              }
            },
            {
              title: '启用',
              key: 'enabled',
              width: 72,
              render: (_, row) => (
                <Switch size="small" checked={row.task.enabled} onChange={(checked) => void toggle(row.task.id, checked)} />
              )
            },
            {
              title: '操作',
              key: 'actions',
              width: 190,
              render: (_, row) => (
                <Space size={4}>
                  <Tooltip title="立即运行一次">
                    <Button
                      size="small"
                      icon={<PlayCircleOutlined />}
                      loading={runningId === row.task.id}
                      onClick={() => void runNow(row.task.id)}
                    />
                  </Tooltip>
                  {onOpenInSqlTab && (
                    <Tooltip title="在 SQL 工作台打开">
                      <Button size="small" icon={<ExportOutlined />} onClick={() => onOpenInSqlTab(row.task.sql, row.task.name)} />
                    </Tooltip>
                  )}
                  <Tooltip title="编辑">
                    <Button size="small" icon={<EditOutlined />} onClick={() => openEditor(row)} />
                  </Tooltip>
                  <Popconfirm title="删除这条定时导出任务？" description="已导出的文件不会被删除。" onConfirm={() => void remove(row.task.id)}>
                    <Button size="small" danger icon={<DeleteOutlined />} />
                  </Popconfirm>
                </Space>
              )
            }
          ]}
        />
      )}

      <Modal
        title={editingId ? '编辑定时导出' : '新建定时导出'}
        open={editorOpen}
        onCancel={() => setEditorOpen(false)}
        onOk={() => void save()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={640}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" size="small">
          <Form.Item label="连接" name="connectionId" rules={[{ required: true, message: '请选择连接' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              // 换连接等于换一套表，已经存在的任务不允许改 —— 后端也会拒绝。
              disabled={editingId !== null}
              options={connections.map((item) => ({ value: item.id, label: item.name }))}
            />
          </Form.Item>
          <Form.Item label="任务名" name="name" rules={[{ required: true, whitespace: true, message: '请填写任务名' }]}
            extra="任务名会成为导出文件名的前缀。">
            <Input placeholder="每日订单明细" maxLength={120} />
          </Form.Item>
          <Form.Item label="查询" name="sql" rules={[{ required: true, whitespace: true, message: '请填写查询' }]}
            extra="只支持单条查询语句：无人值守的写操作出错时，等发现已经晚了。">
            <Input.TextArea rows={5} placeholder="select * from orders where created_at >= current_date - 1" />
          </Form.Item>
          <Form.Item label="导出格式" name="exportFormat" rules={[{ required: true, message: '请选择导出格式' }]}>
            <Select options={SCHEDULED_EXPORT_FORMATS} />
          </Form.Item>
          <Form.Item label="执行频率" name="scheduleKind" rules={[{ required: true, message: '请选择执行频率' }]}>
            <Select
              options={[
                { value: 'DAILY', label: '每天' },
                { value: 'WEEKLY', label: '每周' },
                { value: 'MONTHLY', label: '每月' },
                { value: 'ADVANCED', label: '高级 Cron' }
              ]}
            />
          </Form.Item>
          {scheduleKind !== 'ADVANCED' && (
            <Form.Item
              label="执行时间"
              name="scheduleTime"
              rules={[
                { required: true, message: '请选择执行时间' },
                { pattern: /^(?:[01]\d|2[0-3]):[0-5]\d$/, message: '请输入有效的 24 小时时间' }
              ]}
            >
              <Input type="time" />
            </Form.Item>
          )}
          {scheduleKind === 'WEEKLY' && (
            <Form.Item label="执行日" name="weeklyDays" rules={[{ required: true, type: 'array', min: 1, message: '请至少选择一天' }]}>
              <Checkbox.Group options={WEEKDAY_OPTIONS} />
            </Form.Item>
          )}
          {scheduleKind === 'MONTHLY' && (
            <Form.Item label="执行日" name="monthlyDay" rules={[{ required: true, message: '请选择每月执行日' }]}>
              <Select options={[
                ...Array.from({ length: 31 }, (_, index) => ({ value: String(index + 1), label: `${index + 1} 日` })),
                { value: 'L', label: '最后一天' }
              ]} />
            </Form.Item>
          )}
          {scheduleKind === 'ADVANCED' && (
            <Form.Item
              label="Cron 表达式"
              name="advancedCron"
              extra="使用 Spring 六段式 Cron（秒 分 时 日 月 周）。"
              rules={[
                { required: true, whitespace: true, message: '请输入 Cron 表达式' },
                { validator: (_, value?: string) => isSixFieldCron(value) ? Promise.resolve() : Promise.reject(new Error('Cron 表达式应包含 6 个字段')) }
              ]}
            >
              <Input placeholder="0 0 6 * * *" />
            </Form.Item>
          )}
          <Form.Item
            label="执行时区"
            name="scheduleZone"
            rules={[{ required: true, message: '请选择执行时区' }]}
            extra="执行计划按所选时区触发，与应用服务器所在时区无关。"
          >
            <Select showSearch options={zoneOptions} />
          </Form.Item>
          <Form.Item name="enabled" valuePropName="checked">
            <Checkbox>保存后启用</Checkbox>
          </Form.Item>
          {isProduction && (
            <Form.Item
              label={`生产确认：请输入连接名「${editingConnection?.name}」`}
              name="productionConfirmation"
              rules={[{ required: true, message: '生产连接需要输入连接名确认' }]}
              extra="定时任务没有交互确认的机会，这次确认在创建时完成，之后每次运行都凭它放行。"
            >
              <Input placeholder={editingConnection?.name} autoComplete="off" />
            </Form.Item>
          )}
          <Paragraph type="secondary" style={{ fontSize: 'var(--text-xs)', marginBottom: 0 }}>
            产物写在服务端的导出目录（<code>app.scheduled-query.directory</code>，默认 <code>./exports</code>），
            每个任务只保留最近若干份；每次运行都会记进审计与 SQL 历史。
          </Paragraph>
        </Form>
      </Modal>
    </div>
  );
}
