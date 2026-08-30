import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message
} from 'antd';
import {
  CopyOutlined,
  DeleteOutlined,
  KeyOutlined,
  PlusOutlined,
  ReadOutlined,
  ReloadOutlined,
  SaveOutlined,
  SettingOutlined
} from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { API } from '../constants';
import type { McpAccessLevel, McpAgent, McpConfig, McpCredential, McpLimits } from '../types';
import {
  agentGrantWarnings,
  allowedLevelsFor,
  buildAgentGrants,
  grantsToFormValues,
  mcpAccessLevelColor,
  MCP_ACCESS_LEVEL_HINTS,
  MCP_ACCESS_LEVEL_LABELS
} from '../mcpAccessLevels';
import { McpClientGuideTabs } from './McpClientGuideTabs';
import { McpHelpPanel } from './McpHelpPanel';

const { Paragraph, Text, Title } = Typography;

type ConfigForm = McpLimits & { allowedOrigins: string[] };
type AgentForm = {
  agentId: string;
  connectionIds: number[];
  levels: Record<number, McpAccessLevel>;
  allowProduction: boolean;
  enabled: boolean;
};

export function McpSettingsPanel() {
  const [config, setConfig] = useState<McpConfig>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [statusSaving, setStatusSaving] = useState(false);
  const [agentSaving, setAgentSaving] = useState(false);
  const [editingAgent, setEditingAgent] = useState<McpAgent>();
  const [agentModalOpen, setAgentModalOpen] = useState(false);
  const [credential, setCredential] = useState<McpCredential>();
  const [activeSection, setActiveSection] = useState<'settings' | 'help'>('settings');
  const [configForm] = Form.useForm<ConfigForm>();
  const [agentForm] = Form.useForm<AgentForm>();
  const [modal, modalContext] = Modal.useModal();
  const [messageApi, messageContext] = message.useMessage();

  const endpoint = useMemo(() => resolveMcpEndpoint(config?.endpointPath || '/mcp'), [config?.endpointPath]);
  const availableConnections = config?.connections || [];
  const connectionById = useMemo(
    () => new Map((config?.connections || []).map((connection) => [connection.id, connection])),
    [config?.connections]
  );

  useEffect(() => {
    void loadConfig();
  }, []);

  async function loadConfig() {
    setLoading(true);
    try {
      const next = await api<McpConfig>('/mcp/config');
      applyConfig(next);
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '加载 MCP 配置失败');
    } finally {
      setLoading(false);
    }
  }

  function applyConfig(next: McpConfig) {
    setConfig(next);
    configForm.setFieldsValue({ ...next.limits, allowedOrigins: next.allowedOrigins });
  }

  async function updateStatus(enabled: boolean) {
    setStatusSaving(true);
    try {
      const next = await api<McpConfig>('/mcp/status', {
        method: 'PUT',
        body: JSON.stringify({ enabled })
      });
      applyConfig(next);
      messageApi.success(enabled ? 'MCP Server 已开启' : 'MCP Server 已关闭');
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '更新 MCP 状态失败');
    } finally {
      setStatusSaving(false);
    }
  }

  async function saveConfig(values: ConfigForm) {
    setSaving(true);
    try {
      const { allowedOrigins, ...limits } = values;
      const next = await api<McpConfig>('/mcp/config', {
        method: 'PUT',
        body: JSON.stringify({ allowedOrigins: allowedOrigins || [], limits })
      });
      applyConfig(next);
      messageApi.success('MCP 限制与 Origin 已保存并即时生效');
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '保存 MCP 配置失败');
    } finally {
      setSaving(false);
    }
  }

  function openCreateAgent() {
    setEditingAgent(undefined);
    agentForm.setFieldsValue({ agentId: '', connectionIds: [], levels: {}, allowProduction: false, enabled: true });
    setAgentModalOpen(true);
  }

  function openEditAgent(agent: McpAgent) {
    setEditingAgent(agent);
    const restored = grantsToFormValues(agent.grants);
    agentForm.setFieldsValue({
      agentId: agent.agentId,
      connectionIds: restored.connectionIds,
      levels: restored.levels,
      allowProduction: agent.allowProduction,
      enabled: agent.enabled
    });
    setAgentModalOpen(true);
  }

  async function saveAgent(values: AgentForm) {
    const grants = buildAgentGrants(values.connectionIds, values.levels, connectionById);
    const warnings = agentGrantWarnings(grants, connectionById);
    if (warnings.production.length > 0 && !values.allowProduction) {
      messageApi.error('选择生产连接时必须开启生产环境权限');
      return;
    }
    if (warnings.production.length > 0 || warnings.writable.length > 0) {
      const confirmed = await confirmProduction(warnings);
      if (!confirmed) return;
    }
    setAgentSaving(true);
    try {
      if (editingAgent) {
        await api<McpAgent>(`/mcp/agents/${editingAgent.id}`, {
          method: 'PUT',
          body: JSON.stringify({
            enabled: values.enabled,
            grants,
            allowProduction: values.allowProduction
          })
        });
        messageApi.success('Agent 权限已更新并即时生效');
      } else {
        const created = await api<McpCredential>('/mcp/agents', {
          method: 'POST',
          body: JSON.stringify({
            agentId: values.agentId,
            grants,
            allowProduction: values.allowProduction
          })
        });
        setCredential(created);
      }
      setAgentModalOpen(false);
      await loadConfig();
    } catch (error) {
      messageApi.error(error instanceof Error ? error.message : '保存 Agent 失败');
    } finally {
      setAgentSaving(false);
    }
  }

  function rotateKey(agent: McpAgent) {
    modal.confirm({
      title: `轮换 ${agent.agentId} 的 API Key？`,
      content: '旧 Key 会立即失效，正在使用旧 Key 的 Agent 将无法继续访问。',
      okText: '确认轮换',
      okButtonProps: { danger: true },
      async onOk() {
        try {
          const rotated = await api<McpCredential>(`/mcp/agents/${agent.id}/rotate-key`, { method: 'POST' });
          setCredential(rotated);
          await loadConfig();
        } catch (error) {
          messageApi.error(error instanceof Error ? error.message : '轮换 API Key 失败');
          throw error;
        }
      }
    });
  }

  function deleteAgent(agent: McpAgent) {
    modal.confirm({
      title: `删除 Agent ${agent.agentId}？`,
      content: '该 Agent 的 Key 和所有连接授权会立即失效，此操作不可撤销。',
      okText: '删除',
      okButtonProps: { danger: true },
      async onOk() {
        try {
          await api(`/mcp/agents/${agent.id}`, { method: 'DELETE' });
          messageApi.success('Agent 已删除');
          await loadConfig();
        } catch (error) {
          messageApi.error(error instanceof Error ? error.message : '删除 Agent 失败');
          throw error;
        }
      }
    });
  }

  /**
   * 高风险授权的二次确认。
   *
   * 写档位单独点名：把写能力交给一个自动化 Agent 和交给一个人不是一回事，它不会在回车前停顿。
   * 生产库上的写操作在执行时仍需回传连接名确认，但那道关卡对 Agent 来说算不上阻力 ——
   * 真正的把关就在这里。
   */
  function confirmProduction(warnings: { production: string[]; writable: string[] }) {
    return new Promise<boolean>((resolve) => {
      modal.confirm({
        title: warnings.writable.length > 0 ? '确认授予写权限' : '确认授权生产数据库',
        content: (
          <Space orientation="vertical" size={4}>
            {warnings.writable.length > 0 && (
              <Text>该 Agent 将能够修改以下连接的数据：{warnings.writable.join('、')}。</Text>
            )}
            {warnings.production.length > 0 && (
              <Text>其中涉及生产连接：{warnings.production.join('、')}。</Text>
            )}
            <Text type="secondary">请确认该 Agent 确实需要这些权限。授权即时生效。</Text>
          </Space>
        ),
        okText: '确认授权',
        okButtonProps: { danger: true },
        onOk: () => resolve(true),
        onCancel: () => resolve(false)
      });
    });
  }

  async function copyText(value: string, label: string) {
    try {
      await navigator.clipboard.writeText(value);
      messageApi.success(`${label}已复制`);
    } catch {
      messageApi.error('复制失败，请手动选择文本');
    }
  }

  const agentColumns = [
    {
      title: 'Agent',
      dataIndex: 'agentId',
      render: (value: string, agent: McpAgent) => (
        <Space orientation="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text type="secondary">{agent.enabled ? '已启用' : '已停用'}</Text>
        </Space>
      )
    },
    {
      title: '连接权限',
      render: (_: unknown, agent: McpAgent) => (
        <Space size={[4, 4]} wrap>
          {agent.grants.length === 0 && <Text type="secondary">无授权</Text>}
          {agent.grants.map((grant) => {
            const connection = connectionById.get(grant.connectionId);
            const name = connection?.name || `#${grant.connectionId}`;
            // 档位比环境更值得用颜色强调：写权限才是这份授权真正的风险面。
            const color = mcpAccessLevelColor(grant.accessLevel) || (connection?.environment === 'prod' ? 'red' : 'blue');
            return (
              <Tag key={grant.connectionId} color={color}>
                {name} · {MCP_ACCESS_LEVEL_LABELS[grant.accessLevel]}
              </Tag>
            );
          })}
        </Space>
      )
    },
    {
      title: '生产权限',
      dataIndex: 'allowProduction',
      width: 100,
      render: (allowed: boolean) => allowed ? <Tag color="red">允许</Tag> : <Tag>禁止</Tag>
    },
    {
      title: '操作',
      width: 240,
      render: (_: unknown, agent: McpAgent) => (
        <Space wrap>
          <Button size="small" onClick={() => openEditAgent(agent)}>编辑</Button>
          <Button size="small" icon={<KeyOutlined />} onClick={() => rotateKey(agent)}>轮换 Key</Button>
          <Button size="small" danger icon={<DeleteOutlined />} onClick={() => deleteAgent(agent)}>删除</Button>
        </Space>
      )
    }
  ];

  return (
    <div className="mcp-settings-shell">
      {messageContext}
      {modalContext}
      <Tabs
        className="mcp-main-tabs"
        activeKey={activeSection}
        destroyOnHidden={false}
        onChange={(key) => setActiveSection(key as 'settings' | 'help')}
        items={[
          {
            key: 'settings',
            label: <Space size={6}><SettingOutlined />服务配置</Space>,
            children: (
              <div className="mcp-settings-panel">
                <Card loading={loading} className="mcp-status-card">
        <div className="mcp-status-row">
          <div>
            <Space align="center">
              <Title level={4}>MCP Server</Title>
              <Tag color={config?.enabled ? 'success' : 'default'}>{config?.enabled ? '运行中' : '已关闭'}</Tag>
            </Space>
            <Paragraph type="secondary">Streamable HTTP 端点常驻，开关和权限修改无需重启后端。</Paragraph>
          </div>
          <Space>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void loadConfig()}>刷新</Button>
            <Switch
              checked={config?.enabled || false}
              loading={statusSaving}
              checkedChildren="开启"
              unCheckedChildren="关闭"
              onChange={(checked) => void updateStatus(checked)}
            />
          </Space>
        </div>
        {config?.enabled && config.agents.length === 0 && (
          <Alert
            type="warning"
            showIcon
            title="MCP 已开启，但尚未创建 Agent；创建 Agent 并保存 API Key 后才能接入。"
            className="mcp-section-alert"
          />
        )}
        <Descriptions size="small" column={1} bordered>
          <Descriptions.Item label="MCP URL">
            <Space>
              <Text code copyable>{endpoint}</Text>
              <Button size="small" icon={<CopyOutlined />} onClick={() => void copyText(endpoint, 'MCP URL')}>复制</Button>
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="认证格式"><Text code>Authorization: Bearer agent-id.secret</Text></Descriptions.Item>
        </Descriptions>
                </Card>

                <Card
                  title="Agent 与连接授权"
                  extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreateAgent}>新建 Agent</Button>}
                >
        <Alert
          type="info"
          showIcon
          title="MCP 可访问 Agent 白名单内的连接。默认只发布查询和元数据工具；授予「数据读写」或「完全」档位后该连接才允许 db_execute 写入，且仍受只读连接、生产确认与审计约束。"
          className="mcp-section-alert"
        />
        <Table<McpAgent>
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={config?.agents || []}
          columns={agentColumns}
          scroll={{ x: 800 }}
          locale={{ emptyText: '尚未创建 Agent' }}
        />
                </Card>

                <Card title="Origin 与资源限制">
        <Form<ConfigForm> form={configForm} layout="vertical" onFinish={(values) => void saveConfig(values)}>
          <Form.Item
            name="allowedOrigins"
            label="允许的浏览器 Origin"
            tooltip="CLI 和服务端 Agent 通常不携带 Origin，可保持为空。"
          >
            <Select mode="tags" tokenSeparators={[',', ' ']} placeholder="例如 https://agent.example.internal" />
          </Form.Item>
          <Collapse
            ghost
            items={[{
              key: 'limits',
              label: '高级资源限制',
              children: <LimitsForm />
            }]}
          />
          <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}>保存并即时生效</Button>
        </Form>
                </Card>
              </div>
            )
          },
          {
            key: 'help',
            label: <Space size={6}><ReadOutlined />接入帮助</Space>,
            children: (
              <McpHelpPanel
                endpoint={endpoint}
                enabled={config?.enabled ?? false}
                agents={config?.agents || []}
                onOpenConfig={() => setActiveSection('settings')}
                onCopy={copyText}
              />
            )
          }
        ]}
      />

      <Modal
        title={editingAgent ? `编辑 Agent：${editingAgent.agentId}` : '新建 MCP Agent'}
        open={agentModalOpen}
        footer={null}
        onCancel={() => setAgentModalOpen(false)}
        destroyOnHidden
      >
        <Form<AgentForm>
          form={agentForm}
          layout="vertical"
          onFinish={(values) => void saveAgent(values)}
          initialValues={{ connectionIds: [], levels: {}, allowProduction: false, enabled: true }}
        >
          <Form.Item
            name="agentId"
            label="Agent ID"
            rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]{1,64}$/, message: '仅允许字母、数字、下划线和短横线，最长 64 字符' }]}
          >
            <Input disabled={Boolean(editingAgent)} placeholder="例如 analytics-agent" />
          </Form.Item>
          <Form.Item name="connectionIds" label="连接白名单" rules={[{ required: true, type: 'array', min: 1, message: '至少选择一个连接' }]}>
            <Select
              mode="multiple"
              optionFilterProp="label"
              options={availableConnections.map((connection) => ({
                value: connection.id,
                label: `${connection.name} · ${connection.environment} · ${connection.readonly ? '只读' : '可写'}${connection.environment === 'prod' ? ' · 生产' : ''}`
              }))}
              placeholder="选择允许 Agent 访问的连接"
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(before, after) => before.connectionIds !== after.connectionIds}>
            {({ getFieldValue }) => {
              const selected: number[] = getFieldValue('connectionIds') || [];
              if (selected.length === 0) return null;
              return (
                <Form.Item label="访问档位" extra="档位按连接单独授予；只读连接只能是只读。写档位仍受生产确认、未限定范围写确认与审计约束。">
                  <Space orientation="vertical" size={4} style={{ width: '100%' }}>
                    {selected.map((connectionId) => {
                      const connection = connectionById.get(connectionId);
                      const options = allowedLevelsFor(connection);
                      return (
                        <Space key={connectionId} size={8} wrap>
                          <Tag color={connection?.environment === 'prod' ? 'red' : 'blue'}>
                            {connection?.name || `#${connectionId}`}
                          </Tag>
                          <Form.Item name={['levels', String(connectionId)]} noStyle initialValue="READ_ONLY">
                            <Select
                              size="small"
                              style={{ minWidth: 200 }}
                              disabled={options.length === 1}
                              options={options.map((level) => ({
                                value: level,
                                label: `${MCP_ACCESS_LEVEL_LABELS[level]} · ${MCP_ACCESS_LEVEL_HINTS[level]}`
                              }))}
                            />
                          </Form.Item>
                          {connection?.readonly && <Text type="secondary">只读连接</Text>}
                        </Space>
                      );
                    })}
                  </Space>
                </Form.Item>
              );
            }}
          </Form.Item>
          <Form.Item name="allowProduction" label="允许生产环境" valuePropName="checked">
            <Switch checkedChildren="允许" unCheckedChildren="禁止" />
          </Form.Item>
          {editingAgent && (
            <Form.Item name="enabled" label="Agent 状态" valuePropName="checked">
              <Switch checkedChildren="启用" unCheckedChildren="停用" />
            </Form.Item>
          )}
          <Space>
            <Button onClick={() => setAgentModalOpen(false)}>取消</Button>
            <Button type="primary" htmlType="submit" loading={agentSaving}>{editingAgent ? '保存权限' : '生成 API Key'}</Button>
          </Space>
        </Form>
      </Modal>

      <Modal
        title="请立即保存 Agent API Key"
        open={Boolean(credential)}
        width={920}
        className="mcp-credential-modal"
        closable={false}
        mask={{ closable: false }}
        keyboard={false}
        footer={<Button type="primary" onClick={() => setCredential(undefined)}>我已安全保存</Button>}
      >
        {credential && (
          <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
            <Alert type="warning" showIcon title="完整凭据只显示这一次。关闭后无法找回，只能重新轮换 Key。" />
            <Input.TextArea value={credential.credential} readOnly autoSize={{ minRows: 2, maxRows: 4 }} />
            <Button icon={<CopyOutlined />} onClick={() => void copyText(credential.credential, '完整凭据')}>复制完整凭据</Button>
            <Title level={5}>选择 AI Agent 并复制配置</Title>
            <McpClientGuideTabs endpoint={endpoint} credential={credential.credential} compact onCopy={copyText} />
          </Space>
        )}
      </Modal>
    </div>
  );
}

