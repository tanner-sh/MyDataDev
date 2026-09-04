import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import { ApiOutlined, BookOutlined, DeleteOutlined, PlusOutlined, SaveOutlined } from '@ant-design/icons';
import { api } from '../api';
import { PanelLoading } from './PanelState';
import type {
  AiConnectionPolicy,
  AiGlossaryEntry,
  AiGlossarySuggestion,
  AiGlossarySuggestions,
  AiProbeResult,
  AiProvider,
  AiSchemaSharing,
  AiSettings
} from '../types';
import { alreadyInGlossary, mergeSuggestions } from '../aiGlossary';
import {
  AI_EFFORTS,
  AI_MAX_SAMPLE_ROWS,
  AI_PROVIDERS,
  AI_SHARING_HINTS,
  AI_SHARING_LABELS,
  applyProviderChange,
  clearApiKeyPayload,
  sharingOptionsFor,
  summarizePolicies,
  toPolicyPayload,
  toSettingsForm,
  toSettingsPayload,
  validateSettingsForm,
  type AiSettingsForm
} from '../aiSettings';

const { Paragraph, Text } = Typography;

export function AiSettingsPanel() {
  const [settings, setSettings] = useState<AiSettings>();
  const [form, setForm] = useState<AiSettingsForm>(toSettingsForm());
  const [policies, setPolicies] = useState<AiConnectionPolicy[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [probe, setProbe] = useState<AiProbeResult>();
  const [glossaryPolicy, setGlossaryPolicy] = useState<AiConnectionPolicy>();
  const [glossary, setGlossary] = useState<AiGlossaryEntry[]>([]);
  const [glossaryLoading, setGlossaryLoading] = useState(false);
  const [glossarySaving, setGlossarySaving] = useState(false);
  const [suggestions, setSuggestions] = useState<AiGlossarySuggestions>();
  const [suggestionsLoading, setSuggestionsLoading] = useState(false);
  const [pickedTerms, setPickedTerms] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [messageApi, messageContext] = message.useMessage();

  const meta = AI_PROVIDERS[form.provider];
  const summary = useMemo(() => summarizePolicies(policies), [policies]);

  async function load() {
    setLoading(true);
    setError('');
    try {
      const [nextSettings, nextPolicies] = await Promise.all([
        api<AiSettings>('/ai/settings'),
        api<AiConnectionPolicy[]>('/ai/connections')
      ]);
      setSettings(nextSettings);
      setForm(toSettingsForm(nextSettings));
      setPolicies(nextPolicies);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'AI 设置加载失败');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  async function save(payload: ReturnType<typeof toSettingsPayload>) {
    setSaving(true);
    try {
      const next = await api<AiSettings>('/ai/settings', { method: 'PUT', body: JSON.stringify(payload) });
      setSettings(next);
      setForm(toSettingsForm(next));
      setProbe(undefined);
      messageApi.success('AI 设置已保存');
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : 'AI 设置保存失败');
    } finally {
      setSaving(false);
    }
  }

  async function submit() {
    const invalid = validateSettingsForm(form, settings?.apiKeyConfigured ?? false);
    if (invalid) {
      messageApi.warning(invalid);
      return;
    }
    await save(toSettingsPayload(form));
  }

  async function test() {
    setTesting(true);
    setProbe(undefined);
    try {
      setProbe(await api<AiProbeResult>('/ai/settings/test', { method: 'POST' }));
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '连通性测试失败');
    } finally {
      setTesting(false);
    }
  }

  async function updatePolicy(policy: AiConnectionPolicy, sharing: AiSchemaSharing, sampleRowLimit: number) {
    try {
      const next = await api<AiConnectionPolicy>(`/ai/connections/${policy.connectionId}/policy`, {
        method: 'PUT',
        body: JSON.stringify(toPolicyPayload(sharing, sampleRowLimit))
      });
      setPolicies((current) => current.map((item) => (item.connectionId === next.connectionId ? next : item)));
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '共享策略保存失败');
    }
  }

  /**
   * 拉候选词条。
   *
   * 不自动落库：候选是从表注释推出来的，业务词该叫什么、有哪些别名，最终得由人定。自动写进去
   * 会让词典里混进一批没人认领的词条，比空着更难维护。
   */
  async function loadSuggestions(connectionId: number) {
    setSuggestionsLoading(true);
    try {
      setSuggestions(await api<AiGlossarySuggestions>(`/ai/connections/${connectionId}/glossary/suggestions`));
      setPickedTerms([]);
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '候选词条生成失败');
    } finally {
      setSuggestionsLoading(false);
    }
  }

  function applySuggestions() {
    if (!suggestions) return;
    const picked = suggestions.suggestions.filter((item) => pickedTerms.includes(item.term));
    const merged = mergeSuggestions(glossary, picked);
    const added = merged.length - glossary.length;
    setGlossary(merged);
    setPickedTerms([]);
    messageApi.success(added > 0 ? `已加入 ${added} 条，记得补上用户嘴里的别名再保存` : '选中的业务词都已存在');
  }

  async function openGlossary(policy: AiConnectionPolicy) {
    setSuggestions(undefined);
    setPickedTerms([]);
    setGlossaryPolicy(policy);
    setGlossaryLoading(true);
    try {
      setGlossary(await api<AiGlossaryEntry[]>(`/ai/connections/${policy.connectionId}/glossary`));
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '业务词典加载失败');
      setGlossaryPolicy(undefined);
    } finally {
      setGlossaryLoading(false);
    }
  }

  async function saveGlossary() {
    if (!glossaryPolicy) return;
    if (glossary.some((entry) => !entry.term.trim())) {
      messageApi.warning('业务词不能为空');
      return;
    }
    setGlossarySaving(true);
    try {
      const entries = glossary.map(({ term, aliases, objectNames, description }) => ({
        term: term.trim(), aliases, objectNames, description: description?.trim() || null
      }));
      setGlossary(await api<AiGlossaryEntry[]>(`/ai/connections/${glossaryPolicy.connectionId}/glossary`, {
        method: 'PUT', body: JSON.stringify({ entries })
      }));
      messageApi.success('业务词典已保存');
      setGlossaryPolicy(undefined);
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '业务词典保存失败');
    } finally {
      setGlossarySaving(false);
    }
  }

  function updateGlossary(id: number, patch: Partial<AiGlossaryEntry>) {
    setGlossary((current) => current.map((entry) => (entry.id === id ? { ...entry, ...patch } : entry)));
  }

  if (loading) return <PanelLoading text="正在加载 AI 设置…" />;

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {messageContext}
      {error && <Alert type="error" showIcon message={error} />}

      <Card size="small" title="模型服务">
        <Form layout="vertical" size="small">
          <Form.Item label="启用 AI 助手" extra="关闭后所有 AI 入口在界面上隐藏，接口一律拒绝。">
            <Switch checked={form.enabled} onChange={(enabled) => setForm({ ...form, enabled })} />
          </Form.Item>
          <Form.Item label="服务商" extra={meta.hint}>
            <Select<AiProvider>
              value={form.provider}
              onChange={(provider) => setForm(applyProviderChange(form, provider))}
              options={(Object.keys(AI_PROVIDERS) as AiProvider[]).map((value) => ({ value, label: AI_PROVIDERS[value].label }))}
            />
          </Form.Item>
          {meta.requiresBaseUrl && (
            <Form.Item label="接口地址">
              <Input
                value={form.baseUrl}
                placeholder="http://127.0.0.1:11434/v1"
                onChange={(event) => setForm({ ...form, baseUrl: event.target.value })}
              />
            </Form.Item>
          )}
          <Form.Item label="模型">
            <Input
              value={form.model}
              placeholder={meta.defaultModel || '例如 qwen2.5'}
              onChange={(event) => setForm({ ...form, model: event.target.value })}
            />
          </Form.Item>
          <Form.Item
            label="API Key"
            extra={settings?.apiKeyConfigured
              ? '已保存。留空表示沿用，填入新值即覆盖 —— 已保存的 Key 任何人都读不回来。'
              : meta.requiresApiKey ? '启用前必填。' : '本地模型通常不需要。'}
          >
            <Space.Compact style={{ width: '100%' }}>
              <Input.Password
                value={form.apiKey}
                placeholder={settings?.apiKeyConfigured ? '已保存（留空即沿用）' : '粘贴 API Key'}
                onChange={(event) => setForm({ ...form, apiKey: event.target.value })}
              />
              {settings?.apiKeyConfigured && (
                <Popconfirm
                  title="清除已保存的 API Key？"
                  description="清除后 AI 功能会一并关闭。"
                  okText="清除"
                  cancelText="取消"
                  onConfirm={() => save(clearApiKeyPayload(form))}
                >
                  <Button icon={<DeleteOutlined />} danger>清除</Button>
                </Popconfirm>
              )}
            </Space.Compact>
          </Form.Item>
          <Form.Item label="思考深度" extra="仅 Claude API 生效；兼容协议没有等价参数，会被忽略。">
            <Select
              value={form.effort}
              onChange={(effort) => setForm({ ...form, effort })}
              options={AI_EFFORTS.map((item) => ({ value: item.value, label: item.label }))}
              disabled={form.provider !== 'ANTHROPIC'}
            />
          </Form.Item>
          <Space>
            <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={submit}>保存</Button>
            <Button icon={<ApiOutlined />} loading={testing} onClick={test} disabled={saving}>连通性测试</Button>
          </Space>
        </Form>
        {probe && (
          <Alert
            style={{ marginTop: 12 }}
            type={probe.ok ? 'success' : 'error'}
            showIcon
            message={probe.ok ? `连接正常（${probe.model}，${probe.latencyMs} ms）` : '连接失败'}
            description={probe.ok ? undefined : probe.message}
          />
        )}
      </Card>

      <Card
        size="small"
        title="连接共享策略"
        extra={<Text type="secondary">{summary.shared} / {summary.total} 条连接已授权{summary.sampled > 0 ? `，其中 ${summary.sampled} 条带样本` : ''}</Text>}
      >
        <Paragraph type="secondary" style={{ marginTop: 0 }}>
          新连接默认「不参与 AI」。授权后模型只能拿到你在这里勾选的范围；生产连接不允许发送样本数据。
        </Paragraph>
        <Table<AiConnectionPolicy>
          size="small"
          rowKey="connectionId"
          dataSource={policies}
          pagination={false}
          columns={[
            {
              title: '连接',
              dataIndex: 'connectionName',
              render: (name: string, policy) => (
                <Space size={4}>
                  <Text>{name}</Text>
                  <Tag>{policy.dbType}</Tag>
                  {policy.production && <Tag color="red">生产</Tag>}
                </Space>
              )
            },
            {
              title: '共享范围',
              dataIndex: 'sharing',
              width: 200,
              render: (sharing: AiSchemaSharing, policy) => (
                <Select<AiSchemaSharing>
                  size="small"
                  style={{ width: '100%' }}
                  value={sharing}
                  onChange={(value) => updatePolicy(policy, value, policy.sampleRowLimit)}
                  options={sharingOptionsFor(policy.production).map((option) => ({
                    value: option.value,
                    label: AI_SHARING_LABELS[option.value],
                    disabled: option.disabled
                  }))}
                />
              )
            },
            {
              title: '样本行数',
              dataIndex: 'sampleRowLimit',
              width: 120,
              render: (rows: number, policy) => (
                <InputNumber
                  size="small"
                  min={1}
                  max={AI_MAX_SAMPLE_ROWS}
                  value={rows || undefined}
                  disabled={policy.sharing !== 'STRUCTURE_AND_SAMPLE'}
                  onChange={(value) => updatePolicy(policy, policy.sharing, Number(value) || 0)}
                />
              )
            },
            {
              title: '说明',
              dataIndex: 'sharing',
              key: 'hint',
              render: (sharing: AiSchemaSharing) => <Text type="secondary">{AI_SHARING_HINTS[sharing]}</Text>
            },
            {
              title: '业务词典',
              key: 'glossary',
              width: 110,
              render: (_, policy) => (
                <Button size="small" icon={<BookOutlined />} onClick={() => void openGlossary(policy)}>维护</Button>
              )
            }
          ]}
        />
      </Card>

      <Modal
        open={Boolean(glossaryPolicy)}
        width={920}
        title={`${glossaryPolicy?.connectionName || ''} · 业务词典`}
        okText="保存"
        cancelText="取消"
        confirmLoading={glossarySaving}
        onOk={() => void saveGlossary()}
        onCancel={() => setGlossaryPolicy(undefined)}
      >
        <Paragraph type="secondary">
          把“客户、活跃用户”等业务说法映射到真实表或视图。AI 搜索结构时会优先使用这些映射；多个别名或对象用逗号分隔。
        </Paragraph>
        <GlossarySuggestions
          loading={suggestionsLoading}
          data={suggestions}
          glossary={glossary}
          picked={pickedTerms}
          onPick={setPickedTerms}
          onLoad={() => glossaryPolicy && void loadSuggestions(glossaryPolicy.connectionId)}
          onApply={applySuggestions}
        />
        <Table<AiGlossaryEntry>
          size="small"
          rowKey="id"
          loading={glossaryLoading}
          dataSource={glossary}
          pagination={false}
          scroll={{ y: 360 }}
          columns={[
            {
              title: '业务词', dataIndex: 'term', width: 150,
              render: (value: string, entry) => <Input value={value} maxLength={120} onChange={(event) => updateGlossary(entry.id, { term: event.target.value })} />
            },
            {
              title: '别名', dataIndex: 'aliases', width: 190,
              render: (value: string[], entry) => <Input value={value.join(', ')} placeholder="会员, 客户" onChange={(event) => updateGlossary(entry.id, { aliases: splitList(event.target.value) })} />
            },
            {
              title: '数据库对象', dataIndex: 'objectNames', width: 220,
              render: (value: string[], entry) => <Input value={value.join(', ')} placeholder="users, user_profile" onChange={(event) => updateGlossary(entry.id, { objectNames: splitList(event.target.value) })} />
            },
            {
              title: '说明', dataIndex: 'description',
              render: (value: string | null, entry) => <Input value={value || ''} maxLength={1000} placeholder="可选业务口径" onChange={(event) => updateGlossary(entry.id, { description: event.target.value })} />
            },
            {
              title: '', key: 'action', width: 42,
              render: (_, entry) => <Button type="text" danger icon={<DeleteOutlined />} onClick={() => setGlossary((current) => current.filter((item) => item.id !== entry.id))} />
            }
          ]}
        />
        <Button
          style={{ marginTop: 12 }}
          icon={<PlusOutlined />}
          onClick={() => setGlossary((current) => [...current, {
            id: -Date.now(), term: '', aliases: [], objectNames: [], description: ''
          }])}
        >
          添加业务词
        </Button>
      </Modal>
    </Space>
  );
}

