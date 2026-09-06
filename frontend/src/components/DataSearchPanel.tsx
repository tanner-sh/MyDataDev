import { memo, useMemo, useState } from 'react';
import { Alert, Button, Collapse, Form, Input, InputNumber, List, Radio, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { ExportOutlined, SearchOutlined } from '@ant-design/icons';
import { PanelEmpty } from './PanelState';
import { api } from '../api';
import { localizeError } from '../utils';
import {
  buildDataSearchRequest,
  canRunDataSearch,
  DATA_SEARCH_MODE_OPTIONS,
  describeHitRows,
  EMPTY_DATA_SEARCH_FORM,
  incompleteNotice,
  sqlTabTitle,
  summarizeDataSearch,
  tableHitLabel,
  type DataSearchForm
} from '../dataSearch';
import type { Connection, DataSearchResponse, DataSearchTableHit } from '../types';

const { Text } = Typography;

/**
 * 全库数据检索。
 *
 * <p>「这个手机号在哪张表里」—— 资源树只搜得到对象名，这里搜的是值本身。</p>
 *
 * <p>界面上有一条不能省的规矩：扫描没跑完时那句提示要比结果本身更显眼。一次触了上限的扫描
 * 很容易被当成「全库就这些」，而那个错觉比没有这个功能更糟。</p>
 */
export const DataSearchPanel = memo(function DataSearchPanel({
  connections,
  defaultConnectionId,
  defaultSchema,
  onRequestConfirmation,
  onOpenInSqlTab
}: {
  connections: Connection[];
  defaultConnectionId?: number;
  defaultSchema?: string;
  onRequestConfirmation: (action: string, expected?: string) => Promise<string | undefined>;
  onOpenInSqlTab: (sql: string, title: string) => void;
}) {
  const [form, setForm] = useState<DataSearchForm>({
    ...EMPTY_DATA_SEARCH_FORM,
    connectionId: defaultConnectionId,
    schemaName: defaultSchema || ''
  });
  const [result, setResult] = useState<DataSearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const connectionOptions = useMemo(
    () => connections.map((connection) => ({
      value: connection.id,
      label: `${connection.name}（${connection.dbType}${connection.environment === 'prod' ? ' · 生产' : ''}）`
    })),
    [connections]
  );
  const modeHint = DATA_SEARCH_MODE_OPTIONS.find((option) => option.value === form.mode)?.hint;

  async function run() {
    const source = connections.find((connection) => connection.id === form.connectionId);
    // 几百张表各跑一条整表扫描，和「打开一张表看看」不是一回事，生产连接上要明确点头。
    const confirmation = await onRequestConfirmation(
      '在生产连接上做全库检索', source?.environment === 'prod' ? source.name : undefined);
    if (source?.environment === 'prod' && !confirmation) return;
    setLoading(true);
    setError('');
    try {
      setResult(await api<DataSearchResponse>('/data/search', {
        method: 'POST',
        body: JSON.stringify(buildDataSearchRequest(form)),
        headers: confirmation ? { 'X-Production-Confirmation': encodeURIComponent(confirmation) } : undefined
      }));
    } catch (e) {
      setResult(null);
      setError(localizeError(e));
    } finally {
      setLoading(false);
    }
  }

  const notice = result ? incompleteNotice(result) : '';

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Form layout="vertical" size="small" className="schema-diff-endpoints">
        <Space size={12} wrap align="start">
          <Form.Item label="连接">
            <Select
              style={{ width: 240 }}
              value={form.connectionId}
              options={connectionOptions}
              placeholder="选择连接"
              onChange={(value) => setForm({ ...form, connectionId: value })}
            />
          </Form.Item>
          <Form.Item label="Schema" tooltip="留空则用连接当前所在的 Schema/数据库。范围越小扫得越完整。">
            <Input
              style={{ width: 160 }}
              value={form.schemaName}
              placeholder="默认"
              onChange={(event) => setForm({ ...form, schemaName: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="要找的内容" required tooltip={modeHint}>
            <Input
              style={{ width: 240 }}
              value={form.keyword}
              placeholder="13800001111"
              onPressEnter={() => { if (canRunDataSearch(form) && !loading) void run(); }}
              onChange={(event) => setForm({ ...form, keyword: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="匹配方式">
            <Radio.Group
              size="small"
              optionType="button"
              value={form.mode}
              options={DATA_SEARCH_MODE_OPTIONS.map((option) => ({ value: option.value, label: option.label }))}
              onChange={(event) => setForm({ ...form, mode: event.target.value })}
            />
          </Form.Item>
        </Space>

        <Collapse
          ghost
          size="small"
          items={[{
            key: 'limits',
            label: <Text type="secondary">扫描上限</Text>,
            children: (
              <Space size={12} wrap align="start">
                <Form.Item label="最多扫描表数">
                  <InputNumber
                    min={1}
                    max={2000}
                    value={form.maxTables}
                    onChange={(value) => setForm({ ...form, maxTables: value ?? EMPTY_DATA_SEARCH_FORM.maxTables })}
                  />
                </Form.Item>
                <Form.Item label="每表取样行数" tooltip="只影响能看到多少命中样例，不影响一张表算不算命中">
                  <InputNumber
                    min={1}
                    max={50}
                    value={form.rowsPerTable}
                    onChange={(value) => setForm({ ...form, rowsPerTable: value ?? EMPTY_DATA_SEARCH_FORM.rowsPerTable })}
                  />
                </Form.Item>
                <Form.Item label="时间上限（秒）">
                  <InputNumber
                    min={1}
                    max={120}
                    value={form.budgetSeconds}
                    onChange={(value) => setForm({ ...form, budgetSeconds: value ?? EMPTY_DATA_SEARCH_FORM.budgetSeconds })}
                  />
                </Form.Item>
              </Space>
            )
          }]}
        />

        <Button
          type="primary"
          size="small"
          icon={<SearchOutlined />}
          loading={loading}
          disabled={!canRunDataSearch(form)}
          onClick={() => void run()}
        >
          开始检索
        </Button>
      </Form>

      {error && <Alert type="error" showIcon message="检索失败" description={error} />}

      {result && (
        <>
          {notice && <Alert type="warning" showIcon message="这次扫描没有跑完" description={notice} />}
          <Alert
            type={result.matchedTables === 0 ? 'info' : 'success'}
            showIcon
            message={summarizeDataSearch(result)}
            description={result.warnings.length > 0 && (
              <Space direction="vertical" size={2}>
                {result.warnings.map((warning) => <Text key={warning} type="secondary">{warning}</Text>)}
              </Space>
            )}
          />

          {result.tables.length === 0 ? (
            <PanelEmpty title="没有命中" description="换一种匹配方式，或确认要找的内容是否在这个 Schema 下。" />
          ) : (
            <List<DataSearchTableHit>
              size="small"
              bordered
              dataSource={result.tables}
              renderItem={(hit) => (
                <List.Item
                  key={`${hit.schemaName || ''}.${hit.tableName}`}
                  actions={[
                    <Tooltip key="sql" title="在 SQL 工作台打开这次命中对应的查询">
                      <Button
                        size="small"
                        icon={<ExportOutlined />}
                        onClick={() => onOpenInSqlTab(hit.sql, sqlTabTitle(hit))}
                      >
                        查看命中行
                      </Button>
                    </Tooltip>
                  ]}
                >
                  <List.Item.Meta
                    title={
                      <Space size={8} wrap>
                        <Text strong>{tableHitLabel(hit)}</Text>
                        <Text type="secondary">{describeHitRows(hit)}</Text>
                      </Space>
                    }
                    description={
                      <Space size={8} wrap>
                        {hit.columns.map((column) => (
                          <Tooltip key={column.column} title={column.sample}>
                            <Tag color="blue">{column.column} · {column.rows}</Tag>
                          </Tooltip>
                        ))}
                      </Space>
                    }
                  />
                </List.Item>
              )}
            />
          )}
        </>
      )}
    </Space>
  );
});