function LimitsForm() {
  const fields: Array<{ name: keyof McpLimits; label: string; max: number }> = [
    { name: 'defaultQueryRows', label: '查询默认行数', max: 5_000 },
    { name: 'maxQueryRows', label: '查询最大行数', max: 5_000 },
    { name: 'maxResultCells', label: '结果单元格上限', max: 200_000 },
    { name: 'maxResultTextChars', label: '结果文本总量', max: 20_000_000 },
    { name: 'maxCellTextChars', label: '单元文本上限', max: 100_000 },
    { name: 'maxSqlChars', label: 'SQL 长度上限', max: 1_000_000 },
    { name: 'queryTimeoutSeconds', label: '查询超时（秒）', max: 300 },
    { name: 'metadataPageSize', label: '元数据默认分页', max: 1_000 },
    { name: 'maxMetadataPageSize', label: '元数据最大分页', max: 1_000 },
    { name: 'tablePageSize', label: '表数据默认分页', max: 1_000 },
    { name: 'maxTablePageSize', label: '表数据最大分页', max: 1_000 },
    { name: 'sessionTtlMinutes', label: 'Session TTL（分钟）', max: 1_440 }
  ];
  return (
    <div className="mcp-limit-grid">
      {fields.map((field) => (
        <Form.Item key={field.name} name={field.name} label={field.label} rules={[{ required: true }]}>
          <InputNumber min={1} max={field.max} precision={0} style={{ width: '100%' }} />
        </Form.Item>
      ))}
    </div>
  );
}

function resolveMcpEndpoint(endpointPath: string) {
  const apiUrl = new URL(API, window.location.origin);
  apiUrl.pathname = apiUrl.pathname.replace(/\/api\/?$/, endpointPath);
  apiUrl.search = '';
  apiUrl.hash = '';
  return apiUrl.toString().replace(/\/$/, '');
}
