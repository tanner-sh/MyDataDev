import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Empty, Input, Progress, Select, Space, Spin, Tag, Typography, Upload, message } from 'antd';
import { CloudUploadOutlined, PauseCircleOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { api, uploadBinary } from '../api';
import type { Connection, SqlFileCandidate, SqlFileExecution, SqlFileExecutionPage } from '../types';
import { localizeMessage } from '../utils';
import { formatSqlFileBytes, sqlFileStatusLabel, sqlFileTaskPercent } from '../sqlFileExecution';
import { useVisiblePolling } from '../hooks/useVisiblePolling';

const { Text } = Typography;
const ACTIVE = new Set(['ANALYZING', 'QUEUED', 'RUNNING']);
const CANCELLABLE = new Set(['ANALYZING', 'READY', 'QUEUED', 'RUNNING']);
const TERMINAL = new Set(['SUCCESS', 'FAILED', 'CANCELLED', 'EXPIRED']);

export const SqlFileExecutionDrawer = memo(function SqlFileExecutionDrawer({ open, candidate, connections, selected, onClose, onMetadataChanged }: {
  open: boolean;
  candidate?: SqlFileCandidate;
  connections: Connection[];
  selected: Connection | null;
  onClose: () => void;
  onMetadataChanged: (connectionId: number) => void;
}) {
  const [toast, contextHolder] = message.useMessage();
  const [connectionId, setConnectionId] = useState<number | undefined>(selected?.id);
  const [jobs, setJobs] = useState<SqlFileExecution[]>([]);
  const [focusedId, setFocusedId] = useState<number>();
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [confirmation, setConfirmation] = useState('');
  const uploadAbortRef = useRef<AbortController | undefined>(undefined);
  const handledCandidateRef = useRef(0);
  const notifiedMetadataRef = useRef(new Set<number>());
  const focused = jobs.find((job) => job.id === focusedId) || jobs[0];
  const focusedTarget = connections.find((connection) => connection.id === focused?.connectionId);
  const hasActive = jobs.some((job) => ACTIVE.has(job.status));

  useEffect(() => {
    if (!open || candidate || !selected) return;
    setConnectionId(selected.id);
  }, [open, candidate?.requestId, selected?.id]);

  useEffect(() => {
    if (!open || !connectionId) return;
    void loadJobs(connectionId, false);
  }, [open, connectionId]);

  useEffect(() => {
    if (!open || !candidate || handledCandidateRef.current === candidate.requestId) return;
    handledCandidateRef.current = candidate.requestId;
    setConnectionId(candidate.connection.id);
    void upload(candidate.file, candidate.connection.id);
  }, [candidate?.requestId, open]);

  useVisiblePolling({
    enabled: open && Boolean(connectionId) && hasActive,
    intervalMs: 1_500,
    resetKey: connectionId,
    task: () => connectionId ? loadJobs(connectionId, false) : undefined
  });

  useEffect(() => () => uploadAbortRef.current?.abort(), []);

  const summary = useMemo(() => focused && focused.status === 'READY' ? [
    { label: '查询', value: focused.queryCount, color: 'blue' },
    { label: '数据变更', value: focused.mutationCount, color: 'orange' },
    { label: '结构变更', value: focused.ddlCount, color: 'red' },
    { label: '未识别', value: focused.unknownCount, color: 'purple' }
  ] : [], [focused]);

  async function loadJobs(targetConnectionId: number, showLoading = true) {
    if (showLoading) setLoading(true);
    try {
      const page = await api<SqlFileExecutionPage>(`/sql-file-executions?connectionId=${targetConnectionId}&page=0&pageSize=50`);
      setJobs(page.items);
      setFocusedId((current) => page.items.some((job) => job.id === current) ? current : page.items[0]?.id);
      for (const job of page.items) {
        if (TERMINAL.has(job.status) && job.metadataChanged && !notifiedMetadataRef.current.has(job.id)) {
          notifiedMetadataRef.current.add(job.id);
          onMetadataChanged(job.connectionId);
        }
      }
    } catch (error) {
      if (showLoading) toast.error(localizeMessage((error as Error).message));
    } finally {
      if (showLoading) setLoading(false);
    }
  }

  async function upload(file: File, targetConnectionId: number) {
    if (!file.name.toLowerCase().endsWith('.sql')) {
      toast.error('只支持 .sql 文件');
      return;
    }
    uploadAbortRef.current?.abort();
    const controller = new AbortController();
    uploadAbortRef.current = controller;
    setUploading(true);
    setUploadProgress(0);
    setConfirmation('');
    try {
      const params = new URLSearchParams({ connectionId: String(targetConnectionId), fileName: file.name });
      const job = await uploadBinary<SqlFileExecution>(`/sql-file-executions/uploads?${params.toString()}`, file, setUploadProgress, controller.signal);
      setJobs((current) => [job, ...current.filter((item) => item.id !== job.id)]);
      setFocusedId(job.id);
      toast.success('SQL 文件上传完成，正在后台解析');
      await loadJobs(targetConnectionId, false);
    } catch (error) {
      if ((error as Error).name !== 'AbortError') toast.error(localizeMessage((error as Error).message));
    } finally {
      if (uploadAbortRef.current === controller) uploadAbortRef.current = undefined;
      setUploading(false);
    }
  }

  async function start(job: SqlFileExecution) {
    const connection = connections.find((item) => item.id === job.connectionId);
    if (connection?.environment === 'prod' && confirmation !== job.connectionName) {
      toast.error('请输入准确的生产连接名');
      return;
    }
    try {
      const next = await api<SqlFileExecution>(`/sql-file-executions/${job.id}/start`, {
        method: 'POST',
        body: JSON.stringify({ productionConfirmation: connection?.environment === 'prod' ? confirmation : undefined })
      });
      setJobs((current) => current.map((item) => item.id === next.id ? next : item));
      setConfirmation('');
      toast.success('SQL 文件任务已开始执行');
    } catch (error) {
      toast.error(localizeMessage((error as Error).message));
    }
  }

  async function cancel(job: SqlFileExecution) {
    try {
      const next = await api<SqlFileExecution>(`/sql-file-executions/${job.id}/cancel`, { method: 'POST' });
      setJobs((current) => current.map((item) => item.id === next.id ? next : item));
      toast.info('SQL 文件任务已取消');
    } catch (error) {
      toast.error(localizeMessage((error as Error).message));
    }
  }

  return (
    <Drawer title="SQL 文件执行" size={760} open={open} onClose={onClose} rootClassName="management-drawer sql-file-execution-drawer">
      {contextHolder}
      <div className="sql-file-upload-card">
        <Space orientation="vertical" size={10} className="full-width">
          <Text strong>选择目标连接和本地 SQL 文件</Text>
          <Select
            showSearch
            optionFilterProp="label"
            value={connectionId}
            disabled={uploading}
            placeholder="请选择数据库连接"
            options={connections.map((connection) => ({ value: connection.id, label: `${connection.name} · ${connection.dbType}${connection.readonly ? ' · 只读' : ''}` }))}
            onChange={(value) => { setConnectionId(value); setJobs([]); setFocusedId(undefined); }}
          />
          <Space>
            <Upload accept=".sql" showUploadList={false} disabled={!connectionId || uploading} beforeUpload={(file) => { void upload(file as unknown as File, connectionId!); return false; }}>
              <Button type="primary" icon={<CloudUploadOutlined />} disabled={!connectionId || uploading}>选择 SQL 文件</Button>
            </Upload>
            {uploading && <Button danger onClick={() => uploadAbortRef.current?.abort()}>取消上传</Button>}
            <Button icon={<ReloadOutlined />} loading={loading} disabled={!connectionId} onClick={() => connectionId && void loadJobs(connectionId)}>刷新任务</Button>
          </Space>
          {uploading && <Progress percent={uploadProgress} status="active" />}
          <Text type="secondary">文件会上传到应用服务器进行流式解析，内容不会载入 SQL 编辑器。</Text>
        </Space>
      </div>

      {focused?.status === 'READY' && (
        <div className="sql-file-confirm-card">
          <Alert type={focused.mutationCount + focused.ddlCount + focused.unknownCount > 0 ? 'warning' : 'info'} showIcon
                 title="文件解析完成，请确认执行目标"
                 description="任务逐条提交；任一语句失败后立即停止，已经提交的语句不会自动回滚。" />
          <Descriptions size="small" column={2} items={[
            { key: 'file', label: '文件', children: focused.fileName },
            { key: 'target', label: '目标连接', children: focused.connectionName },
            { key: 'size', label: '文件大小', children: formatSqlFileBytes(focused.fileSize) },
            { key: 'charset', label: '检测编码', children: focused.detectedCharset || '-' },
            { key: 'statements', label: '执行单元', children: focused.statementTotal ?? 0 },
            { key: 'checksum', label: 'SHA-256', children: <Text className="sql-file-checksum" copyable>{focused.checksumSha256}</Text> }
          ]} />
          <Space wrap>{summary.map((item) => <Tag key={item.label} color={item.color}>{item.label} {item.value}</Tag>)}</Space>
          {focusedTarget?.readonly && focused.mutationCount + focused.ddlCount + focused.unknownCount > 0 && (
            <Alert type="error" showIcon title="目标连接为只读连接，文件包含非查询语句，无法执行。" />
          )}
          {focusedTarget?.environment === 'prod' && (
            <Input value={confirmation} onChange={(event) => setConfirmation(event.target.value)} placeholder={`请输入生产连接名：${focused.connectionName}`} />
          )}
          <Button type="primary" danger={focused.mutationCount + focused.ddlCount + focused.unknownCount > 0}
                  disabled={Boolean(focusedTarget?.readonly && focused.mutationCount + focused.ddlCount + focused.unknownCount > 0)}
                  icon={<PlayCircleOutlined />} onClick={() => void start(focused)}>确认并执行</Button>
        </div>
      )}

      <div className="sql-file-job-list">
        {loading ? <div className="sql-file-job-empty"><Spin /></div> : jobs.length === 0 ? <Empty description="暂无 SQL 文件任务" /> : jobs.map((job) => {
          const percent = sqlFileTaskPercent(job);
          return (
            <div key={job.id} className={`sql-file-job-item${job.id === focused?.id ? ' is-selected' : ''}`}
                 onClick={() => { setFocusedId(job.id); setConfirmation(''); }}>
              <div className="sql-file-job-header">
                <Space wrap><Text strong>{job.fileName}</Text><Tag color={statusColor(job.status)}>{sqlFileStatusLabel(job.status)}</Tag></Space>
                {CANCELLABLE.has(job.status) && (
                  <Button size="small" danger icon={<PauseCircleOutlined />} onClick={(event) => { event.stopPropagation(); void cancel(job); }}>取消</Button>
                )}
              </div>
              <Space orientation="vertical" size={3} className="full-width">
                <Text type="secondary">{job.connectionName} · {formatSqlFileBytes(job.fileSize)} · {formatTime(job.createdAt)}</Text>
                {(ACTIVE.has(job.status) || job.status === 'READY') && <Progress size="small" percent={percent} status={job.status === 'RUNNING' || job.status === 'ANALYZING' ? 'active' : 'normal'} />}
                {job.statementTotal != null && <Text type="secondary">进度 {job.statementCurrent}/{job.statementTotal} · 成功 {job.successCount} · 查询返回 {job.queryRowCount} 行</Text>}
                {job.message && <Text type={job.status === 'FAILED' ? 'danger' : 'secondary'}>{job.message}</Text>}
                {job.failedStatementIndex != null && <Alert type="error" showIcon title={`第 ${job.failedStatementIndex} 条执行失败`} description={job.failedSqlPreview ? <pre className="sql-file-error-sql">{job.failedSqlPreview}</pre> : undefined} />}
              </Space>
            </div>
          );
        })}
      </div>
    </Drawer>
  );
});

function statusColor(status: SqlFileExecution['status']) {
  if (status === 'SUCCESS') return 'success';
  if (status === 'FAILED' || status === 'EXPIRED') return 'error';
  if (status === 'CANCELLED') return 'default';
  if (status === 'READY') return 'warning';
  return 'processing';
}

function formatTime(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }); }
