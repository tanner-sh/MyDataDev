import { useState } from 'react';
import { Button, Checkbox, Empty, Form, Input, List, Modal, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import { CheckCircleOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined } from '@ant-design/icons';
import type { ActiveTable, BackupTask, BackupTaskForm, Connection } from '../types';
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
};

export function BackupPanel({ backups, selected, activeTable, loading, onSave, onToggle, onDelete, onRun, onDownload }: BackupPanelProps) {
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [draft, setDraft] = useState<BackupTaskForm>(emptyDraft());
  const [deleteFiles, setDeleteFiles] = useState<Record<number, boolean>>({});

  function openDatabaseTask() {
    if (!selected) return;
    setEditingId(null);
    setDraft({
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
    setEditorOpen(true);
  }

  function openTableTask() {
    if (!selected || !activeTable) return;
    const tableLabel = `${activeTable.schemaName ? `${activeTable.schemaName}.` : ''}${activeTable.tableName}`;
    setEditingId(null);
    setDraft({
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
    setEditorOpen(true);
  }

  function openEditTask(task: BackupTask) {
    setEditingId(task.id);
    setDraft({
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
    setEditorOpen(true);
  }

  async function saveTask() {
    await onSave(editingId, draft);
    setEditorOpen(false);
  }

  async function deleteTask(id: number) {
    await onDelete(id, Boolean(deleteFiles[id]));
    setDeleteFiles((current) => {
      const next = { ...current };
      delete next[id];
      return next;
    });
  }

  const scopedTable = draft.scope === 'TABLE';
  const nativeBackup = draft.backupMethod && draft.backupMethod !== 'SQL';

  return (
    <section className="inspector-section">
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
          renderItem={(backup) => (
            <List.Item
              actions={[
                <Button key="edit" size="small" icon={<EditOutlined />} onClick={() => openEditTask(backup)} />,
                <Button key="toggle" size="small" icon={backup.enabled ? <PauseCircleOutlined /> : <CheckCircleOutlined />} onClick={() => onToggle(backup.id, !backup.enabled)} />,
                <Button key="run" size="small" icon={<PlayCircleOutlined />} onClick={() => onRun(backup.id)} />,
                <Button key="download" size="small" icon={<DownloadOutlined />} disabled={!backup.lastFilePath} onClick={() => onDownload(backup.id)} />,
                <Popconfirm
                  key="delete"
                  title="删除备份任务"
                  description={(
                    <Checkbox
                      checked={Boolean(deleteFiles[backup.id])}
                      disabled={!backup.lastFilePath}
                      onChange={(event) => setDeleteFiles((current) => ({ ...current, [backup.id]: event.target.checked }))}
                    >
                      同时删除最近备份文件
                    </Checkbox>
                  )}
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  onConfirm={() => deleteTask(backup.id)}
                >
                  <Button size="small" danger icon={<DeleteOutlined />} />
                </Popconfirm>
              ]}
            >
              <List.Item.Meta
                title={<span className="backup-task-title">{backup.name}</span>}
                description={(
                  <Space direction="vertical" size={2}>
                    <Text type="secondary">{backupMethodLabel(backup.backupMethod)} · {backupScopeLabel(backup.scope)} · {backup.cron || '手动执行'}</Text>
                    {backup.tableName && <Text type="secondary">{backup.schemaName ? `${backup.schemaName}.` : ''}{backup.tableName}</Text>}
                    {backup.lastRunAt && <Text type="secondary">最近执行：{formatHistoryTime(backup.lastRunAt)}</Text>}
                    {backup.lastFileSize ? <Text type="secondary">文件大小：{formatFileSize(backup.lastFileSize)}</Text> : null}
                    <Space size={4} wrap>
                      <Tag color={backup.enabled ? 'blue' : 'default'}>{backup.enabled ? '已启用' : '已停用'}</Tag>
                      <Tag color={backup.lastStatus === 'SUCCESS' ? 'green' : backup.lastStatus === 'FAILED' ? 'red' : 'default'}>{backupStatusLabel(backup.lastStatus)}</Tag>
                    </Space>
                  </Space>
                )}
              />
            </List.Item>
          )}
        />
      </Space>
      <Modal
        title={editingId ? '编辑备份任务' : '新建备份任务'}
        open={editorOpen}
        confirmLoading={loading}
        onCancel={() => setEditorOpen(false)}
        onOk={saveTask}
        okText="保存"
        cancelText="取消"
      >
        <Form layout="vertical" size="small" className="compact-form">
          <Form.Item label="任务名称">
            <Input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
          </Form.Item>
          <Form.Item label="备份范围">
            <Select
              value={draft.scope}
              options={[{ value: 'DATABASE', label: '全库' }, { value: 'TABLE', label: '单表' }]}
              onChange={(scope) => setDraft({ ...draft, scope })}
            />
          </Form.Item>
          <Form.Item label="备份方式">
            <Select
              value={draft.backupMethod || 'SQL'}
              options={[
                { value: 'SQL', label: 'SQL 逻辑备份' },
                { value: 'MYSQLDUMP', label: 'MySQL mysqldump' },
                { value: 'ORACLE_EXP', label: 'Oracle exp' }
              ]}
              onChange={(backupMethod) => setDraft({ ...draft, backupMethod, toolPath: draft.toolPath || defaultToolPath(selected, backupMethod) })}
            />
          </Form.Item>
          {scopedTable && (
            <>
              <Form.Item label="Schema">
                <Input value={draft.schemaName} onChange={(event) => setDraft({ ...draft, schemaName: event.target.value })} />
              </Form.Item>
              <Form.Item label="表名">
                <Input value={draft.tableName} onChange={(event) => setDraft({ ...draft, tableName: event.target.value })} />
              </Form.Item>
            </>
          )}
          <Form.Item label="Cron">
            <Input value={draft.cron} onChange={(event) => setDraft({ ...draft, cron: event.target.value })} />
          </Form.Item>
          {nativeBackup && (
            <>
              <Form.Item label="工具路径">
                <Input value={draft.toolPath} placeholder={draft.backupMethod === 'ORACLE_EXP' ? 'exp' : 'mysqldump'} onChange={(event) => setDraft({ ...draft, toolPath: event.target.value })} />
              </Form.Item>
              {draft.backupMethod === 'ORACLE_EXP' && (
                <Form.Item label="连接名覆盖">
                  <Input value={draft.nativeConnectName} placeholder="//host:1521/service 或 host:1521:SID" onChange={(event) => setDraft({ ...draft, nativeConnectName: event.target.value })} />
                </Form.Item>
              )}
              <Form.Item label="额外参数">
                <Input.TextArea
                  rows={3}
                  value={draft.extraArgs}
                  placeholder="一行一个参数，例如 --single-transaction 或 compress=y"
                  onChange={(event) => setDraft({ ...draft, extraArgs: event.target.value })}
                />
              </Form.Item>
            </>
          )}
          <Form.Item>
            <Checkbox checked={draft.enabled} onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })}>启用定时任务</Checkbox>
          </Form.Item>
        </Form>
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
