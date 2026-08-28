import { memo, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Empty, Form, Input, Select, Space, Spin, Table, Tag, Tooltip, Typography } from 'antd';
import { CopyOutlined, DiffOutlined, ExportOutlined } from '@ant-design/icons';
import { api } from '../api';
import { localizeError } from '../utils';
import {
  buildMigrationScript,
  buildSchemaDiffRequest,
  canRunSchemaDiff,
  describeEndpoint,
  EMPTY_SCHEMA_DIFF_FORM,
  SCHEMA_DIFF_CATEGORY_LABELS,
  SCHEMA_DIFF_CHANGE_LABELS,
  SCHEMA_DIFF_STATUS_LABELS,
  summarizeSchemaDiff,
  type SchemaDiffForm
} from '../schemaDiff';
import type { Connection, SchemaDiffItem, SchemaDiffResponse, SchemaDiffStatus, SchemaDiffTable } from '../types';

const { Text, Paragraph } = Typography;

const STATUS_COLORS: Record<SchemaDiffStatus, string> = {
  ONLY_IN_SOURCE: 'green',
  ONLY_IN_TARGET: 'orange',
  DIFFERENT: 'blue',
  IDENTICAL: 'default'
};

/**
 * 两个 Schema 的结构对比。
 *
 * 面板只做「看差异」和「拿脚本」两件事：生成的迁移语句不在这里执行，而是送进 SQL 工作台，
 * 让它照常经过生产确认、无 WHERE 写操作确认和审计。
 */