function splitList(value: string): string[] {
  return value.split(/[,，]/).map((item) => item.trim()).filter(Boolean).slice(0, 10);
}

/**
 * 候选词条的挑选区。
 *
 * <p>刻意做成「挑选」而不是「一键导入」：注释里的词本来就能被 search_schema 搜到，自动生成的
 * 词条本身不算新信息 —— 词典真正不可替代的是用户嘴里的「会员」「买家」，那些不会出现在任何
 * 注释里。所以这里的目标是把「对着空表格填一百条」变成「勾几条再补别名」，而不是替人做决定。</p>
 */
function GlossarySuggestions({ loading, data, glossary, picked, onPick, onLoad, onApply }: {
  loading: boolean;
  data?: AiGlossarySuggestions;
  glossary: AiGlossaryEntry[];
  picked: string[];
  onPick: (terms: string[]) => void;
  onLoad: () => void;
  onApply: () => void;
}) {
  const existing = data ? alreadyInGlossary(glossary, data.suggestions) : new Set<string>();
  const selectable = (data?.suggestions || []).filter((item) => !existing.has(item.term));

  if (!data) {
    return (
      <Button size="small" loading={loading} onClick={onLoad} style={{ marginBottom: 12 }}>
        从表注释生成候选
      </Button>
    );
  }

  return (
    <Card
      size="small"
      style={{ marginBottom: 12 }}
      title={`候选词条 · ${selectable.length} 条可加入`}
      extra={(
        <Space size={4}>
          <Button size="small" onClick={onLoad} loading={loading}>重新生成</Button>
          <Button size="small" type="primary" disabled={picked.length === 0} onClick={onApply}>
            加入 {picked.length || ''}
          </Button>
        </Space>
      )}
    >
      {selectable.length === 0 ? (
        <Text type="secondary">没有可加入的候选：有注释的表都已经在词典里了。</Text>
      ) : (
        <Table<AiGlossarySuggestion>
          size="small"
          rowKey="term"
          loading={loading}
          dataSource={selectable}
          pagination={false}
          scroll={{ y: 180 }}
          rowSelection={{ selectedRowKeys: picked, onChange: (keys) => onPick(keys as string[]) }}
          columns={[
            { title: '业务词', dataIndex: 'term', width: 140 },
            {
              title: '数据库对象', dataIndex: 'objectNames', width: 240,
              render: (value: string[]) => <Text code>{value.join(', ')}</Text>
            },
            {
              title: '历史查询次数', dataIndex: 'usageCount', width: 110,
              render: (value: number) => (value > 0
                ? <Text>{value}</Text>
                : <Text type="secondary">没人查过</Text>)
            },
            { title: '来自注释', dataIndex: 'description', ellipsis: true }
          ]}
        />
      )}
      {data.uncommentedObjects.length > 0 && (
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
          另有 {data.uncommentedObjects.length} 个对象连注释都没有，推不出候选词，而它们恰恰是 AI 最找不到的：
          {' '}<Text code>{data.uncommentedObjects.slice(0, 8).join(', ')}</Text>
          {data.uncommentedObjects.length > 8 ? ' …' : ''}
          。给它们补表注释，或者在下面手工加词条。
        </Paragraph>
      )}
    </Card>
  );
}
