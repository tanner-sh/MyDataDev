import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Divider,
  Empty,
  Form,
  Input,
  List,
  Progress,
  Select,
  Space,
  Spin,
  Steps,
  Tag,
  Typography,
  Upload,
  message
} from 'antd';
import { CloudUploadOutlined, PauseCircleOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { api } from '../api';
import { DB_TYPE_OPTIONS } from '../constants';
import type {
  ActiveOperations,
  BackupHistory,
  BackupHistoryPage,
  Connection,
  NativeToolStatus,
  RestoreConflictMode,
  RestoreFileFormat,
  RestoreJob,
  RestoreJobPage,
  RestorePreflight,
  RestoreSourceRef,
  RestoreUpload
} from '../types';
import { formatFileSize, formatHistoryTime } from '../utils';
import { nativeToolForRestore, requestedToolPath } from '../nativeTools';
import type { NativeToolMode } from '../nativeTools';

const { Text, Title } = Typography;

type RestoreCenterProps = {
  connections: Connection[];
  selected: Connection | null;
  initialHistory?: BackupHistory | null;
  nativeTools: NativeToolStatus[];
  nativeToolsLoading: boolean;
  nativeToolsError: string;
  onRefreshNativeTools: () => Promise<void>;
};

export function RestoreCenter({ connections, selected, initialHistory, nativeTools, nativeToolsLoading, nativeToolsError, onRefreshNativeTools }: RestoreCenterProps) {
  const [toast, contextHolder] = message.useMessage();
  const [sourceKind, setSourceKind] = useState<'HISTORY' | 'UPLOAD'>(initialHistory ? 'HISTORY' : 'UPLOAD');
  const [historyRows, setHistoryRows] = useState<BackupHistory[]>([]);
  const [source, setSource] = useState<RestoreSourceRef | null>(initialHistory ? { kind: 'HISTORY', id: initialHistory.id } : null);
  const [format, setFormat] = useState<RestoreFileFormat>((initialHistory?.fileFormat as RestoreFileFormat) || 'SQL');
  const [sourceDbType, setSourceDbType] = useState(initialHistory?.sourceDbType || selected?.dbType || 'mysql');
  const [targetId, setTargetId] = useState<number | undefined>(selected?.id);
  const [mode, setMode] = useState<RestoreConflictMode>('SAFE');
  const [toolPath, setToolPath] = useState('');
  const [toolMode, setToolMode] = useState<NativeToolMode>('AUTO');
  const [extraArgs, setExtraArgs] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [namespaceMapping, setNamespaceMapping] = useState<Record<string, string>>({});
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [preflight, setPreflight] = useState<RestorePreflight | null>(null);
  const [preflighting, setPreflighting] = useState(false);
  const [starting, setStarting] = useState(false);
  const [jobs, setJobs] = useState<RestoreJob[]>([]);
  const [jobsLoading, setJobsLoading] = useState(false);
  const pollWasActive = useRef(false);

  const target = useMemo(() => connections.find((item) => item.id === targetId), [connections, targetId]);
  const needsConfirmation = mode === 'OVERWRITE' || target?.environment === 'prod';
  const nativeFormat = format !== 'SQL';
  const detectedTool = nativeTools.find((item) => item.tool === nativeToolForRestore(format));
  const step = !source ? 0 : !preflight?.valid ? 1 : jobs.some((job) => ['QUEUED', 'RUNNING'].includes(job.status)) ? 3 : 2;

  useEffect(() => {
    if (initialHistory) {
      setSourceKind('HISTORY');
      setSource({ kind: 'HISTORY', id: initialHistory.id });
      setFormat((initialHistory.fileFormat as RestoreFileFormat) || methodFormat(initialHistory.backupMethod));
      setSourceDbType(initialHistory.sourceDbType || selected?.dbType || 'mysql');
      setPreflight(null);
    }
  }, [initialHistory?.id]);

  useEffect(() => {
    if (!selected) return;
    void loadHistories();
  }, [selected?.id]);

  useEffect(() => {
    if (!targetId) return;
    void loadJobs();
  }, [targetId]);

  useEffect(() => {
    if (!targetId || !jobs.some((job) => ['QUEUED', 'RUNNING'].includes(job.status))) return;
    pollWasActive.current = true;
    const timer = window.setInterval(async () => {
      if (document.hidden) return;
      try {
        const active = await api<ActiveOperations>(`/restores/operations/active?connectionId=${targetId}`);
        setJobs((current) => current.map((job) => active.restores.find((item) => item.id === job.id) || job));
        if (active.restores.length === 0 && pollWasActive.current) {
          pollWasActive.current = false;
          await loadJobs(false);
        }
      } catch {
        // Keep the last known state; a manual refresh remains available.
      }
    }, 2_000);
    return () => window.clearInterval(timer);
  }, [jobs, targetId]);

  async function loadHistories() {
    if (!selected) return;
    try {
      const page = await api<BackupHistoryPage>(`/backups/history?connectionId=${selected.id}&page=0&pageSize=50`);
      setHistoryRows(page.items.filter((row) => row.status === 'SUCCESS' && row.filePath));
    } catch (error) {
      toast.error(`加载备份历史失败：${(error as Error).message}`);
    }
  }

  async function loadJobs(showLoading = true) {
    if (!targetId) return;
    if (showLoading) setJobsLoading(true);
    try {
      const page = await api<RestoreJobPage>(`/restores?connectionId=${targetId}&page=0&pageSize=20`);
      setJobs(page.items);
    } catch (error) {
      toast.error(`加载恢复记录失败：${(error as Error).message}`);
    } finally {
      setJobsLoading(false);
    }
  }

  async function uploadFile() {
    const file = fileList[0]?.originFileObj;
    if (!file) return toast.warning('请先选择恢复文件');
    setUploading(true);
    try {
      const body = new FormData();
      body.append('file', file);
      const params = new URLSearchParams({ fileFormat: format, sourceDbType });
      const uploaded = await api<RestoreUpload>(`/restores/uploads?${params}`, { method: 'POST', body });
      setSource({ kind: 'UPLOAD', id: uploaded.id });
      setPreflight(null);
      toast.success(`文件已上传，校验值 ${uploaded.checksumSha256.slice(0, 12)}…`);
    } catch (error) {
      toast.error(`上传失败：${(error as Error).message}`);
    } finally {
      setUploading(false);
    }
  }

  function selectHistory(historyId: number) {
    const history = historyRows.find((row) => row.id === historyId);
    setSource(history ? { kind: 'HISTORY', id: history.id } : null);
    if (history) {
      setFormat((history.fileFormat as RestoreFileFormat) || methodFormat(history.backupMethod));
      setSourceDbType(history.sourceDbType || selected?.dbType || sourceDbType);
    }
    setPreflight(null);
  }

  async function runPreflight() {
    if (!source || !targetId) return toast.warning('请选择恢复来源和目标连接');
    setPreflighting(true);
    try {
      const result = await api<RestorePreflight>('/restores/preflight', {
        method: 'POST',
        body: JSON.stringify({ source, targetConnectionId: targetId, sourceDbType, fileFormat: format, conflictMode: mode, namespaceMapping, toolPath: requestedToolPath(toolMode, toolPath), extraArgs })
      });
      setPreflight(result);
      if (result.namespaces.length) {
        setNamespaceMapping((current) => Object.fromEntries(result.namespaces.map((name) => [name, current[name] || name])));
      }
      result.valid ? toast.success('预检通过') : toast.error('预检未通过，请处理错误后重试');
    } catch (error) {
      toast.error(`预检失败：${(error as Error).message}`);
    } finally {
      setPreflighting(false);
    }
  }

  async function startRestore() {
    if (!source || !targetId || !preflight?.planToken) return;
    if (needsConfirmation && confirmation !== target?.name) return toast.error('目标连接名确认不匹配');
    setStarting(true);
    try {
      const job = await api<RestoreJob>('/restores', {
        method: 'POST',
        body: JSON.stringify({ planToken: preflight.planToken, source, targetConnectionId: targetId, sourceDbType, fileFormat: format,
          conflictMode: mode, namespaceMapping, toolPath: requestedToolPath(toolMode, toolPath), extraArgs, productionConfirmation: confirmation || undefined })
      });
      setJobs((current) => [job, ...current.filter((item) => item.id !== job.id)]);
      setPreflight(null);
      toast.success('恢复任务已进入后台队列');
    } catch (error) {
      toast.error(`启动恢复失败：${(error as Error).message}`);
    } finally {
      setStarting(false);
    }
  }

  async function cancel(job: RestoreJob) {
    try {
      const next = await api<RestoreJob>(`/restores/${job.id}/cancel`, { method: 'POST' });
      setJobs((current) => current.map((item) => item.id === next.id ? next : item));
      toast.success('已请求取消恢复');
    } catch (error) {
      toast.error(`取消失败：${(error as Error).message}`);
    }
  }

  return (
    <div className="restore-center">
      {contextHolder}
      <Steps size="small" current={step} items={[{ title: '选择来源' }, { title: '映射与预检' }, { title: '确认执行' }, { title: '运行状态' }]} />
      <div className="restore-grid">
        <section className="restore-card">
          <Title level={5}>1. 恢复来源</Title>
          <Select className="full-width" value={sourceKind} options={[{ value: 'HISTORY', label: '系统备份历史' }, { value: 'UPLOAD', label: '本地上传文件' }]}
            onChange={(value) => { setSourceKind(value); setSource(null); setPreflight(null); }} />
          {sourceKind === 'HISTORY' ? (
            <Form layout="vertical" size="small">
              <Form.Item label="可恢复备份">
                <Select showSearch optionFilterProp="label" value={source?.kind === 'HISTORY' ? source.id : undefined} onChange={selectHistory}
                  options={historyRows.map((row) => ({ value: row.id, label: `${formatHistoryTime(row.finishedAt || row.startedAt || '')} · ${row.fileFormat || row.backupMethod || 'SQL'} · ${formatFileSize(row.fileSize || 0)}` }))}
                  placeholder="选择成功备份" />
              </Form.Item>
            </Form>
          ) : (
            <Space orientation="vertical" className="full-width">
              <Upload.Dragger maxCount={1} fileList={fileList} beforeUpload={(file) => { setFileList([{ uid: file.uid, name: file.name, status: 'done', originFileObj: file }]); setSource(null); return false; }} onRemove={() => { setFileList([]); setSource(null); }}>
                <CloudUploadOutlined className="restore-upload-icon" />
                <div>选择 SQL、MySQL dump 或 Oracle dmp 文件</div>
                <Text type="secondary">文件流式保存，24 小时后自动清理</Text>
              </Upload.Dragger>
              <Button block loading={uploading} disabled={!fileList.length} onClick={uploadFile}>上传并计算校验值</Button>
            </Space>
          )}
          <Divider />
          <Form layout="vertical" size="small">
            <Form.Item label="文件格式"><Select value={format} onChange={(value) => { setFormat(value); setSource(null); setPreflight(null); }} options={[
              { value: 'SQL', label: '可转换 SQL' }, { value: 'MYSQLDUMP', label: 'MySQL 原生 dump' }, { value: 'ORACLE_DMP', label: 'Oracle dmp' }
            ]} /></Form.Item>
            <Form.Item label="源数据库类型"><Select showSearch value={sourceDbType} onChange={(value) => { setSourceDbType(value); setPreflight(null); }} options={DB_TYPE_OPTIONS} /></Form.Item>
          </Form>
        </section>

        <section className="restore-card">
          <Title level={5}>2. 目标与冲突策略</Title>
          <Form layout="vertical" size="small">
            <Form.Item label="目标连接"><Select showSearch optionFilterProp="label" value={targetId} onChange={(value) => { setTargetId(value); setPreflight(null); setConfirmation(''); }} options={connections.map((connection) => ({ value: connection.id, label: `${connection.name} · ${connection.dbType}${connection.readonly ? ' · 只读' : ''}`, disabled: connection.readonly }))} /></Form.Item>
            <Form.Item label="已有对象处理"><Select value={mode} onChange={(value) => { setMode(value); setPreflight(null); }} options={[
              { value: 'SAFE', label: '安全模式：检测到冲突即停止' }, { value: 'OVERWRITE', label: '覆盖模式：删除识别到的表后重建' }, { value: 'APPEND', label: '追加模式：忽略 DDL，仅插入数据' }
            ]} /></Form.Item>
            {nativeFormat && <>
              <Form.Item label="工具路径模式"><Select value={toolMode} onChange={(value) => { setToolMode(value); setPreflight(null); }} options={[{ value: 'AUTO', label: '自动发现（本次预检锁定路径）' }, { value: 'MANUAL', label: '手动指定路径' }]} /></Form.Item>
              {toolMode === 'AUTO' ? <Alert
                className="native-tool-status-alert"
                type={detectedTool?.available ? 'success' : nativeToolsError ? 'error' : 'warning'}
                showIcon
                title={nativeToolsLoading ? '正在检测应用服务器上的恢复工具…' : detectedTool?.available ? `已发现 ${detectedTool.displayName}` : `未发现 ${format === 'MYSQLDUMP' ? 'MySQL mysql' : 'Oracle imp'}`}
                description={detectedTool?.available ? <Space orientation="vertical" size={0}><Text code>{detectedTool.resolvedPath}</Text>{detectedTool.version && <Text type="secondary">{detectedTool.version}</Text>}</Space> : nativeToolsError || detectedTool?.message}
                action={<Button size="small" icon={<ReloadOutlined />} loading={nativeToolsLoading} onClick={() => void onRefreshNativeTools()}>重新检测</Button>}
              /> : <Form.Item label="恢复工具路径" required><Input value={toolPath} onChange={(event) => { setToolPath(event.target.value); setPreflight(null); }} placeholder={format === 'MYSQLDUMP' ? '/usr/local/bin/mysql' : '/opt/oracle/bin/imp'} /></Form.Item>}
              <Form.Item label="额外参数"><Input.TextArea rows={2} value={extraArgs} onChange={(event) => { setExtraArgs(event.target.value); setPreflight(null); }} placeholder="一行一个参数" /></Form.Item>
            </>}
          </Form>
          {preflight?.namespaces.map((namespace) => (
            <Form.Item key={namespace} label={`命名空间 ${namespace}`}><Input value={namespaceMapping[namespace] || ''} onChange={(event) => { setNamespaceMapping((current) => ({ ...current, [namespace]: event.target.value })); setPreflight(null); }} /></Form.Item>
          ))}
          <Button block icon={<SafetyCertificateOutlined />} loading={preflighting} disabled={!source || !targetId} onClick={runPreflight}>执行恢复预检</Button>
        </section>
      </div>

      {preflight && <Alert className="restore-preflight" type={preflight.valid ? 'success' : 'error'} showIcon title={preflight.valid ? `预检通过，共 ${preflight.statementCount} 条语句、${preflight.tables.length} 张表` : '预检未通过'} description={<Space orientation="vertical" size={2}>{preflight.errors.map((item) => <Text type="danger" key={item}>{item}</Text>)}{preflight.warnings.map((item) => <Text type="warning" key={item}>{item}</Text>)}</Space>} />}
      {preflight?.valid && <section className="restore-card restore-confirm-card">
        <Title level={5}>3. 确认执行</Title>
        {needsConfirmation && <Form.Item label={`输入目标连接名“${target?.name}”确认`}><Input value={confirmation} onChange={(event) => setConfirmation(event.target.value)} /></Form.Item>}
        <Button type="primary" danger={mode === 'OVERWRITE'} block loading={starting} disabled={needsConfirmation && confirmation !== target?.name} onClick={startRestore}>开始恢复到 {target?.name}</Button>
      </section>}

      <Divider titlePlacement="start">恢复记录</Divider>
      <Spin spinning={jobsLoading}>
        {jobs.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无恢复记录" /> : <List dataSource={jobs} renderItem={(job) => {
          const total = job.progressTotal || 0;
          const percent = total > 0 ? Math.min(100, Math.round(((job.progressCurrent || 0) / total) * 100)) : 0;
          const active = ['QUEUED', 'RUNNING'].includes(job.status);
          return <List.Item actions={active ? [<Button key="cancel" size="small" danger icon={<PauseCircleOutlined />} onClick={() => void cancel(job)}>取消</Button>] : []}>
            <List.Item.Meta title={<Space><span>{job.sourceName || `恢复任务 ${job.id}`}</span><Tag color={statusColor(job.status)}>{job.status}</Tag></Space>} description={<Space orientation="vertical" size={2} className="full-width"><Text type="secondary">{job.sourceDbType} → {job.targetDbType} · {job.conflictMode} · {formatHistoryTime(job.createdAt)}</Text>{active && <Progress size="small" percent={percent} status="active" />}{job.message && <Text type="secondary">{job.message}</Text>}</Space>} />
          </List.Item>;
        }} />}
      </Spin>
    </div>
  );
}

function methodFormat(method?: string): RestoreFileFormat {
  if (method === 'MYSQLDUMP') return 'MYSQLDUMP';
  if (method === 'ORACLE_EXP') return 'ORACLE_DMP';
  return 'SQL';
}

function statusColor(status: string) {
  if (status === 'SUCCESS') return 'green';
  if (status === 'FAILED') return 'red';
  if (status === 'CANCELLED') return 'orange';
  return 'blue';
}
