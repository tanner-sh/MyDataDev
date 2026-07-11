import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Collapse,
  Divider,
  Dropdown,
  Empty,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography
} from 'antd';
import {
  CheckCircleOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  HistoryOutlined,
  LeftOutlined,
  MoreOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  RightOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import type {
  ActiveTable,
  BackupEditorRequest,
  BackupHistory,
  BackupMethod,
  BackupSchedulePreview,
  BackupScope,
  BackupTableTargetQuery,
  BackupTargetPage,
  BackupTargetQuery,
  BackupTask,
  BackupTaskForm,
  Connection
} from '../types';
import {
  cronFromSchedule,
  describeBackupSchedule,
  isSixFieldCron,
  scheduleFieldsFromCron,
  WEEKDAY_OPTIONS
} from '../backupSchedule';
import type { BackupScheduleFields, BackupScheduleKind, CronWeekday } from '../backupSchedule';
import {
  backupMethodLabel,
  backupScopeLabel,
  backupStatusLabel,
  backupTargetLabel,
  formatFileSize,
  formatHistoryTime,
  normalizeBackupScope
} from '../utils';

const { Text } = Typography;
const TARGET_PAGE_SIZE = 30;

export type BackupPanelProps = {
  backups: BackupTask[];
  selected: Connection | null;
  activeTable: ActiveTable | null;
  loading: boolean;
  namespaceKind?: 'SCHEMA' | 'CATALOG';
  editorRequest?: BackupEditorRequest | null;
  onLoadNamespaces?: (query: BackupTargetQuery) => Promise<BackupTargetPage>;
  onLoadTables?: (query: BackupTableTargetQuery) => Promise<BackupTargetPage>;
  onPreviewSchedule?: (cron: string) => Promise<BackupSchedulePreview>;
  onSave: (id: number | null, form: BackupTaskForm) => Promise<void>;
  onToggle: (id: number, enabled: boolean) => Promise<void>;
  onDelete: (id: number, deleteFile: boolean) => Promise<void>;
  onRun: (id: number) => void;
  onDownload: (id: number) => void;
  onLoadHistory: (id: number) => Promise<BackupHistory[]>;
  onDeleteHistory: (taskId: number, historyId: number, deleteFile: boolean) => Promise<void>;
  onDownloadHistory: (taskId: number, historyId: number) => void;
};

type BackupTaskEditorValues = BackupScheduleFields & {
  name: string;
  scope: BackupScope;
  schemaName?: string;
  tableNames?: string[];
  backupMethod?: BackupMethod | string;
  toolPath?: string;
  extraArgs?: string;
  nativeConnectName?: string;
  enabled: boolean;
};

export function BackupPanel({
  backups,
  selected,
  activeTable,
  loading,
  namespaceKind,
  editorRequest,
  onLoadNamespaces,
  onLoadTables,
  onPreviewSchedule,
  onSave,
  onToggle,
  onDelete,
  onRun,
  onDownload,
  onLoadHistory,
  onDeleteHistory,
  onDownloadHistory
}: BackupPanelProps) {
  const [form] = Form.useForm<BackupTaskEditorValues>();
  const [modal, modalContextHolder] = Modal.useModal();
  const initialDraftRef = useRef('');
  const handledEditorRequestRef = useRef<string | number | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [detectedNamespaceKind, setDetectedNamespaceKind] = useState<'SCHEMA' | 'CATALOG' | undefined>(namespaceKind);
  const [deleteFiles, setDeleteFiles] = useState<Record<number, boolean>>({});
  const [deleteTarget, setDeleteTarget] = useState<BackupTask | null>(null);
  const [deletingTaskId, setDeletingTaskId] = useState<number | null>(null);
  const [runningTaskId, setRunningTaskId] = useState<number | null>(null);
  const [taskAction, setTaskAction] = useState<string | null>(null);
  const [historyTask, setHistoryTask] = useState<BackupTask | null>(null);
  const [histories, setHistories] = useState<BackupHistory[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [deletingHistoryId, setDeletingHistoryId] = useState<number | null>(null);
  const [deleteHistoryFiles, setDeleteHistoryFiles] = useState<Record<number, boolean>>({});
  const [schedulePreview, setSchedulePreview] = useState<BackupSchedulePreview | null>(null);
  const [schedulePreviewError, setSchedulePreviewError] = useState('');
  const [schedulePreviewLoading, setSchedulePreviewLoading] = useState(false);
  const [listSchedulePreviews, setListSchedulePreviews] = useState<Record<string, BackupSchedulePreview>>({});

  const scope = Form.useWatch('scope', form) || 'DATABASE';
  const schemaName = Form.useWatch('schemaName', form) || '';
  const backupMethod = Form.useWatch('backupMethod', form) || 'SQL';
  const scheduleKind = Form.useWatch('scheduleKind', form) || 'MANUAL';
  const scheduleTime = Form.useWatch('scheduleTime', form);
  const weeklyDays = Form.useWatch('weeklyDays', form);
  const monthlyDay = Form.useWatch('monthlyDay', form);
  const advancedCron = Form.useWatch('advancedCron', form);
  const nativeBackup = backupMethod !== 'SQL';
  const effectiveNamespaceKind = detectedNamespaceKind || namespaceKind;
  const namespaceLabel = effectiveNamespaceKind === 'CATALOG' ? '数据库' : 'Schema';
  const generatedCron = useMemo(() => cronFromSchedule({
    scheduleKind,
    scheduleTime,
    weeklyDays,
    monthlyDay,
    advancedCron
  }), [advancedCron, monthlyDay, scheduleKind, scheduleTime, weeklyDays]);
  const scheduledCronKey = useMemo(() => [...new Set(backups
    .filter((task) => task.cron?.trim() && (!task.zoneId || (task.enabled && !task.nextRunAt)))
    .map((task) => task.cron!.trim()))]
    .sort()
    .join('\u0000'), [backups]);

  const namespaceLoader = useCallback(async (query: BackupTargetQuery) => {
    if (!onLoadNamespaces) return emptyTargetPage(query.page, query.pageSize);
    const page = await onLoadNamespaces(query);
    if (page.namespaceKind) setDetectedNamespaceKind(page.namespaceKind);
    return page;
  }, [onLoadNamespaces]);

  const tableLoader = useCallback(async (query: BackupTargetQuery) => {
    if (!onLoadTables || !schemaName) return emptyTargetPage(query.page, query.pageSize);
    const page = await onLoadTables({ ...query, namespaceName: schemaName });
    if (page.namespaceKind) setDetectedNamespaceKind(page.namespaceKind);
    return page;
  }, [onLoadTables, schemaName]);

  useEffect(() => {
    setDetectedNamespaceKind(namespaceKind);
  }, [namespaceKind, selected?.id]);

  useEffect(() => {
    if (!loading) setRunningTaskId(null);
  }, [loading]);

  useEffect(() => {
    if (!onPreviewSchedule || !scheduledCronKey) {
      setListSchedulePreviews({});
      return;
    }
    let cancelled = false;
    const crons = scheduledCronKey.split('\u0000');
    void Promise.all(crons.map(async (cron) => {
      try {
        return [cron, await onPreviewSchedule(cron)] as const;
      } catch {
        return null;
      }
    })).then((entries) => {
      if (cancelled) return;
      setListSchedulePreviews(Object.fromEntries(entries.filter((entry): entry is readonly [string, BackupSchedulePreview] => Boolean(entry))));
    });
    return () => {
      cancelled = true;
    };
  }, [onPreviewSchedule, scheduledCronKey]);

  useEffect(() => {
    if (!editorRequest || !selected || handledEditorRequestRef.current === editorRequest.requestId) return;
    handledEditorRequestRef.current = editorRequest.requestId;
    const openRequestedEditor = () => openTableTask(editorRequest.target, editorRequest.name);
    if (editorOpen && currentDraft() !== initialDraftRef.current) {
      modal.confirm({
        title: '切换到新的备份任务？',
        content: '当前未保存的备份任务配置将被替换。',
        okText: '替换并继续',
        cancelText: '继续编辑当前任务',
        okButtonProps: { danger: true },
        onOk: openRequestedEditor
      });
      return;
    }
    openRequestedEditor();
    // The request id is the imperative edge. Other editor state must not replay it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editorRequest?.requestId, selected?.id]);

  useEffect(() => {
    setSchedulePreview(null);
    setSchedulePreviewError('');
    setSchedulePreviewLoading(false);
    if (!editorOpen || scheduleKind === 'MANUAL' || !generatedCron) return;
    if (!isSixFieldCron(generatedCron)) {
      if (scheduleKind === 'ADVANCED') setSchedulePreviewError('Cron 表达式应包含 6 个字段');
      return;
    }
    if (!onPreviewSchedule) return;

    let cancelled = false;
    const timer = window.setTimeout(async () => {
      setSchedulePreviewLoading(true);
      try {
        const preview = await onPreviewSchedule(generatedCron);
        if (!cancelled) setSchedulePreview(preview);
      } catch (error) {
        if (!cancelled) setSchedulePreviewError((error as Error).message || '无法预览后续执行时间');
      } finally {
        if (!cancelled) setSchedulePreviewLoading(false);
      }
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [editorOpen, generatedCron, onPreviewSchedule, scheduleKind]);

  function currentDraft() {
    return JSON.stringify(form.getFieldsValue(true));
  }

  function setEditorDraft(values: BackupTaskEditorValues, id: number | null) {
    setEditingId(id);
    form.resetFields();
    form.setFieldsValue(values);
    initialDraftRef.current = JSON.stringify(form.getFieldsValue(true));
    setSchedulePreview(null);
    setSchedulePreviewError('');
    setEditorOpen(true);
  }

  function openDatabaseTask() {
    if (!selected) return;
    setEditorDraft({
      ...emptyEditorDraft(),
      name: `${selected.name} 备份任务`,
      backupMethod: defaultBackupMethod(selected),
      toolPath: defaultToolPath(selected)
    }, null);
  }

  function openTableTask(target = activeTable, requestedName?: string) {
    if (!selected || !target) return;
    const tableLabel = `${target.schemaName ? `${target.schemaName}.` : ''}${target.tableName}`;
    setEditorDraft({
      ...emptyEditorDraft(),
      name: requestedName || `${selected.name} ${tableLabel} 备份`,
      scope: 'TABLES',
      schemaName: target.schemaName || '',
      tableNames: [target.tableName],
      backupMethod: defaultBackupMethod(selected),
      toolPath: defaultToolPath(selected)
    }, null);
  }

  function openEditTask(task: BackupTask) {
    const parsedSchedule = scheduleFieldsFromCron(task.cron);
    const tableNames = task.tableNames?.length ? task.tableNames : task.tableName ? [task.tableName] : [];
    setEditorDraft({
      ...emptyEditorDraft(),
      ...parsedSchedule,
      name: task.name,
      scope: normalizeBackupScope(task.scope) as BackupScope,
      schemaName: task.schemaName || '',
      tableNames,
      backupMethod: task.backupMethod || 'SQL',
      toolPath: task.toolPath || '',
      extraArgs: task.extraArgs || '',
      nativeConnectName: task.nativeConnectName || '',
      enabled: Boolean(task.cron?.trim()) && task.enabled
    }, task.id);
  }

  function closeEditor() {
    setEditorOpen(false);
    setEditingId(null);
    initialDraftRef.current = '';
    setSchedulePreview(null);
    setSchedulePreviewError('');
    form.resetFields();
  }

  function requestCloseEditor() {
    if (loading) return;
    if (currentDraft() === initialDraftRef.current) {
      closeEditor();
      return;
    }
    modal.confirm({
      title: '放弃未保存的更改？',
      content: '当前备份任务配置尚未保存，关闭后更改将丢失。',
      okText: '放弃更改',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: closeEditor
    });
  }

  async function saveTask(values: BackupTaskEditorValues) {
    const tables = [...new Set((values.tableNames || []).map((name) => name.trim()).filter(Boolean))];
    const cron = cronFromSchedule(values);
    const payload: BackupTaskForm = {
      name: values.name.trim(),
      scope: values.scope,
      schemaName: values.scope === 'SCHEMA' || values.scope === 'TABLES' ? values.schemaName?.trim() : undefined,
      tableNames: values.scope === 'TABLES' ? tables : undefined,
      tableName: values.scope === 'TABLES' && tables.length === 1 ? tables[0] : undefined,
      backupMethod: values.backupMethod || 'SQL',
      toolPath: values.toolPath?.trim(),
      extraArgs: values.extraArgs?.trim(),
      nativeConnectName: values.nativeConnectName?.trim(),
      cron: cron || undefined,
      enabled: Boolean(cron) && values.enabled
    };
    try {
      await onSave(editingId, payload);
      closeEditor();
    } catch {
      // The parent owns API error feedback; keep the editor open for correction/retry.
    }
  }

  async function deleteTask(id: number) {
    setDeletingTaskId(id);
    try {
      await onDelete(id, Boolean(deleteFiles[id]));
      setDeleteTarget(null);
      setDeleteFiles((current) => {
        const next = { ...current };
        delete next[id];
        return next;
      });
    } catch {
      // The parent owns API error feedback; leave the confirmation open for retry.
    } finally {
      setDeletingTaskId(null);
    }
  }

  async function openHistory(task: BackupTask) {
    setHistoryTask(task);
    setHistories([]);
    setHistoryLoading(true);
    try {
      setHistories(await onLoadHistory(task.id));
    } catch {
      // The parent owns API error feedback; the empty state remains visible.
    } finally {
      setHistoryLoading(false);
    }
  }

  async function deleteHistory(historyId: number) {
    if (!historyTask) return;
    setDeletingHistoryId(historyId);
    try {
      await onDeleteHistory(historyTask.id, historyId, Boolean(deleteHistoryFiles[historyId]));
      setHistories(await onLoadHistory(historyTask.id));
      setDeleteHistoryFiles((current) => {
        const next = { ...current };
        delete next[historyId];
        return next;
      });
    } catch {
      // The parent owns API error feedback; preserve the history row and selection.
    } finally {
      setDeletingHistoryId(null);
    }
  }

  async function handleTaskMenu(task: BackupTask, key: string) {
    if (key === 'edit') return openEditTask(task);
    if (key === 'download') return onDownload(task.id);
    if (key === 'history') return openHistory(task);
    if (key === 'delete') return setDeleteTarget(task);
    if (key === 'toggle') {
      setTaskAction(`toggle-${task.id}`);
      try {
        await onToggle(task.id, !task.enabled);
      } catch {
        // The parent owns API error feedback.
      } finally {
        setTaskAction(null);
      }
    }
  }

  function taskMenuItems(task: BackupTask): MenuProps['items'] {
    return [
      { key: 'edit', icon: <EditOutlined />, label: '编辑任务' },
      ...(task.cron?.trim() ? [{
        key: 'toggle',
        icon: task.enabled ? <PauseCircleOutlined /> : <CheckCircleOutlined />,
        label: task.enabled ? '暂停执行计划' : '启用执行计划'
      }] : []),
      { key: 'download', icon: <DownloadOutlined />, label: '下载最近备份', disabled: !task.lastFilePath },
      { key: 'history', icon: <HistoryOutlined />, label: '查看执行历史' },
      { type: 'divider' },
      { key: 'delete', icon: <DeleteOutlined />, label: '删除任务', danger: true }
    ] as MenuProps['items'];
  }

  const methodOptions = backupMethodOptions(selected, backupMethod);

  return (
    <section className="inspector-section">
      {modalContextHolder}
      <div className="inspector-section-header">
        <Text strong>备份任务</Text>
        <Tag>{selected ? selected.name : '未选择连接'}</Tag>
      </div>
      <Space direction="vertical" size={10} className="full-width">
        <Space.Compact block>
          <Button size="small" icon={<PlusOutlined />} disabled={!selected || loading} onClick={openDatabaseTask}>新建任务</Button>
          <Button size="small" icon={<PlusOutlined />} disabled={!selected || !activeTable || loading} onClick={() => openTableTask()}>备份当前表</Button>
        </Space.Compact>
        <List
          size="small"
          pagination={backups.length > 10 ? { pageSize: 10, showSizeChanger: false, size: 'small' } : false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={selected ? '暂无备份任务' : '请选择连接'} /> }}
          dataSource={backups}
          renderItem={(backup) => {
            const scheduled = Boolean(backup.cron?.trim());
            const taskSchedulePreview = backup.cron ? listSchedulePreviews[backup.cron.trim()] : undefined;
            const taskZoneId = backup.zoneId || taskSchedulePreview?.zoneId;
            const nextRunAt = backup.nextRunAt || taskSchedulePreview?.nextRuns[0];
            const recentDetails = [
              backup.lastRunAt ? `最近执行：${formatHistoryTime(backup.lastRunAt)}` : '',
              backup.lastFileSize ? `文件大小：${formatFileSize(backup.lastFileSize)}` : '',
              scheduled && nextRunAt ? `下次执行：${formatHistoryTime(nextRunAt)}` : ''
            ].filter(Boolean).join(' · ');
            return (
              <List.Item
                actions={[
                  <Button
                    key="run"
                    type="primary"
                    size="small"
                    icon={<PlayCircleOutlined />}
                    loading={loading && runningTaskId === backup.id}
                    disabled={loading || runningTaskId !== null || Boolean(taskAction)}
                    onClick={() => {
                      setRunningTaskId(backup.id);
                      onRun(backup.id);
                    }}
                  >
                    立即执行
                  </Button>,
                  <Tooltip key="more" title="更多操作">
                    <Dropdown
                      trigger={['click']}
                      disabled={loading || runningTaskId !== null || Boolean(taskAction)}
                      menu={{ items: taskMenuItems(backup), onClick: ({ key }) => void handleTaskMenu(backup, key) }}
                    >
                      <Button size="small" type="text" icon={<MoreOutlined />} aria-label={`${backup.name} 更多操作`} />
                    </Dropdown>
                  </Tooltip>
                ]}
              >
                <List.Item.Meta
                  title={(
                    <Space size={4} wrap>
                      <span className="backup-task-title">{backup.name}</span>
                      <Tag color={!scheduled ? 'default' : backup.enabled ? 'blue' : 'orange'}>
                        {!scheduled ? '手动任务' : backup.enabled ? '计划运行中' : '计划已暂停'}
                      </Tag>
                      <Tag color={backup.lastStatus === 'SUCCESS' ? 'green' : backup.lastStatus === 'FAILED' ? 'red' : 'default'}>{backupStatusLabel(backup.lastStatus)}</Tag>
                    </Space>
                  )}
                  description={(
                    <Space direction="vertical" size={1}>
                      <Text type="secondary">{backupMethodLabel(backup.backupMethod)} · {backupScopeLabel(backup.scope, effectiveNamespaceKind)}</Text>
                      <Text type="secondary">{backupTargetLabel(backup, effectiveNamespaceKind)} · {describeBackupSchedule(backup.cron)}{taskZoneId ? `（${taskZoneId}）` : ''}</Text>
                      {recentDetails && <Text type="secondary">{recentDetails}</Text>}
                    </Space>
                  )}
                />
              </List.Item>
            );
          }}
        />
      </Space>

      <Modal
        title="删除备份任务"
        open={Boolean(deleteTarget)}
        okText="删除"
        cancelText="取消"
        confirmLoading={deleteTarget ? deletingTaskId === deleteTarget.id : false}
        okButtonProps={{ danger: true, disabled: loading && deletingTaskId === null }}
        cancelButtonProps={{ disabled: deletingTaskId !== null }}
        closable={deletingTaskId === null}
        maskClosable={deletingTaskId === null}
        onCancel={() => {
          if (deleteTarget) {
            setDeleteFiles((current) => {
              const next = { ...current };
              delete next[deleteTarget.id];
              return next;
            });
          }
          setDeleteTarget(null);
        }}
        onOk={() => deleteTarget && deleteTask(deleteTarget.id)}
      >
        <Space direction="vertical" size={12} className="full-width">
          <Text>确定删除任务“{deleteTarget?.name}”吗？此操作无法撤销。</Text>
          <Checkbox
            checked={deleteTarget ? Boolean(deleteFiles[deleteTarget.id]) : false}
            disabled={deletingTaskId !== null}
            onChange={(event) => {
              if (!deleteTarget) return;
              setDeleteFiles((current) => ({ ...current, [deleteTarget.id]: event.target.checked }));
            }}
          >
            同时删除所有历史备份文件
          </Checkbox>
        </Space>
      </Modal>

      <Modal
        title={editingId ? '编辑备份任务' : '新建备份任务'}
        open={editorOpen}
        width={680}
        forceRender
        confirmLoading={loading}
        onCancel={requestCloseEditor}
        onOk={() => form.submit()}
        okText="保存"
        cancelText="取消"
        okButtonProps={{ disabled: !selected || loading }}
        cancelButtonProps={{ disabled: loading }}
        maskClosable={!loading}
      >
        <Form
          form={form}
          layout="vertical"
          size="small"
          className="compact-form"
          initialValues={emptyEditorDraft()}
          disabled={loading}
          onFinish={saveTask}
        >
          <Divider titlePlacement="start" plain>基本信息</Divider>
          <Form.Item label="任务名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入任务名称' }]}>
            <Input placeholder="例如：生产库每日备份" autoFocus />
          </Form.Item>

          <Divider titlePlacement="start" plain>备份范围</Divider>
          <Form.Item label="范围" name="scope" rules={[{ required: true, message: '请选择备份范围' }]}>
            <Select
              options={[
                { value: 'DATABASE', label: '当前连接数据库' },
                { value: 'SCHEMA', label: effectiveNamespaceKind === 'CATALOG' ? '指定数据库' : '指定 Schema' },
                { value: 'TABLES', label: '指定一张或多张表' }
              ]}
              onChange={(nextScope: BackupScope) => {
                if (nextScope === 'DATABASE') {
                  form.setFieldValue('schemaName', '');
                  form.setFieldValue('tableNames', []);
                } else if (nextScope === 'SCHEMA') {
                  form.setFieldValue('tableNames', []);
                }
              }}
            />
          </Form.Item>
          {(scope === 'SCHEMA' || scope === 'TABLES') && (
            <Form.Item
              label={namespaceLabel}
              name="schemaName"
              rules={[{ required: true, whitespace: true, message: `请选择${namespaceLabel}` }]}
              extra={scope === 'SCHEMA' ? `执行时将动态备份该${namespaceLabel}下的全部基础表。` : `先选择${namespaceLabel}，再选择其中的表。`}
            >
              {onLoadNamespaces ? (
                <RemoteNameSelect
                  active={editorOpen}
                  loader={namespaceLoader}
                  placeholder={`搜索并选择${namespaceLabel}`}
                  onChange={() => form.setFieldValue('tableNames', [])}
                />
              ) : (
                <Input placeholder={`请输入${namespaceLabel}`} onChange={() => form.setFieldValue('tableNames', [])} />
              )}
            </Form.Item>
          )}
          {scope === 'TABLES' && (
            <Form.Item
              label="表"
              name="tableNames"
              rules={[
                { required: true, type: 'array', min: 1, message: '请至少选择一张表' },
                { type: 'array', max: 100, message: '一个任务最多选择 100 张表' }
              ]}
              extra="只显示基础表；同一任务最多选择 100 张。"
            >
              {onLoadTables ? (
                <RemoteNameSelect
                  multiple
                  maxCount={100}
                  active={editorOpen && Boolean(schemaName)}
                  disabled={!schemaName}
                  loader={tableLoader}
                  resetKey={schemaName}
                  placeholder={schemaName ? '搜索并选择表' : `请先选择${namespaceLabel}`}
                />
              ) : (
                <Select mode="tags" maxCount={100} tokenSeparators={[',', '，']} placeholder="输入表名后按回车，可选择多张表" />
              )}
            </Form.Item>
          )}

          <Divider titlePlacement="start" plain>备份方式</Divider>
          <Form.Item label="方式" name="backupMethod" rules={[{ required: true, message: '请选择备份方式' }]}>
            <Select
              options={methodOptions}
              onChange={(nextMethod) => {
                const currentPath = form.getFieldValue('toolPath');
                if (!currentPath || currentPath === defaultToolPath(selected, backupMethod)) {
                  form.setFieldValue('toolPath', defaultToolPath(selected, nextMethod));
                }
              }}
            />
          </Form.Item>

          <Divider titlePlacement="start" plain>执行计划</Divider>
          <Form.Item label="执行频率" name="scheduleKind" rules={[{ required: true, message: '请选择执行频率' }]}>
            <Select
              options={[
                { value: 'MANUAL', label: '仅手动执行' },
                { value: 'DAILY', label: '每天' },
                { value: 'WEEKLY', label: '每周' },
                { value: 'MONTHLY', label: '每月' },
                { value: 'ADVANCED', label: '高级 Cron' }
              ]}
              onChange={(nextKind: BackupScheduleKind) => form.setFieldValue('enabled', nextKind !== 'MANUAL')}
            />
          </Form.Item>
          {scheduleKind !== 'MANUAL' && scheduleKind !== 'ADVANCED' && (
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
              <Select options={monthlyDayOptions()} />
            </Form.Item>
          )}
          {scheduleKind === 'ADVANCED' && (
            <Form.Item
              label="Cron 表达式"
              name="advancedCron"
              extra="使用 Spring 六段式 Cron（秒 分 时 日 月 周）。不能识别的旧表达式会原样保留在此处。"
              rules={[
                { required: true, whitespace: true, message: '请输入 Cron 表达式' },
                { validator: (_, value?: string) => isSixFieldCron(value) ? Promise.resolve() : Promise.reject(new Error('Cron 表达式应包含 6 个字段')) }
              ]}
            >
              <Input placeholder="0 0 2 * * *" />
            </Form.Item>
          )}
          {scheduleKind !== 'MANUAL' && (
            <>
              <Form.Item name="enabled" valuePropName="checked">
                <Checkbox>保存后启用该执行计划</Checkbox>
              </Form.Item>
              <SchedulePreview
                cron={generatedCron}
                preview={schedulePreview}
                loading={schedulePreviewLoading}
                error={schedulePreviewError}
                previewAvailable={Boolean(onPreviewSchedule)}
              />
            </>
          )}

          <Collapse
            ghost
            size="small"
            items={[{
              key: 'advanced',
              label: '高级设置',
              children: nativeBackup ? (
                <>
                  <Form.Item label="工具路径" name="toolPath" rules={[{ required: true, whitespace: true, message: '原生备份需要填写工具路径' }]}>
                    <Input placeholder={backupMethod === 'ORACLE_EXP' ? 'exp' : 'mysqldump'} />
                  </Form.Item>
                  {backupMethod === 'ORACLE_EXP' && (
                    <Form.Item label="连接名覆盖" name="nativeConnectName">
                      <Input placeholder="//host:1521/service 或 host:1521:SID" />
                    </Form.Item>
                  )}
                  <Form.Item label="额外参数" name="extraArgs" extra="一行填写一个参数；连接和输出文件等系统参数不能覆盖。">
                    <Input.TextArea rows={3} placeholder="例如：--single-transaction" />
                  </Form.Item>
                </>
              ) : <Text type="secondary">SQL 逻辑备份无需配置外部工具。</Text>
            }]}
          />
        </Form>
      </Modal>

      <Modal
        title={historyTask ? `${historyTask.name} 执行历史` : '执行历史'}
        open={Boolean(historyTask)}
        footer={null}
        maskClosable={!deletingHistoryId}
        closable={!deletingHistoryId}
        onCancel={() => {
          if (deletingHistoryId) return;
          setHistoryTask(null);
          setHistories([]);
          setDeleteHistoryFiles({});
        }}
      >
        <List
          size="small"
          loading={historyLoading}
          pagination={histories.length > 10 ? { pageSize: 10, showSizeChanger: false, size: 'small' } : false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无执行历史" /> }}
          dataSource={histories}
          renderItem={(history) => (
            <List.Item
              actions={[
                <Tooltip key="download" title={history.filePath ? '下载备份文件' : '没有可下载的备份文件'}>
                  <Button
                    size="small"
                    type="text"
                    icon={<DownloadOutlined />}
                    aria-label="下载备份文件"
                    disabled={!history.filePath || loading || deletingHistoryId !== null}
                    onClick={() => historyTask && onDownloadHistory(historyTask.id, history.id)}
                  />
                </Tooltip>,
                <Popconfirm
                  key="delete"
                  title="删除备份历史"
                  description={(
                    <Checkbox
                      checked={Boolean(deleteHistoryFiles[history.id])}
                      disabled={!history.filePath}
                      onChange={(event) => setDeleteHistoryFiles((current) => ({ ...current, [history.id]: event.target.checked }))}
                    >
                      同时删除备份文件
                    </Checkbox>
                  )}
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  onConfirm={() => deleteHistory(history.id)}
                >
                  <Tooltip title="删除备份历史">
                    <Button
                      size="small"
                      type="text"
                      danger
                      icon={<DeleteOutlined />}
                      aria-label="删除备份历史"
                      loading={deletingHistoryId === history.id}
                      disabled={loading && deletingHistoryId !== history.id}
                    />
                  </Tooltip>
                </Popconfirm>
              ]}
            >
              <List.Item.Meta
                title={(
                  <Space size={6} wrap>
                    <Tag color={history.status === 'SUCCESS' ? 'green' : history.status === 'FAILED' ? 'red' : 'default'}>{backupStatusLabel(history.status)}</Tag>
                    <Text>{formatHistoryTime(history.finishedAt || history.startedAt || '')}</Text>
                  </Space>
                )}
                description={(
                  <Space direction="vertical" size={2} className="full-width">
                    {history.fileSize ? <Text type="secondary">文件大小：{formatFileSize(history.fileSize)}</Text> : null}
                    {history.message ? <Text type="secondary" className="backup-history-message">{history.message}</Text> : null}
                  </Space>
                )}
              />
            </List.Item>
          )}
        />
      </Modal>
    </section>
  );
}

type RemoteNameSelectProps = {
  id?: string;
  value?: string | string[];
  onChange?: (value: string | string[]) => void;
  active: boolean;
  disabled?: boolean;
  multiple?: boolean;
  maxCount?: number;
  placeholder: string;
  resetKey?: string;
  loader: (query: BackupTargetQuery) => Promise<BackupTargetPage>;
};

function RemoteNameSelect({ value, onChange, active, disabled, multiple, maxCount, placeholder, resetKey, loader }: RemoteNameSelectProps) {
  const requestIdRef = useRef(0);
  const [keyword, setKeyword] = useState('');
  const [debouncedKeyword, setDebouncedKeyword] = useState('');
  const [pageNumber, setPageNumber] = useState(0);
  const [result, setResult] = useState<BackupTargetPage>(() => emptyTargetPage(0, TARGET_PAGE_SIZE));
  const [fetching, setFetching] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedKeyword(keyword.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [keyword]);

  useEffect(() => {
    setKeyword('');
    setDebouncedKeyword('');
    setPageNumber(0);
    setResult(emptyTargetPage(0, TARGET_PAGE_SIZE));
  }, [resetKey]);

  const fetchPage = useCallback(async (refresh = false) => {
    if (!active || disabled) return;
    const requestId = ++requestIdRef.current;
    setFetching(true);
    setError('');
    try {
      const next = await loader({ keyword: debouncedKeyword || undefined, page: pageNumber, pageSize: TARGET_PAGE_SIZE, refresh });
      if (requestId === requestIdRef.current) setResult(next);
    } catch (loadError) {
      if (requestId === requestIdRef.current) setError((loadError as Error).message || '加载失败');
    } finally {
      if (requestId === requestIdRef.current) setFetching(false);
    }
  }, [active, debouncedKeyword, disabled, loader, pageNumber]);

  useEffect(() => {
    void fetchPage();
  }, [fetchPage]);

  const selectedNames = Array.isArray(value) ? value : value ? [value] : [];
  const items = [...result.items];
  for (const selectedName of selectedNames) {
    if (!items.some((item) => item.name === selectedName)) items.unshift({ name: selectedName });
  }
  const options = items.map((item) => ({
    value: item.name,
    label: item.current ? <Space size={4}><span>{item.name}</span><Tag color="blue">当前</Tag></Space> : item.name
  }));
  const pageCount = Math.max(1, Math.ceil(result.total / Math.max(result.pageSize, 1)));

  return (
    <Select
      value={value}
      onChange={onChange}
      mode={multiple ? 'multiple' : undefined}
      maxCount={maxCount}
      allowClear
      showSearch
      filterOption={false}
      disabled={disabled}
      placeholder={placeholder}
      options={options}
      notFoundContent={fetching ? <Spin size="small" /> : error || '没有匹配项'}
      onSearch={(nextKeyword) => {
        setKeyword(nextKeyword);
        setPageNumber(0);
      }}
      popupRender={(menu) => (
        <div>
          {menu}
          <Divider style={{ margin: '6px 0' }} />
          <Space style={{ display: 'flex', justifyContent: 'space-between', padding: '0 8px 6px' }}>
            <Text type={error ? 'danger' : 'secondary'}>{error || `第 ${result.page + 1} / ${pageCount} 页，共 ${result.total} 项`}</Text>
            <Space.Compact>
              <Button size="small" type="text" icon={<ReloadOutlined />} loading={fetching} onMouseDown={(event) => event.preventDefault()} onClick={() => void fetchPage(true)} />
              <Button size="small" type="text" icon={<LeftOutlined />} disabled={fetching || pageNumber <= 0} onMouseDown={(event) => event.preventDefault()} onClick={() => setPageNumber((page) => Math.max(0, page - 1))} />
              <Button size="small" type="text" icon={<RightOutlined />} disabled={fetching || !result.hasMore} onMouseDown={(event) => event.preventDefault()} onClick={() => setPageNumber((page) => page + 1)} />
            </Space.Compact>
          </Space>
        </div>
      )}
    />
  );
}

function SchedulePreview({ cron, preview, loading, error, previewAvailable }: { cron: string; preview: BackupSchedulePreview | null; loading: boolean; error: string; previewAvailable: boolean }) {
  if (!cron && !error) return null;
  if (error) return <Alert type="error" showIcon message="执行计划无法预览" description={error} />;
  return (
    <Alert
      type="info"
      showIcon
      message={<Space wrap><span>生成的 Cron</span><Text code>{cron}</Text>{loading && <Spin size="small" />}</Space>}
      description={preview ? (
        <Space direction="vertical" size={2}>
          <Text>服务端时区：{preview.zoneId}</Text>
          {preview.nextRuns.slice(0, 3).map((run, index) => <Text key={`${run}-${index}`}>第 {index + 1} 次：{formatHistoryTime(run)}</Text>)}
        </Space>
      ) : previewAvailable ? '正在根据服务端时区计算后续执行时间。' : '保存后将由服务端时区计算实际执行时间。'}
    />
  );
}

function emptyEditorDraft(): BackupTaskEditorValues {
  return {
    name: '',
    scope: 'DATABASE',
    schemaName: '',
    tableNames: [],
    backupMethod: 'SQL',
    toolPath: '',
    extraArgs: '',
    nativeConnectName: '',
    scheduleKind: 'MANUAL',
    scheduleTime: '02:00',
    weeklyDays: ['MON'] as CronWeekday[],
    monthlyDay: '1',
    advancedCron: '',
    enabled: false
  };
}

function emptyTargetPage(page: number, pageSize: number): BackupTargetPage {
  return { items: [], page, pageSize, total: 0, hasMore: false };
}

function monthlyDayOptions() {
  return [
    ...Array.from({ length: 31 }, (_, index) => ({ value: String(index + 1), label: `${index + 1} 日` })),
    { value: 'L', label: '最后一天' }
  ];
}

function backupMethodOptions(connection: Connection | null, currentMethod?: string) {
  const options: { value: string; label: string }[] = [{ value: 'SQL', label: 'SQL 逻辑备份' }];
  const dbType = connection?.dbType?.toLowerCase();
  if (dbType === 'mysql' || dbType === 'mariadb') options.push({ value: 'MYSQLDUMP', label: 'MySQL mysqldump' });
  if (dbType === 'oracle') options.push({ value: 'ORACLE_EXP', label: 'Oracle exp' });
  if (currentMethod && !options.some((option) => option.value === currentMethod)) {
    options.push({ value: currentMethod, label: `${backupMethodLabel(currentMethod)}（现有配置）` });
  }
  return options;
}

function defaultBackupMethod(connection: Connection | null): BackupMethod {
  const dbType = connection?.dbType?.toLowerCase();
  if (dbType === 'mysql' || dbType === 'mariadb') return 'MYSQLDUMP';
  if (dbType === 'oracle') return 'ORACLE_EXP';
  return 'SQL';
}

function defaultToolPath(connection: Connection | null, method: string = defaultBackupMethod(connection)) {
  if (method === 'MYSQLDUMP') return 'mysqldump';
  if (method === 'ORACLE_EXP') return 'exp';
  return '';
}
