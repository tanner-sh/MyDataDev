import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Collapse,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Pagination,
  Popconfirm,
  Progress,
  Radio,
  Select,
  Space,
  Steps,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  Upload,
  message
} from 'antd';
import {
  BulbOutlined,
  CheckCircleOutlined,
  CloudUploadOutlined,
  DownloadOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  SwapOutlined
} from '@ant-design/icons';
import { api, apiResponse, downloadBlob, uploadBinary } from '../api';
import { ConnectionField, SchemaField, invalidateSchemaCache } from './ConnectionScopeFields';
import { localizeError } from '../utils';
import {
  buildCombinedDdlPreview,
  canRunAssess,
  createInitialMigrationForm,
  formatMigrationBytes,
  isMigrationJobActive,
  migrationModeLabel,
  migrationStatusMeta,
  parseVerificationReport,
  sourceConfirmationTarget,
  targetConfirmationTarget,
  verifyStatusMeta,
  withColumnTypeOverride,
  withProgramObjectOverride,
  type MigrationFormState
} from '../databaseMigration';
import type {
  AiTranslateObjectResponse,
  AssessmentFinding,
  Connection,
  MigrationArtifact,
  MigrationAssessmentResponse,
  MigrationItem,
  MigrationJob,
  MigrationJobDetail,
  MigrationMode,
  MigrationVerificationReport,
  StorageProfile,
  TableAssessment
} from '../types';

const { Text } = Typography;

