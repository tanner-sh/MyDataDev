import { useEffect, useRef, useState } from 'react';
import { Button, Checkbox, Dropdown, Empty, Form, Input, List, Modal, Popconfirm, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { CheckCircleOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, HistoryOutlined, MoreOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined } from '@ant-design/icons';
import type { MenuProps } from 'antd';
import type { ActiveTable, BackupHistory, BackupTask, BackupTaskForm, Connection } from '../types';
import { backupMethodLabel, backupScopeLabel, backupStatusLabel, formatFileSize, formatHistoryTime } from '../utils';

const { Text } = Typography;

type BackupPanelProps = {
  backups: BackupTask[];
  selected: Connection | null;
  activeTable: ActiveTable | null;
  loading: boolean;
  onSave: (id: number | null, form: BackupTaskForm) => Promise<void>;
  onToggle: (id: number, enabled: boolean) => Promise<void>;
  onDelete: (id: number, deleteFile: boolean) => Promise<void>;
  onRun: (id: number) => void;
  onDownload: (id: number) => void;
  onLoadHistory: (id: number) => Promise<BackupHistory[]>;
  onDeleteHistory: (taskId: number, historyId: number, deleteFile: boolean) => Promise<void>;
  onDownloadHistory: (taskId: number, historyId: number) => void;
};

export function BackupPanel({ backups, selected, activeTable, loading, onSave, onToggle, onDelete, onRun, onDownload, onLoadHistory, onDeleteHistory, onDownloadHistory }: BackupPanelProps) {
  const [form] = Form.useForm<BackupTaskForm>();
  const [modal, modalContextHolder] = Modal.useModal();
  const initialDraftRef = useRef('');
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
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

  const scopedTable = Form.useWatch('scope', form) === 'TABLE';
  const backupMethod = Form.useWatch('backupMethod', form) || 'SQL';
  const nativeBackup = backupMethod !== 'SQL';

  useEffect(() => {
    if (!loading) setRunningTaskId(null);
  }, [loading]);

  function openDatabaseTask() {
    if (!selected) return;
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({
      name: `${selected.name} 全量备份`,
      scope: 'DATABASE',
      schemaName: '',
      tableName: '',
      backupMethod: defaultBackupMethod(selected),
      toolPath: defaultToolPath(selected),
      extraArgs: '',
      nativeConnectName: '',
      cron: '0 0 2 * * *',
      enabled: true
    });
    initialDraftRef.current = JSON.stringify(form.getFieldsValue(true));
    setEditorOpen(true);
  }

  function openTableTask() {
    if (!selected || !activeTable) return;
    const tableLabel = `${activeTable.schemaName ? `${activeTable.schemaName}.` : ''}${activeTable.tableName}`;
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({
      name: `${selected.name} ${tableLabel} 备份`,
      scope: 'TABLE',
      schemaName: activeTable.schemaName || '',
      tableName: activeTable.tableName,
      backupMethod: defaultBackupMethod(selected),
      toolPath: defaultToolPath(selected),
      extraArgs: '',
      nativeConnectName: '',
      cron: '',
      enabled: false
    });
    initialDraftRef.current = JSON.stringify(form.getFieldsValue(true));
    setEditorOpen(true);
  }

  function openEditTask(task: BackupTask) {
    setEditingId(task.id);
    form.resetFields();
    form.setFieldsValue({
      name: task.name,
      scope: task.scope,
      schemaName: task.schemaName || '',
      tableName: task.tableName || '',
      backupMethod: task.backupMethod || 'SQL',
      toolPath: task.toolPath || '',
      extraArgs: task.extraArgs || '',
      nativeConnectName: task.nativeConnectName || '',
      cron: task.cron || '',
      enabled: task.enabled
    });
    initialDraftRef.current = JSON.stringify(form.getFieldsValue(true));
    setEditorOpen(true);
  }

  function closeEditor() {
    setEditorOpen(false);
    setEditingId(null);
    initialDraftRef.current = '';
    form.resetFields();
  }

  function requestCloseEditor() {
    if (loading) return;
    if (JSON.stringify(form.getFieldsValue(true)) === initialDraftRef.current) {
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

  async function saveTask(values: BackupTaskForm) {
    try {
      await onSave(editingId, values);
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
    if (key === 'edit') {
      openEditTask(task);
      return;
    }
    if (key === 'download') {
      onDownload(task.id);
      return;
    }
    if (key === 'history') {
      await openHistory(task);
      return;
    }
    if (key === 'delete') {
      setDeleteTarget(task);
      return;
    }
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
      {
        key: 'toggle',
        icon: task.enabled ? <PauseCircleOutlined /> : <CheckCircleOutlined />,
        label: task.enabled ? '停用定时任务' : '启用定时任务'
      },
      { key: 'download', icon: <DownloadOutlined />, label: '下载最近备份', disabled: !task.lastFilePath },
      { key: 'history', icon: <HistoryOutlined />, label: '查看执行历史' },
      { type: 'divider' },
      { key: 'delete', icon: <DeleteOutlined />, label: '删除任务', danger: true }
    ];
  }

  return (
    <section className="inspector-section">
      {modalContextHolder}
      <div className="inspector-section-header">
        <Text strong>备份任务</Text>
        <Tag>{selected ? selected.name : '未选择连接'}</Tag>
      </div>
      <Space direction="vertical" size={10} className="full-width">
        <Space.Compact block>
          <Button size="small" icon={<PlusOutlined />} disabled={!selected || loading} onClick={openDatabaseTask}>全库</Button>
          <Button size="small" icon={<PlusOutlined />} disabled={!selected || !activeTable || loading} onClick={openTableTask}>当前表</Button>
        </Space.Compact>
        <List
          size="small"
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={selected ? '暂无备份任务' : '请选择连接'} /> }}
          dataSource={backups}
          renderItem={(backup) => {
            const target = backup.tableName ? `${backup.schemaName ? `${backup.schemaName}.` : ''}${backup.tableName}` : '';
            const recentDetails = [
              target,
              backup.lastRunAt ? `最近执行：${formatHistoryTime(backup.lastRunAt)}` : '',
              backup.lastFileSize ? `文件大小：${formatFileSize(backup.lastFileSize)}` : ''
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
                    menu={{
                      items: taskMenuItems(backup),
                      onClick: ({ key }) => void handleTaskMenu(backup, key)
                    }}
                  >
                    <Button
                      size="small"
                      type="text"
                      icon={<MoreOutlined />}
                      aria-label={`${backup.name} 更多操作`}
                    />
                  </Dropdown>
                </Tooltip>
              ]}
              >
                <List.Item.Meta
                  title={(
                    <Space size={4} wrap>
                      <span className="backup-task-title">{backup.name}</span>
                      <Tag color={backup.enabled ? 'blue' : 'default'}>{backup.enabled ? '已启用' : '已停用'}</Tag>
                      <Tag color={backup.lastStatus === 'SUCCESS' ? 'green' : backup.lastStatus === 'FAILED' ? 'red' : 'default'}>{backupStatusLabel(backup.lastStatus)}</Tag>
                    </Space>
                  )}
                  description={(
                    <Space direction="vertical" size={1}>
                      <Text type="secondary">{backupMethodLabel(backup.backupMethod)} · {backupScopeLabel(backup.scope)} · {backup.cron || '手动执行'}</Text>
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
          initialValues={emptyDraft()}
          disabled={loading}
          onFinish={saveTask}
        >
          <Form.Item
            label="任务名称"
            name="name"
            rules={[{ required: true, whitespace: true, message: '请输入任务名称' }]}
          >
            <Input placeholder="例如：生产库每日全量备份" autoFocus />
          </Form.Item>
          <Form.Item label="备份范围" name="scope" rules={[{ required: true, message: '请选择备份范围' }]}>
            <Select
              options={[{ value: 'DATABASE', label: '全库' }, { value: 'TABLE', label: '单表' }]}
            />
          </Form.Item>
          <Form.Item label="备份方式" name="backupMethod" rules={[{ required: true, message: '请选择备份方式' }]}>
            <Select
              options={[
                { value: 'SQL', label: 'SQL 逻辑备份' },
                { value: 'MYSQLDUMP', label: 'MySQL mysqldump' },
                { value: 'ORACLE_EXP', label: 'Oracle exp' }
              ]}
              onChange={(nextMethod) => {
                const currentPath = form.getFieldValue('toolPath');
                if (!currentPath || currentPath === defaultToolPath(selected, backupMethod)) {
                  form.setFieldValue('toolPath', defaultToolPath(selected, nextMethod));
                }
              }}
            />
          </Form.Item>
          {scopedTable && (
            <>
              <Form.Item label="Schema" name="schemaName">
                <Input placeholder="留空时使用连接默认 Schema" />
              </Form.Item>
              <Form.Item
                label="表名"
                name="tableName"
                rules={[{ required: true, whitespace: true, message: '请输入要备份的表名' }]}
              >
                <Input placeholder="请输入表名" />
              </Form.Item>
            </>
          )}
          <Form.Item
            label="Cron"
            name="cron"
            dependencies={['enabled']}
            extra="使用 Spring 六段式 Cron；手动任务可留空。"
            rules={[
              ({ getFieldValue }) => ({
                validator: (_, value?: string) => {
                  const cron = value?.trim();
                  if (getFieldValue('enabled') && !cron) {
                    return Promise.reject(new Error('启用定时任务时必须填写 Cron 表达式'));
                  }
                  if (cron && cron.split(/\s+/).length !== 6) {
                    return Promise.reject(new Error('Cron 表达式应包含 6 个字段'));
                  }
                  return Promise.resolve();
                }
              })
            ]}
          >
            <Input placeholder="0 0 2 * * *" />
          </Form.Item>
          {nativeBackup && (
            <>
              <Form.Item
                label="工具路径"
                name="toolPath"
                rules={[{ required: true, whitespace: true, message: '原生备份需要填写工具路径' }]}
              >
                <Input placeholder={backupMethod === 'ORACLE_EXP' ? 'exp' : 'mysqldump'} />
              </Form.Item>
              {backupMethod === 'ORACLE_EXP' && (
                <Form.Item label="连接名覆盖" name="nativeConnectName">
                  <Input placeholder="//host:1521/service 或 host:1521:SID" />
                </Form.Item>
              )}
              <Form.Item label="额外参数" name="extraArgs">
                <Input.TextArea
                  rows={3}
                  placeholder="一行一个参数，例如 --single-transaction 或 compress=y"
                />
              </Form.Item>
            </>
          )}
          <Form.Item name="enabled" valuePropName="checked">
            <Checkbox>启用定时任务</Checkbox>
          </Form.Item>
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

function emptyDraft(): BackupTaskForm {
  return { name: '', scope: 'DATABASE', schemaName: '', tableName: '', backupMethod: 'SQL', toolPath: '', extraArgs: '', nativeConnectName: '', cron: '', enabled: false };
}

function defaultBackupMethod(connection: Connection | null) {
  if (connection?.dbType === 'mysql' || connection?.dbType === 'mariadb') return 'MYSQLDUMP';
  if (connection?.dbType === 'oracle') return 'ORACLE_EXP';
  return 'SQL';
}

function defaultToolPath(connection: Connection | null, method = defaultBackupMethod(connection)) {
  if (method === 'MYSQLDUMP') return 'mysqldump';
  if (method === 'ORACLE_EXP') return 'exp';
  return '';
}
