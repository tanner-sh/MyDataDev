import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Popconfirm, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import { ApiOutlined, DeleteOutlined, SaveOutlined } from '@ant-design/icons';
import { api } from '../api';
import { PanelLoading } from './PanelState';
import type { AiConnectionPolicy, AiProbeResult, AiProvider, AiSchemaSharing, AiSettings } from '../types';
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
            }
          ]}
        />
      </Card>
    </Space>
  );
}