export const MigrationPanel = memo(function MigrationPanel({
  connections,
  defaultConnectionId,
  defaultSchema,
  onRequestConfirmation
}: {
  connections: Connection[];
  defaultConnectionId?: number;
  defaultSchema?: string;
  onRequestConfirmation: (action: string, expected?: string) => Promise<string | undefined>;
}) {
  const [viewTab, setViewTab] = useState<'wizard' | 'jobs' | 'artifacts'>('wizard');
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<MigrationFormState>(() =>
    createInitialMigrationForm(defaultConnectionId, defaultSchema || '')
  );

  const [assessment, setAssessment] = useState<MigrationAssessmentResponse | null>(null);
  const [assessing, setAssessing] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');

  const [findingFilter, setFindingFilter] = useState<'ALL' | 'BLOCKER' | 'WARNING' | 'OK'>('ALL');
  const [tableKeyword, setTableKeyword] = useState('');
  const [mappingPage, setMappingPage] = useState(1);
  const [loadingTableIds, setLoadingTableIds] = useState<Record<string, boolean>>({});
  const [selectedTablesInput, setSelectedTablesInput] = useState('');
  const [aiLoadingKey, setAiLoadingKey] = useState<string | null>(null);

  const [activeJobDetail, setActiveJobDetail] = useState<MigrationJobDetail | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [verifyReport, setVerifyReport] = useState<MigrationVerificationReport | null>(null);

  const [jobs, setJobs] = useState<MigrationJob[]>([]);
  const [artifacts, setArtifacts] = useState<MigrationArtifact[]>([]);
  const [storageProfiles, setStorageProfiles] = useState<StorageProfile[]>([]);
  const [loadingLists, setLoadingLists] = useState(false);
  const [uploadPercent, setUploadPercent] = useState<number | null>(null);

  const loadArtifactsAndJobs = useCallback(async () => {
    setLoadingLists(true);
    try {
      const [artPage, jobPage] = await Promise.all([
        api<{ items: MigrationArtifact[] }>('/migrations/artifacts?page=0&pageSize=50'),
        api<{ items: MigrationJob[] }>('/migrations/jobs?page=0&pageSize=50')
      ]);
      setArtifacts(artPage.items || []);
      setJobs(jobPage.items || []);
    } catch {
      // 静默忽略非关键列表刷新异常
    } finally {
      setLoadingLists(false);
    }
  }, []);

  useEffect(() => {
    void loadArtifactsAndJobs();
    api<StorageProfile[]>('/storage-profiles')
      .then((list) => setStorageProfiles((list || []).filter((p) => p.enabled)))
      .catch(() => setStorageProfiles([]));
  }, [loadArtifactsAndJobs]);

  // 活动任务轮询（每 1.5s 刷新进度与表级子项，终态自动停轮询）
  const activeJobId = activeJobDetail?.job.id;
  const activeJobRunning = isMigrationJobActive(activeJobDetail?.job.status);
  useEffect(() => {
    if (!activeJobId || !activeJobRunning) return;
    const timer = window.setInterval(() => {
      api<MigrationJobDetail>(`/migrations/jobs/${activeJobId}`)
        .then((detail) => {
          setActiveJobDetail(detail);
          const parsed = parseVerificationReport(detail.job.verifyReportJson);
          if (parsed) setVerifyReport(parsed);
          if (!isMigrationJobActive(detail.job.status)) {
            if (detail.job.targetConnectionId) {
              invalidateSchemaCache(detail.job.targetConnectionId);
            }
            void loadArtifactsAndJobs();
          }
        })
        .catch(() => { });
    }, 1500);
    return () => window.clearInterval(timer);
  }, [activeJobId, activeJobRunning, loadArtifactsAndJobs]);

  async function runAssess(nextOptions = form.options) {
    setAssessing(true);
    setError('');
    try {
      const resp = await api<MigrationAssessmentResponse>('/migrations/assess', {
        method: 'POST',
        body: JSON.stringify({
          mode: form.mode,
          sourceConnectionId: form.mode === 'IMPORT_ARTIFACT' ? null : form.sourceConnectionId,
          sourceSchema: form.mode === 'IMPORT_ARTIFACT' ? null : form.sourceSchema || null,
          sourceArtifactId: form.mode === 'IMPORT_ARTIFACT' ? form.sourceArtifactId : null,
          targetConnectionId: form.mode === 'EXPORT_ARTIFACT' ? null : form.targetConnectionId,
          targetSchema: form.mode === 'EXPORT_ARTIFACT' ? null : form.targetSchema || null,
          options: nextOptions
        })
      });
      setAssessment(resp);
      setForm((prev) => ({
        ...prev,
        options: {
          ...resp.effectiveOptions,
          selectedTables: prev.options.selectedTables
        }
      }));
      if (step < 1) setStep(1);
    } catch (e) {
      setError(localizeError(e));
    } finally {
      setAssessing(false);
    }
  }

  async function startJob() {
    if (!assessment) return;
    setError('');

    let sourceConfirm: string | undefined;
    const expectedSrc = sourceConfirmationTarget(form, connections);
    if (expectedSrc) {
      sourceConfirm = await onRequestConfirmation('从生产源库读取全量结构与数据', expectedSrc);
      if (!sourceConfirm) return;
    }

    let targetConfirm: string | undefined;
    const expectedTgt = targetConfirmationTarget(form, connections);
    if (expectedTgt) {
      targetConfirm = await onRequestConfirmation('向目标库执行异构迁移建表与写入', expectedTgt);
      if (!targetConfirm) return;
    }

    setStarting(true);
    try {
      const created = await api<MigrationJob>('/migrations/jobs', {
        method: 'POST',
        body: JSON.stringify({
          planToken: assessment.planToken,
          options: form.options,
          sourceProductionConfirmation: sourceConfirm,
          targetProductionConfirmation: targetConfirm
        })
      });
      const detail = await api<MigrationJobDetail>(`/migrations/jobs/${created.id}`);
      setActiveJobDetail(detail);
      setVerifyReport(parseVerificationReport(detail.job.verifyReportJson));
      setStep(3);
      void loadArtifactsAndJobs();
    } catch (e) {
      setError(localizeError(e));
    } finally {
      setStarting(false);
    }
  }

  async function handleAiTranslate(objectKey: string) {
    if (!assessment) return;
    setAiLoadingKey(objectKey);
    try {
      const res = await api<AiTranslateObjectResponse>('/migrations/ai-translate', {
        method: 'POST',
        body: JSON.stringify({ planToken: assessment.planToken, objectKey })
      });
      setForm((prev) => ({
        ...prev,
        options: withProgramObjectOverride(prev.options, objectKey, res.targetSql)
      }));
      setAssessment((prev) => prev ? {
        ...prev,
        programObjects: prev.programObjects.map((o) =>
          o.key === objectKey ? { ...o, targetSql: res.targetSql, aiAssisted: true, translationMode: 'AI_ASSISTED' } : o
        )
      } : prev);
      message.success(res.compileMessage || '已完成 AI 辅助转译，请审阅 SQL');
    } catch (e) {
      message.error(localizeError(e));
    } finally {
      setAiLoadingKey(null);
    }
  }

  async function handleCancelJob(jobId: number) {
    try {
      await api<MigrationJob>(`/migrations/jobs/${jobId}/cancel`, { method: 'POST' });
      const detail = await api<MigrationJobDetail>(`/migrations/jobs/${jobId}`);
      setActiveJobDetail(detail);
      message.info('已发出取消请求，等待当前批次安全结束');
    } catch (e) {
      message.error(localizeError(e));
    }
  }

  async function handleRetryFailed(job: MigrationJob) {
    let targetConfirm: string | undefined;
    if (job.targetConnectionId) {
      const conn = connections.find((c) => c.id === job.targetConnectionId);
      if (conn?.environment === 'PROD') {
        targetConfirm = await onRequestConfirmation('在生产目标库上断点重试失败迁移项', conn.name);
        if (!targetConfirm) return;
      }
    }
    try {
      await api<MigrationJob>(`/migrations/jobs/${job.id}/retry-failed`, {
        method: 'POST',
        body: JSON.stringify({ targetProductionConfirmation: targetConfirm })
      });
      const detail = await api<MigrationJobDetail>(`/migrations/jobs/${job.id}`);
      setActiveJobDetail(detail);
      message.success('已启动失败项断点重试');
    } catch (e) {
      message.error(localizeError(e));
    }
  }

  async function handleVerify(jobId: number, level: string) {
    setVerifying(true);
    try {
      const report = await api<MigrationVerificationReport>(`/migrations/jobs/${jobId}/verify`, {
        method: 'POST',
        body: JSON.stringify({ verifyLevel: level, tables: [] })
      });
      setVerifyReport(report);
      const detail = await api<MigrationJobDetail>(`/migrations/jobs/${jobId}`);
      setActiveJobDetail(detail);
      setStep(4);
      message.success(report.overallStatus === 'PASSED' ? '一致性校验全部通过' : '校验发现差异，请查看明细');
    } catch (e) {
      message.error(localizeError(e));
    } finally {
      setVerifying(false);
    }
  }

  async function downloadArtifactFile(artifact: MigrationArtifact) {
    try {
      const res = await apiResponse(`/migrations/artifacts/${artifact.id}/download`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = artifact.name;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      message.error(localizeError(e));
    }
  }

  async function downloadMarkdownReport(jobId: number) {
    try {
      const res = await apiResponse(`/migrations/jobs/${jobId}/verify/report.md`);
      const text = await res.text();
      const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `migration-verify-job-${jobId}.md`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      message.error(localizeError(e));
    }
  }

  async function handleUploadArtifact(file: File) {
    setUploadPercent(1);
    try {
      const uploaded = await uploadBinary<MigrationArtifact>(
        `/migrations/artifacts/upload?filename=${encodeURIComponent(file.name)}`,
        file,
        (pct) => setUploadPercent(pct)
      );
      message.success(`制品「${uploaded.name}」上传并校验成功（${uploaded.tableCount} 张表，${uploaded.totalRows} 行）`);
      await loadArtifactsAndJobs();
      setForm((prev) => ({ ...prev, mode: 'IMPORT_ARTIFACT', sourceArtifactId: uploaded.id }));
    } catch (e) {
      message.error(localizeError(e));
    } finally {
      setUploadPercent(null);
    }
    return false;
  }

  async function openJobInWizard(jobId: number) {
    try {
      const detail = await api<MigrationJobDetail>(`/migrations/jobs/${jobId}`);
      setActiveJobDetail(detail);
      const parsed = parseVerificationReport(detail.job.verifyReportJson);
      setVerifyReport(parsed);
      setViewTab('wizard');
      setStep(parsed ? 4 : 3);
    } catch (e) {
      message.error(localizeError(e));
    }
  }

  async function loadDeferredTable(tableId: string) {
    if (!assessment) return;
    const target = assessment.tables.find((t) => t.tableId === tableId);
    if (!target || !target.detailsDeferred || loadingTableIds[tableId]) return;
    setLoadingTableIds((prev) => ({ ...prev, [tableId]: true }));
    try {
      const full = await api<TableAssessment>(
        `/migrations/plans/${encodeURIComponent(assessment.planToken)}/tables/${encodeURIComponent(tableId)}`
      );
      setAssessment((prev) => prev ? {
        ...prev,
        tables: prev.tables.map((t) => (t.tableId === tableId ? full : t))
      } : prev);
    } catch (e) {
      message.error(localizeError(e));
    } finally {
      setLoadingTableIds((prev) => ({ ...prev, [tableId]: false }));
    }
  }

  async function handleDownloadFullDdl() {
    if (!assessment) return;
    try {
      const res = await apiResponse(`/migrations/plans/${encodeURIComponent(assessment.planToken)}/ddl`);
      const blob = await res.blob();
      downloadBlob(blob, `migration-${assessment.sourceSchema || 'schema'}-ddl.sql`);
    } catch (e) {
      message.error(localizeError(e));
    }
  }

  const allFindings = useMemo(() => {
    if (!assessment) return [];
    const merged: AssessmentFinding[] = [
      ...assessment.globalFindings,
      ...assessment.tables.flatMap((t) => t.findings),
      ...assessment.programObjects.flatMap((p) => p.findings)
    ];
    if (findingFilter === 'ALL') return merged;
    return merged.filter((f) => f.severity === findingFilter);
  }, [assessment, findingFilter]);

  const filteredTables = useMemo(() => {
    if (!assessment) return [];
    const kw = tableKeyword.trim().toLowerCase();
    if (!kw) return assessment.tables;
    return assessment.tables.filter(
      (t) => t.sourceName.toLowerCase().includes(kw) || t.targetName.toLowerCase().includes(kw)
    );
  }, [assessment, tableKeyword]);

  const pagedMappingTables = useMemo(() => {
    const start = (mappingPage - 1) * 30;
    return filteredTables.slice(start, start + 30);
  }, [filteredTables, mappingPage]);

  const combinedDdl = useMemo(() => (assessment ? buildCombinedDdlPreview(assessment) : ''), [assessment]);

  const modeChanged = (mode: MigrationMode) => {
    setForm((prev) => ({ ...prev, mode }));
    setAssessment(null);
    setError('');
    setStep(0);
  };

  return (
    <Space className="full-width" orientation="vertical" size="middle">
      <Tabs
        size="small"
        activeKey={viewTab}
        onChange={(k) => setViewTab(k as 'wizard' | 'jobs' | 'artifacts')}
        items={[
          { key: 'wizard', label: '转换向导' },
          { key: 'jobs', label: `迁移任务 (${jobs.length})` },
          { key: 'artifacts', label: `标准制品库 .mddpkg (${artifacts.length})` }
        ]}
        tabBarExtraContent={
          <Button size="small" icon={<ReloadOutlined />} loading={loadingLists} onClick={() => void loadArtifactsAndJobs()}>
            刷新
          </Button>
        }
      />

      {error && <Alert type="error" showIcon closable onClose={() => setError('')} title={error} />}

      {viewTab === 'wizard' && (
        <Space className="full-width" orientation="vertical" size="middle">
          <Steps
            size="small"
            current={step}
            onChange={(next) => {
              if (next === 0) setStep(0);
              else if (next <= 2 && assessment) setStep(next);
              else if (next === 3 && activeJobDetail) setStep(3);
              else if (next === 4 && verifyReport) setStep(4);
            }}
            items={[
              { title: '源/目标与策略' },
              { title: '预检评估' },
              { title: '映射与 DDL 审阅' },
              { title: '后台执行监控' },
              { title: '一致性校验' }
            ]}
          />

          {/* 步骤 1：配置 */}
          {step === 0 && (
            <Space className="full-width" orientation="vertical" size="middle">
              <Alert
                type="info"
                showIcon
                title="支持全部 11 种数据库双向转换（强类型流式直传 + 通用标准制品 .mddpkg）"
                description="全程采用 JDBC 强类型参数绑定与流式大对象帧，原生支持 10GB 级重度 BLOB/CLOB 数据无损迁移及 256-bit 顺序无关全字节校验。"
              />

              <Form layout="vertical" size="small">
                <Form.Item label="转换模式">
                  <Radio.Group
                    optionType="button"
                    buttonStyle="solid"
                    value={form.mode}
                    onChange={(e) => modeChanged(e.target.value as MigrationMode)}
                    options={[
                      { value: 'ONLINE_DIRECT', label: '双连接在线直迁' },
                      { value: 'EXPORT_ARTIFACT', label: '导出标准制品 (.mddpkg)' },
                      { value: 'IMPORT_ARTIFACT', label: '导入标准制品 (.mddpkg)' }
                    ]}
                  />
                </Form.Item>

                <Space size={12} wrap align="start">
                  {form.mode !== 'IMPORT_ARTIFACT' && (
                    <>
                      <ConnectionField
                        label="源数据库连接"
                        connections={connections}
                        value={form.sourceConnectionId}
                        placeholder="选择源库（如 Oracle）"
                        onChange={(value) => setForm((prev) => ({ ...prev, sourceConnectionId: value, sourceSchema: '' }))}
                      />
                      <SchemaField
                        label="源 Schema / 库"
                        connectionId={form.sourceConnectionId}
                        value={form.sourceSchema}
                        onChange={(value) => setForm((prev) => ({ ...prev, sourceSchema: value }))}
                      />
                    </>
                  )}

                  {form.mode === 'IMPORT_ARTIFACT' && (
                    <Form.Item label="选择标准制品 (.mddpkg)" required style={{ minWidth: 320 }}>
                      <Space orientation="vertical" className="full-width" size={6}>
                        <Select
                          placeholder="从制品库选择 .mddpkg"
                          value={form.sourceArtifactId}
                          onChange={(v) => setForm((prev) => ({ ...prev, sourceArtifactId: v }))}
                          options={artifacts.map((a) => ({
                            value: a.id,
                            label: `${a.name} (${a.sourceDbType} · ${a.tableCount}表 · ${formatMigrationBytes(a.fileSize)})`
                          }))}
                        />
                        <Upload
                          accept=".mddpkg"
                          showUploadList={false}
                          beforeUpload={(file) => handleUploadArtifact(file)}
                        >
                          <Button size="small" icon={<CloudUploadOutlined />}>上传本地 .mddpkg 制品</Button>
                        </Upload>
                        {uploadPercent !== null && <Progress percent={uploadPercent} size="small" />}
                      </Space>
                    </Form.Item>
                  )}

                  {form.mode !== 'EXPORT_ARTIFACT' && (
                    <>
                      <ConnectionField
                        label="目标数据库连接"
                        connections={connections.filter((c) => !c.readonly)}
                        value={form.targetConnectionId}
                        placeholder="选择目标库（如 OceanBase / MySQL / PG）"
                        onChange={(value) => setForm((prev) => ({ ...prev, targetConnectionId: value, targetSchema: '' }))}
                      />
                      <SchemaField
                        label="目标 Schema / 库"
                        tooltip="可直接选择已有 Schema/库，或手工输入新名称（开启自动创建时会在迁移前自动新建）"
                        placeholder="选择或输入新名称"
                        connectionId={form.targetConnectionId}
                        value={form.targetSchema}
                        onChange={(value) => setForm((prev) => ({ ...prev, targetSchema: value }))}
                      />
                    </>
                  )}
                </Space>

                {form.mode === 'EXPORT_ARTIFACT' && (
                  <Space size={12} wrap align="start">
                    <Form.Item label="制品文件名（可选）">
                      <Input
                        style={{ width: 260 }}
                        placeholder="留空自动按连接与时间命名.mddpkg"
                        value={form.options.artifactName || ''}
                        onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, artifactName: e.target.value || null } }))}
                      />
                    </Form.Item>
                    {storageProfiles.length > 0 && (
                      <Form.Item label="同步上传到远端存储（可选）">
                        <Select
                          allowClear
                          style={{ width: 220 }}
                          placeholder="仅保存在本地制品库"
                          value={form.options.storageProfileId ?? undefined}
                          onChange={(v) => setForm((p) => ({ ...p, options: { ...p.options, storageProfileId: v ?? null } }))}
                          options={storageProfiles.map((s) => ({ value: s.id, label: `${s.name} (${s.type})` }))}
                        />
                      </Form.Item>
                    )}
                  </Space>
                )}

                <Collapse
                  size="small"
                  items={[
                    {
                      key: 'adv',
                      label: '转换策略与高级选项',
                      children: (
                        <Space orientation="vertical" size={10} className="full-width">
                          <Space size={16} wrap>
                            {form.mode !== 'EXPORT_ARTIFACT' && (
                              <>
                                <Form.Item label="目标表已存在策略">
                                  <Select
                                    style={{ width: 210 }}
                                    value={form.options.conflictMode}
                                    onChange={(v) => setForm((p) => ({ ...p, options: { ...p.options, conflictMode: v } }))}
                                    options={[
                                      { value: 'FAIL', label: '中止报错（默认最安全）' },
                                      { value: 'SKIP', label: '跳过已存在表' },
                                      { value: 'TRUNCATE_AND_LOAD', label: '清空数据后重灌（需确认）' },
                                      { value: 'DROP_AND_RECREATE', label: '删除重建目标表（需确认）' }
                                    ]}
                                  />
                                </Form.Item>
                                <Form.Item label="标识符大小写">
                                  <Select
                                    style={{ width: 150 }}
                                    value={form.options.identifierCasePolicy}
                                    onChange={(v) => setForm((p) => ({ ...p, options: { ...p.options, identifierCasePolicy: v } }))}
                                    options={[
                                      { value: 'PRESERVE', label: '保持源库原样' },
                                      { value: 'LOWER', label: '统一转小写' },
                                      { value: 'UPPER', label: '统一转大写' }
                                    ]}
                                  />
                                </Form.Item>
                                <Form.Item label="校验级别">
                                  <Select
                                    style={{ width: 200 }}
                                    value={form.options.verifyLevel}
                                    onChange={(v) => setForm((p) => ({ ...p, options: { ...p.options, verifyLevel: v } }))}
                                    options={[
                                      { value: 'L3_FULL_HASH', label: 'L3 全字节 256-bit 哈希（推荐）' },
                                      { value: 'L2_AGGREGATE', label: 'L2 行数 + 列聚合/LOB字节' },
                                      { value: 'L1_COUNT', label: 'L1 快速行数核对' }
                                    ]}
                                  />
                                </Form.Item>
                              </>
                            )}
                            <Form.Item label="并行并发度" tooltip="同时作用于并行导出、分片级并发导入、索引构建与 L3 校验（最大 8）">
                              <InputNumber
                                min={1}
                                max={8}
                                value={form.options.concurrency}
                                onChange={(v) => setForm((p) => ({ ...p, options: { ...p.options, concurrency: Number(v || 4) } }))}
                              />
                            </Form.Item>
                          </Space>

                          <Space size={16} wrap>
                            {form.mode !== 'EXPORT_ARTIFACT' && (
                              <Checkbox
                                checked={form.options.createTargetSchemaIfMissing}
                                onChange={(e) => setForm((p) => ({
                                  ...p,
                                  options: { ...p.options, createTargetSchemaIfMissing: e.target.checked }
                                }))}
                              >
                                目标 Schema / 库不存在时自动创建
                              </Checkbox>
                            )}
                            <Checkbox
                              checked={form.options.includeData}
                              onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, includeData: e.target.checked } }))}
                            >
                              迁移表数据
                            </Checkbox>
                            <Checkbox
                              checked={form.options.includeIndexes}
                              onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, includeIndexes: e.target.checked } }))}
                            >
                              二级索引
                            </Checkbox>
                            <Checkbox
                              checked={form.options.includeForeignKeys}
                              onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, includeForeignKeys: e.target.checked } }))}
                            >
                              外键约束
                            </Checkbox>
                            <Checkbox
                              checked={form.options.includeComments}
                              onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, includeComments: e.target.checked } }))}
                            >
                              表/列注释
                            </Checkbox>
                            <Checkbox
                              checked={form.options.includeSequences}
                              onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, includeSequences: e.target.checked } }))}
                            >
                              序列与自增对齐
                            </Checkbox>
                            <Checkbox
                              checked={form.options.includeProgramObjects}
                              onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, includeProgramObjects: e.target.checked } }))}
                            >
                              执行视图/过程/触发器
                            </Checkbox>
                            <Checkbox
                              checked={form.options.caseSensitiveCollation}
                              onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, caseSensitiveCollation: e.target.checked } }))}
                            >
                              MySQL 使用大小写敏感排序 (utf8mb4_bin)
                            </Checkbox>
                            <Checkbox
                              checked={form.options.useOracleScnSnapshot}
                              onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, useOracleScnSnapshot: e.target.checked } }))}
                            >
                              Oracle 源启用 AS OF SCN 快照
                            </Checkbox>
                            <Checkbox
                              checked={form.options.verifyAfterMigration}
                              onChange={(e) => setForm((p) => ({ ...p, options: { ...p.options, verifyAfterMigration: e.target.checked } }))}
                            >
                              迁移后自动校验
                            </Checkbox>
                          </Space>

                          <Form.Item
                            label="仅迁移指定表（可选）"
                            tooltip="对于上千张表的大型数据库，可填入少量表名先验证转换效果；留空表示迁移整个 Schema 的全部表"
                            style={{ marginBottom: 0 }}
                          >
                            <Input
                              allowClear
                              placeholder="留空迁移整库；多个表名用英文逗号或空格分隔（如 BD_CORP, GL_VOUCHER）"
                              value={selectedTablesInput}
                              onChange={(e) => {
                                const raw = e.target.value;
                                setSelectedTablesInput(raw);
                                const parsed = raw.split(/[\s,，;；]+/).map((s) => s.trim()).filter(Boolean);
                                setForm((p) => ({ ...p, options: { ...p.options, selectedTables: parsed } }));
                              }}
                            />
                          </Form.Item>
                        </Space>
                      )
                    }
                  ]}
                />

                <div style={{ marginTop: 12 }}>
                  <Button
                    type="primary"
                    icon={<SwapOutlined />}
                    disabled={!canRunAssess(form)}
                    loading={assessing}
                    onClick={() => void runAssess()}
                  >
                    采集结构并执行兼容性评估
                  </Button>
                </div>
              </Form>
            </Space>
          )}

          {/* 步骤 2：兼容性预检评估看板 */}
          {step === 1 && assessment && (
            <Space className="full-width" orientation="vertical" size="middle">
              <Descriptions size="small" bordered column={4}>
                <Descriptions.Item label="源库">{assessment.sourceDbType} / {assessment.sourceSchema || '-'}</Descriptions.Item>
                <Descriptions.Item label="目标库">
                  <Space size={4} wrap>
                    <span>{assessment.targetDbType || '标准制品 .mddpkg'} / {assessment.targetSchema || '-'}</span>
                    {assessment.targetSchemaExists === false && (
                      <Tag color={assessment.effectiveOptions.createTargetSchemaIfMissing ? 'blue' : 'error'}>
                        {assessment.effectiveOptions.createTargetSchemaIfMissing ? '不存在·将自动创建' : '不存在'}
                      </Tag>
                    )}
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label="对象总数">
                  {assessment.summary.tableCount} 表 · {assessment.summary.sequenceCount} 序列 · {assessment.summary.programObjectCount} 可编程对象
                </Descriptions.Item>
                <Descriptions.Item label="评估结论">
                  <Space size={4}>
                    <Tag color="success">通过 {assessment.summary.okCount}</Tag>
                    <Tag color="warning">提示 {assessment.summary.warningCount}</Tag>
                    {assessment.summary.blockerCount > 0 && <Tag color="error">阻断 {assessment.summary.blockerCount}</Tag>}
                    {assessment.summary.hasLobTables && <Tag color="purple">含 BLOB/CLOB</Tag>}
                  </Space>
                </Descriptions.Item>
              </Descriptions>

              <Space style={{ justifyContent: 'space-between', width: '100%' }}>
                <Radio.Group
                  size="small"
                  value={findingFilter}
                  onChange={(e) => setFindingFilter(e.target.value)}
                  options={[
                    { value: 'ALL', label: '全部提示' },
                    { value: 'BLOCKER', label: '仅阻断' },
                    { value: 'WARNING', label: '仅警告' },
                    { value: 'OK', label: '仅信息' }
                  ]}
                />
                <Space>
                  <Button size="small" onClick={() => setStep(0)}>上一步</Button>
                  <Button size="small" type="primary" onClick={() => setStep(2)}>下一步：审阅映射与 DDL</Button>
                </Space>
              </Space>

              {allFindings.length > 0 && (
                <Table
                  size="small"
                  rowKey={(_, idx) => String(idx)}
                  pagination={{ pageSize: 8 }}
                  dataSource={allFindings}
                  columns={[
                    {
                      title: '级别',
                      dataIndex: 'severity',
                      width: 84,
                      render: (v: string) => (
                        <Tag color={v === 'BLOCKER' ? 'error' : v === 'WARNING' ? 'warning' : 'success'}>{v}</Tag>
                      )
                    },
                    { title: '对象', render: (_, r) => `${r.objectKind} ${r.objectName}${r.columnName ? '.' + r.columnName : ''}`, width: 210 },
                    { title: '说明', dataIndex: 'message' },
                    { title: '建议', dataIndex: 'suggestion', width: 260, render: (v) => v || '-' }
                  ]}
                />
              )}

              <Input
                size="small"
                allowClear
                style={{ maxWidth: 320 }}
                placeholder={`按表名筛选（共 ${assessment.tables.length} 张表）`}
                value={tableKeyword}
                onChange={(e) => { setTableKeyword(e.target.value); setMappingPage(1); }}
              />

              <Table
                size="small"
                rowKey="tableId"
                pagination={{ pageSize: 10 }}
                dataSource={filteredTables}
                columns={[
                  { title: '源表名', dataIndex: 'sourceName' },
                  { title: '目标表名', dataIndex: 'targetName' },
                  { title: '列数', render: (_, r) => r.columnCount ?? r.columns.length, width: 64 },
                  {
                    title: '特征',
                    render: (_, r) => (
                      <Space size={4}>
                        {r.hasPrimaryKey ? <Tag color="blue">PK</Tag> : <Tag color="orange">无主键</Tag>}
                        {r.hasLobColumns && <Tag color="purple">LOB</Tag>}
                        {r.indexCount > 0 && <Tag>索引 {r.indexCount}</Tag>}
                        {r.foreignKeyCount > 0 && <Tag>FK {r.foreignKeyCount}</Tag>}
                      </Space>
                    )
                  },
                  { title: '估算行数', dataIndex: 'estimatedRows', width: 100, render: (v) => v ?? '-' }
                ]}
              />
            </Space>
          )}

          {/* 步骤 3：类型映射微调与 DDL 审阅 */}
          {step === 2 && assessment && (
            <Space className="full-width" orientation="vertical" size="middle">
              <Space style={{ justifyContent: 'space-between', width: '100%' }}>
                <Text type="secondary">
                  可在下方针对任意列微调目标类型，调整后点击「重算 DDL」立即刷新预览。
                </Text>
                <Space>
                  <Button size="small" onClick={() => setStep(1)}>上一步</Button>
                  {assessment.mode !== 'EXPORT_ARTIFACT' && (
                    <Button size="small" loading={assessing} onClick={() => void runAssess(form.options)}>
                      应用覆写并重算 DDL
                    </Button>
                  )}
                  <Button
                    size="small"
                    type="primary"
                    icon={<PlayCircleOutlined />}
                    disabled={!assessment.canExecute}
                    loading={starting}
                    onClick={() => void startJob()}
                  >
                    {assessment.mode === 'EXPORT_ARTIFACT' ? '启动标准制品导出' : '确认并启动后台迁移'}
                  </Button>
                </Space>
              </Space>

              <Tabs
                size="small"
                items={[
                  {
                    key: 'columns',
                    label: `列类型映射 (${assessment.tables.length} 表)`,
                    children: (
                      <Space orientation="vertical" className="full-width" size={10}>
                        <Space style={{ justifyContent: 'space-between', width: '100%' }} wrap>
                          <Input
                            size="small"
                            allowClear
                            style={{ width: 280 }}
                            placeholder={`按表名搜索（匹配 ${filteredTables.length} / ${assessment.tables.length} 张表）`}
                            value={tableKeyword}
                            onChange={(e) => { setTableKeyword(e.target.value); setMappingPage(1); }}
                          />
                          {filteredTables.length > 30 && (
                            <Pagination
                              size="small"
                              current={mappingPage}
                              pageSize={30}
                              total={filteredTables.length}
                              showSizeChanger={false}
                              onChange={(page) => setMappingPage(page)}
                            />
                          )}
                        </Space>
                        <Collapse
                          size="small"
                          defaultActiveKey={pagedMappingTables.slice(0, 2).filter((t) => !t.detailsDeferred).map((t) => t.tableId)}
                          onChange={(keys) => {
                            const arr = Array.isArray(keys) ? keys : [keys];
                            for (const k of arr) void loadDeferredTable(String(k));
                          }}
                          items={pagedMappingTables.map((t) => ({
                            key: t.tableId,
                            label: `${t.sourceName} → ${t.targetName} (${t.columnCount ?? t.columns.length} 列${t.hasLobColumns ? ' · 含 LOB' : ''}${t.detailsDeferred ? ' · 展开按需加载' : ''})`,
                            children: t.detailsDeferred ? (
                              <Text type="secondary">{loadingTableIds[t.tableId] ? '正在按需加载列映射…' : '正在准备列映射详情…'}</Text>
                            ) : (
                              <Table
                                size="small"
                                pagination={false}
                                rowKey="sourceName"
                                dataSource={t.columns}
                                columns={[
                                  { title: '源列', dataIndex: 'sourceName', width: 150 },
                                  { title: '源原生类型', dataIndex: 'sourceNativeType', width: 150 },
                                  { title: '中立类型', dataIndex: 'canonicalType', width: 110, render: (v) => <Tag>{v}</Tag> },
                                  {
                                    title: '目标类型（可覆写）',
                                    width: 210,
                                    render: (_, c) => {
                                      const key = `${t.sourceName}.${c.sourceName}`;
                                      const currentOverride = form.options.columnTypeOverrides[key];
                                      return assessment.mode === 'EXPORT_ARTIFACT' ? (
                                        <Text code>{c.targetType}</Text>
                                      ) : (
                                        <Input
                                          size="small"
                                          placeholder={c.targetType}
                                          value={currentOverride ?? c.targetType}
                                          onChange={(e) =>
                                            setForm((p) => ({
                                              ...p,
                                              options: withColumnTypeOverride(p.options, t.sourceName, c.sourceName, e.target.value)
                                            }))
                                          }
                                        />
                                      );
                                    }
                                  },
                                  {
                                    title: '提示',
                                    render: (_, c) => c.note ? (
                                      <Text type={c.severity === 'WARNING' ? 'warning' : 'secondary'}>{c.note}</Text>
                                    ) : '-'
                                  }
                                ]}
                              />
                            )
                          }))}
                        />
                      </Space>
                    )
                  },
                  ...(assessment.mode !== 'EXPORT_ARTIFACT'
                    ? [{
                        key: 'ddl',
                        label: '目标 DDL 预览',
                        children: (
                          <Space orientation="vertical" className="full-width" size={8}>
                            <Space style={{ justifyContent: 'space-between', width: '100%' }}>
                              <Text type="secondary">
                                {assessment.tables.length > 80
                                  ? `共 ${assessment.tables.length} 张表，页面预览前 80 张表；完整脚本请点击右侧下载。`
                                  : `共 ${assessment.tables.length} 张表的分阶段目标 DDL。`}
                              </Text>
                              <Button size="small" icon={<DownloadOutlined />} onClick={() => void handleDownloadFullDdl()}>
                                下载完整 DDL (.sql)
                              </Button>
                            </Space>
                            <Input.TextArea
                              readOnly
                              rows={16}
                              value={combinedDdl}
                              style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
                            />
                          </Space>
                        )
                      }]
                    : []),
                  ...(assessment.programObjects.length > 0
                    ? [{
                        key: 'objects',
                        label: `可编程对象 (${assessment.programObjects.length})`,
                        children: (
                          <Space orientation="vertical" className="full-width" size="middle">
                            <Space>
                              <Switch
                                size="small"
                                checked={form.options.includeProgramObjects}
                                onChange={(checked) => setForm((p) => ({ ...p, options: { ...p.options, includeProgramObjects: checked } }))}
                              />
                              <Text>在阶段 5 自动执行下方已确认的可编程对象 DDL</Text>
                            </Space>
                            <Collapse
                              size="small"
                              items={assessment.programObjects.map((obj) => ({
                                key: obj.key,
                                label: (
                                  <Space>
                                    <Tag>{obj.kind}</Tag>
                                    <Text strong>{obj.sourceName}</Text>
                                    <Tag color={obj.targetSql ? 'blue' : 'warning'}>{obj.translationMode}</Tag>
                                  </Space>
                                ),
                                extra: assessment.aiTranslationAvailable ? (
                                  <Button
                                    size="small"
                                    icon={<BulbOutlined />}
                                    loading={aiLoadingKey === obj.key}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      void handleAiTranslate(obj.key);
                                    }}
                                  >
                                    AI 辅助转译
                                  </Button>
                                ) : undefined,
                                children: (
                                  <Space orientation="vertical" className="full-width" size={8}>
                                    <Text type="secondary">源对象定义：</Text>
                                    <Input.TextArea readOnly rows={5} value={obj.sourceSql} style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }} />
                                    <Text type="secondary">目标方言候选定义（可手工编辑）：</Text>
                                    <Input.TextArea
                                      rows={6}
                                      value={form.options.programObjectOverrides[obj.key] ?? obj.targetSql}
                                      placeholder="跨方言复杂对象请点击「AI 辅助转译」或手工编写目标 DDL"
                                      onChange={(e) =>
                                        setForm((p) => ({
                                          ...p,
                                          options: withProgramObjectOverride(p.options, obj.key, e.target.value)
                                        }))
                                      }
                                      style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
                                    />
                                  </Space>
                                )
                              }))}
                            />
                          </Space>
                        )
                      }]
                    : [])
                ]}
              />
            </Space>
          )}

          {/* 步骤 4：后台执行监控 */}
          {step === 3 && activeJobDetail && (
            <Space className="full-width" orientation="vertical" size="middle">
              <Descriptions size="small" bordered column={3}>
                <Descriptions.Item label="任务">
                  #{activeJobDetail.job.id} · {migrationModeLabel(activeJobDetail.job.mode)}
                </Descriptions.Item>
                <Descriptions.Item label="状态">
                  <Tag color={migrationStatusMeta(activeJobDetail.job.status).color}>
                    {migrationStatusMeta(activeJobDetail.job.status).label}
                  </Tag>
                  <Text type="secondary" style={{ marginLeft: 8 }}>{activeJobDetail.job.phase}</Text>
                </Descriptions.Item>
                <Descriptions.Item label="累计搬运">
                  {activeJobDetail.job.migratedRows.toLocaleString()} 行 · {formatMigrationBytes(activeJobDetail.job.migratedBytes)}
                </Descriptions.Item>
                <Descriptions.Item label="说明" span={3}>{activeJobDetail.job.message || '-'}</Descriptions.Item>
              </Descriptions>

              <Progress
                percent={activeJobDetail.job.totalItems > 0
                  ? Math.min(100, Math.round((activeJobDetail.job.completedItems / activeJobDetail.job.totalItems) * 100))
                  : (activeJobDetail.job.status === 'SUCCESS' ? 100 : 0)}
                status={
                  activeJobDetail.job.status === 'FAILED' ? 'exception'
                    : activeJobDetail.job.status === 'SUCCESS' ? 'success'
                      : 'active'
                }
              />

              <Space wrap>
                {isMigrationJobActive(activeJobDetail.job.status) && (
                  <Button size="small" danger icon={<StopOutlined />} onClick={() => void handleCancelJob(activeJobDetail.job.id)}>
                    取消任务
                  </Button>
                )}
                {(activeJobDetail.job.status === 'FAILED' || activeJobDetail.job.status === 'PARTIAL_FAILED' || activeJobDetail.job.status === 'CANCELLED') && (
                  <Button size="small" type="primary" icon={<ReloadOutlined />} onClick={() => void handleRetryFailed(activeJobDetail.job)}>
                    断点重试失败项
                  </Button>
                )}
                {activeJobDetail.outputArtifact && (
                  <Button
                    size="small"
                    type="primary"
                    icon={<DownloadOutlined />}
                    onClick={() => void downloadArtifactFile(activeJobDetail.outputArtifact!)}
                  >
                    下载标准制品 ({activeJobDetail.outputArtifact.name} · {formatMigrationBytes(activeJobDetail.outputArtifact.fileSize)})
                  </Button>
                )}
                {activeJobDetail.job.mode !== 'EXPORT_ARTIFACT' && !isMigrationJobActive(activeJobDetail.job.status) && (
                  <Button
                    size="small"
                    icon={<SafetyCertificateOutlined />}
                    loading={verifying}
                    onClick={() => void handleVerify(activeJobDetail.job.id, form.options.verifyLevel)}
                  >
                    执行 L3 全量一致性校验
                  </Button>
                )}
                {verifyReport && (
                  <Button size="small" type="primary" icon={<CheckCircleOutlined />} onClick={() => setStep(4)}>
                    查看验收报告
                  </Button>
                )}
              </Space>

              <Table<MigrationItem>
                size="small"
                rowKey="id"
                pagination={{ pageSize: 12 }}
                dataSource={activeJobDetail.items}
                columns={[
                  { title: '类型', dataIndex: 'itemKind', width: 90, render: (v) => <Tag>{v}</Tag> },
                  { title: '源对象', dataIndex: 'sourceName' },
                  { title: '目标对象', dataIndex: 'targetName' },
                  {
                    title: '状态',
                    dataIndex: 'status',
                    width: 96,
                    render: (v) => {
                      const m = migrationStatusMeta(v);
                      return <Tag color={m.color}>{m.label}</Tag>;
                    }
                  },
                  { title: '已迁行数', dataIndex: 'migratedRows', width: 100, render: (v: number) => v.toLocaleString() },
                  { title: '已迁体积', dataIndex: 'migratedBytes', width: 100, render: (v: number) => formatMigrationBytes(v) },
                  {
                    title: '校验',
                    dataIndex: 'verifyStatus',
                    width: 90,
                    render: (v) => {
                      const m = verifyStatusMeta(v);
                      return <Tag color={m.color}>{m.label}</Tag>;
                    }
                  },
                  { title: '错误信息', dataIndex: 'errorMessage', ellipsis: true, render: (v) => v || '-' }
                ]}
              />
            </Space>
          )}

          {/* 步骤 5：一致性校验与验收报告 */}
          {step === 4 && verifyReport && (
            <Space className="full-width" orientation="vertical" size="middle">
              <Alert
                type={verifyReport.overallStatus === 'PASSED' ? 'success' : 'error'}
                showIcon
                title={verifyReport.overallStatus === 'PASSED'
                  ? `数据一致性校验全部通过（${verifyReport.passedTables}/${verifyReport.totalTables} 张表，${verifyReport.totalRowsVerified.toLocaleString()} 行，${formatMigrationBytes(verifyReport.totalLobBytesVerified)} 大对象字节逐位一致）`
                  : `数据一致性校验存在差异（通过 ${verifyReport.passedTables} / 失败 ${verifyReport.failedTables}）`}
                description={verifyReport.notes.join('；')}
              />

              <Space>
                <Button size="small" onClick={() => setStep(3)}>返回任务监控</Button>
                <Button size="small" loading={verifying} onClick={() => void handleVerify(verifyReport.jobId, 'L3_FULL_HASH')}>
                  重新运行 L3 校验
                </Button>
                <Button size="small" type="primary" icon={<DownloadOutlined />} onClick={() => void downloadMarkdownReport(verifyReport.jobId)}>
                  导出 Markdown 验收报告
                </Button>
              </Space>

              <Table
                size="small"
                rowKey="tableId"
                pagination={{ pageSize: 12 }}
                dataSource={verifyReport.tables}
                columns={[
                  { title: '源表', dataIndex: 'sourceName' },
                  { title: '目标表', dataIndex: 'targetName' },
                  {
                    title: '结论',
                    dataIndex: 'status',
                    width: 90,
                    render: (v) => <Tag color={verifyStatusMeta(v).color}>{verifyStatusMeta(v).label}</Tag>
                  },
                  { title: '行数 (源/目标)', render: (_, r) => `${r.sourceRowCount} / ${r.targetRowCount}` },
                  { title: 'LOB 字节 (源/目标)', render: (_, r) => `${formatMigrationBytes(r.sourceLobBytes)} / ${formatMigrationBytes(r.targetLobBytes)}` },
                  {
                    title: 'L3 256-bit 哈希',
                    render: (_, r) => r.multisetHashMatch === null || r.multisetHashMatch === undefined
                      ? '-'
                      : <Tag color={r.multisetHashMatch ? 'success' : 'error'}>{r.multisetHashMatch ? '逐位一致' : '不一致'}</Tag>
                  },
                  { title: '耗时', dataIndex: 'elapsedMs', width: 80, render: (v) => `${v} ms` },
                  { title: '差异说明', render: (_, r) => r.mismatches.length ? r.mismatches.join('；') : '-' }
                ]}
              />
            </Space>
          )}
        </Space>
      )}

      {viewTab === 'jobs' && (
        <Table<MigrationJob>
          size="small"
          rowKey="id"
          loading={loadingLists}
          dataSource={jobs}
          pagination={{ pageSize: 12 }}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 64 },
            { title: '模式', dataIndex: 'mode', width: 150, render: (v) => migrationModeLabel(v) },
            { title: '转换路径', render: (_, r) => `${r.sourceDbType} → ${r.targetDbType || '.mddpkg'}` },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (v) => <Tag color={migrationStatusMeta(v).color}>{migrationStatusMeta(v).label}</Tag>
            },
            {
              title: '校验',
              dataIndex: 'verifyStatus',
              width: 90,
              render: (v) => <Tag color={verifyStatusMeta(v).color}>{verifyStatusMeta(v).label}</Tag>
            },
            { title: '进度', render: (_, r) => `${r.completedItems}/${r.totalItems} 项 · ${r.migratedRows.toLocaleString()} 行` },
            {
              title: '操作',
              width: 160,
              render: (_, r) => (
                <Space size={4}>
                  <Button size="small" type="link" onClick={() => void openJobInWizard(r.id)}>查看</Button>
                  {(r.status === 'FAILED' || r.status === 'PARTIAL_FAILED' || r.status === 'CANCELLED') && (
                    <Button size="small" type="link" onClick={() => void handleRetryFailed(r)}>重试</Button>
                  )}
                </Space>
              )
            }
          ]}
        />
      )}

      {viewTab === 'artifacts' && (
        <Space className="full-width" orientation="vertical" size="middle">
          <Space>
            <Upload accept=".mddpkg" showUploadList={false} beforeUpload={(file) => handleUploadArtifact(file)}>
              <Button size="small" type="primary" icon={<CloudUploadOutlined />}>上传标准制品 (.mddpkg)</Button>
            </Upload>
            {uploadPercent !== null && <Progress percent={uploadPercent} size="small" style={{ width: 200 }} />}
          </Space>

          <Table<MigrationArtifact>
            size="small"
            rowKey="id"
            loading={loadingLists}
            dataSource={artifacts}
            pagination={{ pageSize: 10 }}
            columns={[
              { title: '制品名称', dataIndex: 'name' },
              { title: '源库类型', dataIndex: 'sourceDbType', width: 130, render: (v) => <Tag>{v}</Tag> },
              { title: '源 Schema', dataIndex: 'sourceSchema', width: 120, render: (v) => v || '-' },
              { title: '表 / 行', width: 130, render: (_, r) => `${r.tableCount} 表 · ${r.totalRows.toLocaleString()} 行` },
              { title: '体积', dataIndex: 'fileSize', width: 100, render: (v) => formatMigrationBytes(v) },
              {
                title: '操作',
                width: 220,
                render: (_, r) => (
                  <Space size={4}>
                    <Button
                      size="small"
                      type="link"
                      onClick={() => {
                        setForm((p) => ({ ...p, mode: 'IMPORT_ARTIFACT', sourceArtifactId: r.id }));
                        setViewTab('wizard');
                        setStep(0);
                      }}
                    >
                      导入目标库
                    </Button>
                    <Button size="small" type="link" onClick={() => void downloadArtifactFile(r)}>下载</Button>
                    <Popconfirm
                      title="删除标准制品文件？"
                      onConfirm={async () => {
                        await api(`/migrations/artifacts/${r.id}`, { method: 'DELETE' });
                        void loadArtifactsAndJobs();
                      }}
                    >
                      <Button size="small" type="link" danger>删除</Button>
                    </Popconfirm>
                  </Space>
                )
              }
            ]}
          />
        </Space>
      )}
    </Space>
  );
});
