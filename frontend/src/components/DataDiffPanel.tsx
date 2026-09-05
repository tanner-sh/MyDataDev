import { memo, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Form, Input, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { CopyOutlined, DiffOutlined, ExportOutlined } from '@ant-design/icons';
import { PanelEmpty } from './PanelState';
import { api } from '../api';
import { localizeError } from '../utils';
import {
  buildDataDiffRequest,
  buildSyncScript,
  canRunDataDiff,
  cellPair,
  DATA_DIFF_CHANGE_LABELS,
  describeTable,
  EMPTY_DATA_DIFF_FORM,
  summarizeDataDiff,
  type DataDiffForm
} from '../dataDiff';
import type { Connection, DataDiffChange, DataDiffResponse, DataDiffRow } from '../types';

const { Text } = Typography;

const CHANGE_COLORS: Record<DataDiffChange, string> = {
  ONLY_IN_SOURCE: 'green',
  ONLY_IN_TARGET: 'orange',
  DIFFERENT: 'blue'
};

/**
 * 两张表的逐行数据对比。
 *
 * <p>和结构对比一样，面板只做「看差异」和「拿脚本」两件事：生成的 INSERT/UPDATE/DELETE 不在
 * 这里执行，而是送进 SQL 工作台，照常经过生产确认、无 WHERE 写操作确认和审计。</p>
 */
export const DataDiffPanel = memo(function DataDiffPanel({ connections, defaultConnectionId, onOpenInSqlTab }: {
  connections: Connection[];
  defaultConnectionId?: number;
  onOpenInSqlTab: (sql: string, title: string) => void;
}) {
  const [form, setForm] = useState<DataDiffForm>({ ...EMPTY_DATA_DIFF_FORM, sourceConnectionId: defaultConnectionId });
  const [result, setResult] = useState<DataDiffResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const connectionOptions = useMemo(
    () => connections.map((connection) => ({ value: connection.id, label: `${connection.name}（${connection.dbType}）` })),
    [connections]
  );
  const script = result ? buildSyncScript(result) : '';

  async function run() {
    setLoading(true);
    setError('');
    try {
      setResult(await api<DataDiffResponse>('/schema-diff/data', {
        method: 'POST',
        body: JSON.stringify(buildDataDiffRequest(form))
      }));
    } catch (e) {
      setResult(null);
      setError(localizeError(e));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Form layout="vertical" size="small" className="schema-diff-endpoints">
        <Space size={12} wrap align="start">
          <Form.Item label="源连接">
            <Select
              style={{ width: 220 }}
              value={form.sourceConnectionId}
              options={connectionOptions}
              placeholder="选择源连接"
              onChange={(value) => setForm({ ...form, sourceConnectionId: value })}
            />
          </Form.Item>
          <Form.Item label="源 Schema" tooltip="留空则用连接当前所在的 Schema/数据库">
            <Input
              style={{ width: 160 }}
              value={form.sourceSchema}
              placeholder="默认"
              onChange={(event) => setForm({ ...form, sourceSchema: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="源表" required>
            <Input
              style={{ width: 180 }}
              value={form.sourceTable}
              placeholder="orders"
              onChange={(event) => setForm({ ...form, sourceTable: event.target.value })}
            />
          </Form.Item>
        </Space>
        <Space size={12} wrap align="start">
          <Form.Item label="目标连接">
            <Select
              style={{ width: 220 }}
              value={form.targetConnectionId}
              options={connectionOptions}
              placeholder="选择目标连接"
              onChange={(value) => setForm({ ...form, targetConnectionId: value })}
            />
          </Form.Item>
          <Form.Item label="目标 Schema" tooltip="留空则用连接当前所在的 Schema/数据库">
            <Input
              style={{ width: 160 }}
              value={form.targetSchema}
              placeholder="默认"
              onChange={(event) => setForm({ ...form, targetSchema: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="目标表" tooltip="留空表示与源表同名">
            <Input
              style={{ width: 180 }}
              value={form.targetTable}
              placeholder="同源表"
              onChange={(event) => setForm({ ...form, targetTable: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="匹配字段" tooltip="留空则用主键；多个字段用逗号分隔">
            <Input
              style={{ width: 200 }}
              value={form.keyColumns}
              placeholder="默认主键"
              onChange={(event) => setForm({ ...form, keyColumns: event.target.value })}
            />
          </Form.Item>
        </Space>
        <Space size={12} wrap>
          <Checkbox
            checked={form.includeDeletes}
            onChange={(event) => setForm({ ...form, includeDeletes: event.target.checked })}
          >
            <Tooltip title="目标端多出来的行往往是它自己的数据，默认不生成 DELETE">
              为「源端没有」的行生成 DELETE
            </Tooltip>
          </Checkbox>
          <Button
            type="primary"
            size="small"
            icon={<DiffOutlined />}
            loading={loading}
            disabled={!canRunDataDiff(form)}
            onClick={() => void run()}
          >
            开始对比
          </Button>
        </Space>
      </Form>

      {error && <Alert type="error" showIcon message="对比失败" description={error} />}

      {result && (
        <>
          <Alert
            type={result.summary.onlyInSource + result.summary.onlyInTarget + result.summary.different === 0
              ? 'success' : 'info'}
            showIcon
            message={summarizeDataDiff(result)}
            description={
              <Space direction="vertical" size={2}>
                <Text type="secondary">
                  {describeTable(result, 'source')} → {describeTable(result, 'target')}
                  {' · '}匹配字段 {result.keyColumns.join(', ')}
                </Text>
                {result.warnings.map((warning) => <Text key={warning} type="warning">{warning}</Text>)}
              </Space>
            }
          />

          {result.rows.length === 0 ? (
            <PanelEmpty title="两侧数据一致" description="没有需要同步的行。" />
          ) : (
            <Table<DataDiffRow>
              size="small"
              rowKey={(row) => `${row.change}:${row.key.join('/')}`}
              dataSource={result.rows}
              pagination={{ pageSize: 20, size: 'small' }}
              scroll={{ x: true }}
              columns={[
                {
                  title: '差异', dataIndex: 'change', width: 110,
                  render: (change: DataDiffChange) => (
                    <Tag color={CHANGE_COLORS[change]}>{DATA_DIFF_CHANGE_LABELS[change]}</Tag>
                  )
                },
                {
                  title: result.keyColumns.join(' / '), dataIndex: 'key', width: 180,
                  render: (key: string[]) => <Text code>{key.join(' / ')}</Text>
                },
                {
                  title: '不一致的字段', dataIndex: 'columns',
                  render: (columns: string[], row) => (columns.length === 0
                    ? <Text type="secondary">整行</Text>
                    : (
                      <Space direction="vertical" size={2}>
                        {columns.map((column) => {
                          const pair = cellPair(result, row, column);
                          return (
                            <Text key={column}>
                              <Text strong>{column}</Text>：
                              <Text type="secondary">{display(pair.target)}</Text>
                              {' → '}
                              <Text>{display(pair.source)}</Text>
                            </Text>
                          );
                        })}
                      </Space>
                    ))
                }
              ]}
            />
          )}

          {script && (
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              <Space size={6}>
                <Button
                  size="small"
                  type="primary"
                  icon={<ExportOutlined />}
                  onClick={() => onOpenInSqlTab(script, `数据同步 ${result.targetTable}`)}
                >
                  写入 SQL 工作台
                </Button>
                <Button
                  size="small"
                  icon={<CopyOutlined />}
                  onClick={() => { void navigator.clipboard?.writeText(script).catch(() => undefined); }}
                >
                  复制脚本
                </Button>
                <Text type="secondary">共 {result.script.length} 条语句 · 不会自动执行</Text>
              </Space>
              <pre className="schema-diff-sql">{script}</pre>
            </Space>
          )}
        </>
      )}
    </Space>
  );
});

/** NULL 和空串在这一屏必须看得出区别 —— 它们的同步语句写法完全不同。 */
function display(value?: string | null): string {
  if (value === null || value === undefined) return 'NULL';
  return value === '' ? '(空串)' : value;
}
