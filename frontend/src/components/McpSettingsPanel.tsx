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
  Tag,
  Typography,
  message
} from 'antd';
import {
  CopyOutlined,
  DeleteOutlined,
  KeyOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined
} from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { API } from '../constants';
import type { McpAgent, McpConfig, McpCredential, McpLimits } from '../types';

const { Paragraph, Text, Title } = Typography;

type ConfigForm = McpLimits & { allowedOrigins: string[] };
type AgentForm = { agentId: string; connectionIds: number[]; allowProduction: boolean; enabled: boolean };

export function McpSettingsPanel() {
  const [config, setConfig] = useState<McpConfig>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [statusSaving, setStatusSaving] = useState(false);
  const [agentSaving, setAgentSaving] = useState(false);
  const [editingAgent, setEditingAgent] = useState<McpAgent>();
  const [agentModalOpen, setAgentModalOpen] = useState(false);
  const [credential, setCredential] = useState<McpCredential>();
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
    agentForm.setFieldsValue({ agentId: '', connectionIds: [], allowProduction: false, enabled: true });
    setAgentModalOpen(true);
  }

  function openEditAgent(agent: McpAgent) {
    setEditingAgent(agent);
    agentForm.setFieldsValue({
      agentId: agent.agentId,
      connectionIds: agent.connectionIds,
      allowProduction: agent.allowProduction,
      enabled: agent.enabled
    });
    setAgentModalOpen(true);
  }

  async function saveAgent(values: AgentForm) {
    const includesProduction = values.connectionIds.some((id) => connectionById.get(id)?.environment === 'prod');
    if (includesProduction && !values.allowProduction) {
      messageApi.error('选择生产连接时必须开启生产环境权限');
      return;
    }
    if (includesProduction && values.allowProduction) {
      const confirmed = await confirmProduction();
      if (!confirmed) return;
    }
    setAgentSaving(true);
    try {
      if (editingAgent) {
        await api<McpAgent>(`/mcp/agents/${editingAgent.id}`, {
          method: 'PUT',
          body: JSON.stringify({
            enabled: values.enabled,
            connectionIds: values.connectionIds,
            allowProduction: values.allowProduction
          })
        });
        messageApi.success('Agent 权限已更新并即时生效');
      } else {
        const created = await api<McpCredential>('/mcp/agents', {
          method: 'POST',
          body: JSON.stringify({
            agentId: values.agentId,
            connectionIds: values.connectionIds,
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

  function confirmProduction() {
    return new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '确认授权生产数据库',
        content: '该 Agent 将能够查询所选生产连接。请确认该 Agent 确实需要这些生产数据访问权限。',
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
        <Space direction="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text type="secondary">{agent.enabled ? '已启用' : '已停用'}</Text>
        </Space>
      )
    },
    {
      title: '连接权限',
      render: (_: unknown, agent: McpAgent) => (
        <Space size={[4, 4]} wrap>
          {agent.connectionIds.length === 0 && <Text type="secondary">无授权</Text>}
          {agent.connectionIds.map((id) => {
            const connection = connectionById.get(id);
            return <Tag key={id} color={connection?.environment === 'prod' ? 'red' : 'blue'}>{connection?.name || `#${id}`}</Tag>;
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
    <div className="mcp-settings-panel">
      {messageContext}
      {modalContext}
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
            message="MCP 已开启，但尚未创建 Agent；创建 Agent 并保存 API Key 后才能接入。"
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
          message="MCP 可查询 Agent 白名单内的任意连接，但只发布查询和元数据工具；DML、DDL、多语句及其他非查询 SQL 仍会被后端拒绝。"
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
          initialValues={{ connectionIds: [], allowProduction: false, enabled: true }}
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
        closable={false}
        maskClosable={false}
        keyboard={false}
        footer={<Button type="primary" onClick={() => setCredential(undefined)}>我已安全保存</Button>}
      >
        {credential && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Alert type="warning" showIcon message="完整凭据只显示这一次。关闭后无法找回，只能重新轮换 Key。" />
            <Input.TextArea value={credential.credential} readOnly autoSize={{ minRows: 2, maxRows: 4 }} />
            <Space wrap>
              <Button icon={<CopyOutlined />} onClick={() => void copyText(credential.credential, '完整凭据')}>复制完整凭据</Button>
              <Button icon={<CopyOutlined />} onClick={() => void copyText(clientConfig(endpoint, credential.credential), '客户端配置')}>复制客户端 JSON</Button>
            </Space>
            <Input.TextArea value={clientConfig(endpoint, credential.credential)} readOnly autoSize={{ minRows: 8, maxRows: 12 }} />
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

function clientConfig(endpoint: string, credential: string) {
  return JSON.stringify({
    mcpServers: {
      mydatadev: {
        type: 'streamable-http',
        url: endpoint,
        headers: { Authorization: `Bearer ${credential}` }
      }
    }
  }, null, 2);
}