export const SchemaDiffPanel = memo(function SchemaDiffPanel({ connections, defaultConnectionId, onOpenInSqlTab }: {
  connections: Connection[];
  defaultConnectionId?: number;
  onOpenInSqlTab: (sql: string, title: string) => void;
}) {
  const [form, setForm] = useState<SchemaDiffForm>({ ...EMPTY_SCHEMA_DIFF_FORM, sourceConnectionId: defaultConnectionId });
  const [result, setResult] = useState<SchemaDiffResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [showIdentical, setShowIdentical] = useState(false);

  const connectionOptions = useMemo(
    () => connections.map((connection) => ({ value: connection.id, label: `${connection.name}（${connection.dbType}）` })),
    [connections]
  );
  const script = result ? buildMigrationScript(result) : '';
  const visibleTables = useMemo(
    () => (result ? result.tables.filter((table) => showIdentical || table.status !== 'IDENTICAL') : []),
    [result, showIdentical]
  );

  async function run() {
    setLoading(true);
    setError('');
    try {
      const response = await api<SchemaDiffResponse>('/schema-diff', {
        method: 'POST',
        body: JSON.stringify(buildSchemaDiffRequest(form))
      });
      setResult(response);
    } catch (e) {
      setResult(null);
      setError(localizeError(e));
    } finally {
      setLoading(false);
    }
  }

  async function copyScript() {
    if (!script) return;
    await navigator.clipboard.writeText(script);
  }

  return (
    <section className="schema-diff-panel">
      <Form layout="vertical" size="small" className="compact-form" disabled={loading}>
        <Space size={8} wrap className="schema-diff-endpoints">
          <Form.Item label="源（作为基准）" required>
            <Select
              className="schema-diff-connection"
              placeholder="选择连接"
              value={form.sourceConnectionId}
              options={connectionOptions}
              onChange={(value) => setForm((current) => ({ ...current, sourceConnectionId: value }))}
            />
          </Form.Item>
          <Form.Item label="源 Schema / 数据库">
            <Input
              value={form.sourceSchema}
              placeholder="留空则用默认库"
              onChange={(event) => setForm((current) => ({ ...current, sourceSchema: event.target.value }))}
            />
          </Form.Item>
          <Form.Item label="目标（将被对齐）" required>
            <Select
              className="schema-diff-connection"
              placeholder="选择连接"
              value={form.targetConnectionId}
              options={connectionOptions}
              onChange={(value) => setForm((current) => ({ ...current, targetConnectionId: value }))}
            />
          </Form.Item>
          <Form.Item label="目标 Schema / 数据库">
            <Input
              value={form.targetSchema}
              placeholder="留空则用默认库"
              onChange={(event) => setForm((current) => ({ ...current, targetSchema: event.target.value }))}
            />
          </Form.Item>
        </Space>
        <Form.Item label="只对比这些表" help="逗号或换行分隔，留空表示全部表；表很多时建议先缩小范围。">
          <Input.TextArea
            value={form.tables}
            rows={2}
            placeholder="orders, order_item"
            onChange={(event) => setForm((current) => ({ ...current, tables: event.target.value }))}
          />
        </Form.Item>
        <Space size={12} wrap>
          <Checkbox
            checked={form.includeDrops}
            onChange={(event) => setForm((current) => ({ ...current, includeDrops: event.target.checked }))}
          >
            <Tooltip title="勾选后，目标端多出来的表、字段和索引会生成删除语句。默认不生成，因为它们往往是有意保留的。">
              生成删除语句
            </Tooltip>
          </Checkbox>
          <Button
            type="primary"
            icon={<DiffOutlined />}
            loading={loading}
            disabled={!canRunSchemaDiff(form)}
            onClick={run}
          >
            开始对比
          </Button>
        </Space>
      </Form>

      {error && <Alert type="error" showIcon className="schema-diff-alert" message={error} />}
      {loading && <div className="workspace-lazy-loading"><Spin /> 正在读取两侧结构…</div>}

      {result && !loading && (
        <>
          <Alert
            type={result.summary.onlyInSource + result.summary.onlyInTarget + result.summary.different === 0 ? 'success' : 'info'}
            showIcon
            className="schema-diff-alert"
            message={summarizeSchemaDiff(result)}
            description={
              <>
                <Text type="secondary">
                  {describeEndpoint(result.source)} → {describeEndpoint(result.target)}
                </Text>
                {result.warnings.map((warning) => (
                  <Paragraph key={warning} type="warning" className="schema-diff-warning">{warning}</Paragraph>
                ))}
              </>
            }
          />
          <Space size={8} wrap className="schema-diff-actions">
            <Checkbox checked={showIdentical} onChange={(event) => setShowIdentical(event.target.checked)}>
              显示一致的表
            </Checkbox>
            <Button icon={<ExportOutlined />} disabled={!script} onClick={() => onOpenInSqlTab(script, '结构同步')}>
              在 SQL 工作台打开
            </Button>
            <Button icon={<CopyOutlined />} disabled={!script} onClick={copyScript}>复制脚本</Button>
          </Space>
          {visibleTables.length === 0 ? (
            <Empty description={showIdentical ? '没有可对比的表' : '没有差异'} />
          ) : (
            <Table<SchemaDiffTable>
              size="small"
              rowKey="tableName"
              pagination={false}
              dataSource={visibleTables}
              expandable={{
                rowExpandable: (table) => table.items.length > 0 || table.migration.length > 0,
                expandedRowRender: (table) => <TableDiffDetail table={table} />
              }}
              columns={[
                { title: '表', dataIndex: 'tableName', key: 'tableName' },
                {
                  title: '状态',
                  dataIndex: 'status',
                  key: 'status',
                  width: 120,
                  render: (status: SchemaDiffStatus) => (
                    <Tag color={STATUS_COLORS[status]}>{SCHEMA_DIFF_STATUS_LABELS[status]}</Tag>
                  )
                },
                {
                  title: '差异项',
                  key: 'items',
                  width: 100,
                  render: (_value, table) => (table.items.length === 0 ? '—' : table.items.length)
                },
                {
                  title: '迁移语句',
                  key: 'migration',
                  width: 100,
                  render: (_value, table) => (table.migration.length === 0 ? '—' : table.migration.length)
                }
              ]}
            />
          )}
        </>
      )}
    </section>
  );
});

function TableDiffDetail({ table }: { table: SchemaDiffTable }) {
  return (
    <div className="schema-diff-detail">
      {table.items.length > 0 && (
        <Table<SchemaDiffItem>
          size="small"
          rowKey={(item) => `${item.category}:${item.name}`}
          pagination={false}
          dataSource={table.items}
          columns={[
            {
              title: '类型',
              dataIndex: 'category',
              key: 'category',
              width: 80,
              render: (category: string) => SCHEMA_DIFF_CATEGORY_LABELS[category] || category
            },
            { title: '名称', dataIndex: 'name', key: 'name' },
            {
              title: '差异',
              dataIndex: 'change',
              key: 'change',
              width: 80,
              render: (change: SchemaDiffItem['change']) => SCHEMA_DIFF_CHANGE_LABELS[change]
            },
            { title: '源端', dataIndex: 'source', key: 'source', render: (value?: string) => value || '—' },
            { title: '目标端', dataIndex: 'target', key: 'target', render: (value?: string) => value || '—' }
          ]}
        />
      )}
      {table.migration.length > 0 && (
        <pre className="schema-diff-sql">{table.migration.join(';\n')};</pre>
      )}
    </div>
  );
}
