import { memo, useMemo, useState } from 'react';
import { Alert, Button, Form, Input, Radio, Select, Space, Typography } from 'antd';
import { SwapOutlined } from '@ant-design/icons';
import { api } from '../api';
import { localizeError } from '../utils';
import { IMPORT_CONFLICT_OPTIONS, type ImportConflictMode } from '../dataImport';
import {
  buildDataTransferRequest,
  canRunTransfer,
  describeTransfer,
  EMPTY_DATA_TRANSFER_FORM,
  isSelfCopy,
  sourceConfirmationName,
  withSourceTable,
  type DataTransferForm
} from '../dataTransfer';
import type { Connection, SqlFileExecution } from '../types';

const { Text } = Typography;

/**
 * 跨连接数据传输。
 *
 * <p>面板只做「描述一次传输」这一件事。点下去之后拿到的是一个待执行的后台任务，不是已经
 * 写完的数据 —— 目标端的生产确认、语句构成预览、进度与取消都在「SQL 文件执行」那个抽屉里，
 * 与文件导入走的是同一条路。把两步并成一步，就等于让一次点击直接往生产库写几百万行。</p>
 */
export const DataTransferPanel = memo(function DataTransferPanel({
  connections,
  defaultConnectionId,
  onRequestConfirmation,
  onOpenTasks
}: {
  connections: Connection[];
  defaultConnectionId?: number;
  /** 生产连接的二次确认；expected 为空表示这条连接不是生产连接。 */
  onRequestConfirmation: (action: string, expected?: string) => Promise<string | undefined>;
  onOpenTasks: (connectionId: number) => void;
}) {
  const [form, setForm] = useState<DataTransferForm>({
    ...EMPTY_DATA_TRANSFER_FORM,
    sourceConnectionId: defaultConnectionId
  });
  const [job, setJob] = useState<SqlFileExecution | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const connectionOptions = useMemo(
    () => connections.map((connection) => ({
      value: connection.id,
      label: `${connection.name}（${connection.dbType}${connection.environment === 'prod' ? ' · 生产' : ''}）`
    })),
    [connections]
  );
  const conflictHint = IMPORT_CONFLICT_OPTIONS.find((option) => option.value === form.conflictMode)?.hint;

  async function run() {
    const expected = sourceConfirmationName(form, connections);
    if (expected) {
      // 数据要离开源库，和导出同档；拿不到确认就直接 return。
      const confirmation = await onRequestConfirmation('从生产连接传输数据', expected);
      if (!confirmation) return;
      await submit(confirmation);
      return;
    }
    await submit(undefined);
  }

  async function submit(confirmation?: string) {
    setLoading(true);
    setError('');
    try {
      const created = await api<SqlFileExecution>('/data/transfer', {
        method: 'POST',
        body: JSON.stringify(buildDataTransferRequest(form)),
        headers: confirmation ? { 'X-Production-Confirmation': encodeURIComponent(confirmation) } : undefined
      });
      setJob(created);
    } catch (e) {
      setJob(null);
      setError(localizeError(e));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="传输会先生成一个待执行的后台任务"
        description="点击后先读源端并生成写入脚本，你会看到要写多少行；真正写库要到「SQL 文件执行」里再确认一次，那一步照常做生产确认与审计。"
      />

      <Form layout="vertical" size="small" className="schema-diff-endpoints">
        <Space size={12} wrap align="start">
          <Form.Item label="源连接">
            <Select
              style={{ width: 240 }}
              value={form.sourceConnectionId}
              options={connectionOptions}
              placeholder="选择源连接"
              onChange={(value) => setForm({ ...form, sourceConnectionId: value })}
            />
          </Form.Item>
          <Form.Item label="源 Schema" tooltip="留空则用连接当前所在的 Schema/数据库">
            <Input
              style={{ width: 150 }}
              value={form.sourceSchema}
              placeholder="默认"
              onChange={(event) => setForm({ ...form, sourceSchema: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="取数方式">
            <Radio.Group
              size="small"
              optionType="button"
              value={form.sourceMode}
              options={[{ value: 'table', label: '整表' }, { value: 'query', label: '自定义查询' }]}
              onChange={(event) => setForm({ ...form, sourceMode: event.target.value })}
            />
          </Form.Item>
          {form.sourceMode === 'table' && (
            <Form.Item label="源表" required>
              <Input
                style={{ width: 180 }}
                value={form.sourceTable}
                placeholder="orders"
                onChange={(event) => setForm(withSourceTable(form, event.target.value))}
              />
            </Form.Item>
          )}
        </Space>

        {form.sourceMode === 'query' && (
          <Form.Item
            label="源查询"
            required
            tooltip="只能是一条 SELECT。列名按名字匹配目标表的字段，对不上时在这里写 AS 改名。"
          >
            <Input.TextArea
              rows={4}
              value={form.sourceSql}
              placeholder="SELECT id, title AS name FROM orders WHERE created_at >= '2025-01-01'"
              onChange={(event) => setForm({ ...form, sourceSql: event.target.value })}
            />
          </Form.Item>
        )}

        <Space size={12} wrap align="start">
          <Form.Item label="目标连接">
            <Select
              style={{ width: 240 }}
              value={form.targetConnectionId}
              options={connectionOptions}
              placeholder="选择目标连接"
              onChange={(value) => setForm({ ...form, targetConnectionId: value })}
            />
          </Form.Item>
          <Form.Item label="目标 Schema" tooltip="留空则用连接当前所在的 Schema/数据库">
            <Input
              style={{ width: 150 }}
              value={form.targetSchema}
              placeholder="默认"
              onChange={(event) => setForm({ ...form, targetSchema: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="目标表" required tooltip="目标表必须已经存在，且字段名要能对上源端的列名">
            <Input
              style={{ width: 180 }}
              value={form.targetTable}
              placeholder="orders"
              onChange={(event) => setForm({ ...form, targetTable: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="遇到重复主键" tooltip={conflictHint}>
            <Select<ImportConflictMode>
              style={{ width: 180 }}
              value={form.conflictMode}
              options={IMPORT_CONFLICT_OPTIONS.map((option) => ({ value: option.value, label: option.label }))}
              onChange={(value) => setForm({ ...form, conflictMode: value })}
            />
          </Form.Item>
        </Space>

        <Space size={12} wrap align="center">
          <Button
            type="primary"
            size="small"
            icon={<SwapOutlined />}
            loading={loading}
            disabled={!canRunTransfer(form)}
            onClick={() => void run()}
          >
            生成传输任务
          </Button>
          <Text type="secondary">{describeTransfer(form, connections)}</Text>
        </Space>
      </Form>

      {isSelfCopy(form) && (
        <Alert type="warning" showIcon message="源表和目标表是同一张表，这样传输只会让行数翻倍。" />
      )}

      {error && <Alert type="error" showIcon message="传输任务创建失败" description={error} />}

      {job && (
        <Alert
          type="success"
          showIcon
          message={`已生成传输任务：${job.fileName}`}
          description={
            <Space direction="vertical" size={4}>
              <Text type="secondary">脚本已就绪，还没有写入目标库。到「SQL 文件执行」里核对语句数量后再开始执行。</Text>
              <Button size="small" onClick={() => onOpenTasks(job.connectionId)}>查看并执行</Button>
            </Space>
          }
        />
      )}
    </Space>
  );
});
