import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import { CopyOutlined, PlusOutlined } from '@ant-design/icons';
import { api } from '../api';
import type { AccessTokenItem, AccessTokenScope, CreatedAccessToken, TokenConnectionOption } from '../types';
import { formatDateTime } from '../utils';

const { Text, Paragraph } = Typography;

type AccessTokenModalProps = {
  open: boolean;
  onClose: () => void;
};

type CreateFormValues = {
  name: string;
  expiresInDays: number;
  connectionScope: AccessTokenScope;
  connectionIds?: number[];
};

type EditScopeFormValues = {
  connectionScope: AccessTokenScope;
  connectionIds?: number[];
};

export function AccessTokenModal({ open, onClose }: AccessTokenModalProps) {
  const [tokens, setTokens] = useState<AccessTokenItem[]>([]);
  const [connections, setConnections] = useState<TokenConnectionOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [createdToken, setCreatedToken] = useState<CreatedAccessToken | null>(null);
  const [editingScope, setEditingScope] = useState<AccessTokenItem | null>(null);
  const [createForm] = Form.useForm<CreateFormValues>();
  const [scopeForm] = Form.useForm<EditScopeFormValues>();
  const [messageApi, messageContext] = message.useMessage();

  const createScope = Form.useWatch('connectionScope', createForm) ?? 'LISTED';
  const editScope = Form.useWatch('connectionScope', scopeForm) ?? 'LISTED';

  const connectionById = useMemo(
    () => new Map(connections.map((item) => [item.id, item])),
    [connections]
  );

  async function load() {
    setLoading(true);
    setError('');
    try {
      const [tokenList, connList] = await Promise.all([
        api<AccessTokenItem[]>('/auth/tokens'),
        api<TokenConnectionOption[]>('/auth/tokens/connections')
      ]);
      setTokens(tokenList);
      setConnections(connList);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '访问令牌加载失败');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (open) void load();
  }, [open]);

  function openCreate() {
    setCreatedToken(null);
    createForm.setFieldsValue({
      name: '',
      expiresInDays: 90,
      connectionScope: 'LISTED',
      connectionIds: []
    });
    setCreating(true);
  }

  function openEditScope(item: AccessTokenItem) {
    scopeForm.setFieldsValue({
      connectionScope: item.connectionScope,
      connectionIds: item.connectionIds
    });
    setEditingScope(item);
  }

  async function submitCreate() {
    const values = await createForm.validateFields();
    setSaving(true);
    try {
      const created = await api<CreatedAccessToken>('/auth/tokens', {
        method: 'POST',
        body: JSON.stringify({
          name: values.name,
          expiresInDays: values.expiresInDays,
          connectionScope: values.connectionScope,
          connectionIds: values.connectionScope === 'LISTED' ? values.connectionIds ?? [] : []
        })
      });
      setCreating(false);
      setCreatedToken(created);
      messageApi.success('访问令牌已创建');
      await load();
    } catch (cause) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return;
      messageApi.error(cause instanceof Error ? cause.message : '访问令牌创建失败');
    } finally {
      setSaving(false);
    }
  }

  async function submitEditScope() {
    if (!editingScope) return;
    const values = await scopeForm.validateFields();
    setSaving(true);
    try {
      await api<AccessTokenItem>(`/auth/tokens/${editingScope.id}/connections`, {
        method: 'PUT',
        body: JSON.stringify({
          connectionScope: values.connectionScope,
          connectionIds: values.connectionScope === 'LISTED' ? values.connectionIds ?? [] : []
        })
      });
      setEditingScope(null);
      messageApi.success('连接范围已更新');
      await load();
    } catch (cause) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return;
      messageApi.error(cause instanceof Error ? cause.message : '连接范围更新失败');
    } finally {
      setSaving(false);
    }
  }

  async function revoke(item: AccessTokenItem) {
    try {
      await api<void>(`/auth/tokens/${item.id}`, { method: 'DELETE' });
      messageApi.success('访问令牌已吊销');
      await load();
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '访问令牌吊销失败');
    }
  }

  async function copyText(text: string, label: string) {
    try {
      await navigator.clipboard.writeText(text);
      messageApi.success(`${label}已复制`);
    } catch {
      messageApi.warning('无法自动复制到剪贴板，请手动选中复制');
    }
  }

  const connectionSelectOptions = connections.map((conn) => ({
    value: conn.id,
    label: `${conn.name} (${conn.dbType}${conn.shared ? ' · 共享' : ''})`,
    conn
  }));

  const origin = typeof window !== 'undefined' ? window.location.origin : 'https://db.example.com';

  return (
    <Modal
      title="个人访问令牌（CLI）"
      open={open}
      onCancel={onClose}
      footer={null}
      width={880}
      destroyOnHidden
    >
      {messageContext}
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
          <Text type="secondary">
            用于 <Text code>mdd</Text> 命令行或 CI/CD。令牌不能调用全局管理接口，也不能再签发新令牌；修改密码或停用账号时旧令牌立即失效。
          </Text>
          <Button type="primary" size="small" icon={<PlusOutlined />} onClick={openCreate}>
            创建令牌
          </Button>
        </div>

        <Alert
          type="info"
          showIcon
          title="SSO / OIDC 账号提示：身份平台的角色与分组变更在下次 SSO 登录时同步；需要立刻失效请直接在此吊销令牌。"
        />

        {createdToken && (
          <Alert
            type="success"
            showIcon
            title={`令牌「${createdToken.tokenInfo.name}」已生成，明文仅展示这一次，请立即保存`}
            description={
              <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 4 }}>
                <Space.Compact style={{ width: '100%' }}>
                  <Input readOnly value={createdToken.token} />
                  <Button icon={<CopyOutlined />} onClick={() => void copyText(createdToken.token, '令牌')}>
                    复制
                  </Button>
                </Space.Compact>
                <Paragraph style={{ marginBottom: 0 }}>
                  <Text type="secondary">CLI 登录示例：</Text>
                  <Text code>{`mdd login --server ${origin}`}</Text>
                </Paragraph>
              </Space>
            }
            closable
            onClose={() => setCreatedToken(null)}
          />
        )}

        {error && (
          <Alert
            type="error"
            showIcon
            title={error}
            action={<Button size="small" onClick={() => void load()}>重试</Button>}
          />
        )}

        <Table<AccessTokenItem>
          rowKey="id"
          size="small"
          loading={loading}
          dataSource={tokens}
          pagination={false}
          scroll={{ x: 760 }}
          columns={[
            {
              title: '名称 / ID',
              dataIndex: 'name',
              width: 170,
              render: (_, item) => (
                <Space direction="vertical" size={0}>
                  <Text strong>{item.name}</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>{item.id}</Text>
                </Space>
              )
            },
            {
              title: '连接范围',
              dataIndex: 'connectionScope',
              width: 210,
              render: (_, item) =>
                item.connectionScope === 'ALL' ? (
                  <Tag color="gold">继承账号全部连接</Tag>
                ) : (
                  <Space size={[4, 4]} wrap>
                    {item.connectionIds.map((id) => (
                      <Tag key={id}>{connectionById.get(id)?.name ?? `#${id}`}</Tag>
                    ))}
                    {item.connectionIds.length === 0 && <Text type="secondary">无连接</Text>}
                  </Space>
                )
            },
            {
              title: '状态',
              dataIndex: 'active',
              width: 85,
              render: (_, item) => {
                if (item.revokedAt) return <Tag>已吊销</Tag>;
                if (!item.active) return <Tag color="default">已过期</Tag>;
                return <Tag color="green">生效中</Tag>;
              }
            },
            {
              title: '过期时间',
              dataIndex: 'expiresAt',
              width: 145,
              render: (value: string) => formatDateTime(value)
            },
            {
              title: '最近使用',
              dataIndex: 'lastUsedAt',
              width: 150,
              render: (_, item) =>
                item.lastUsedAt ? (
                  <Space direction="vertical" size={0}>
                    <span>{formatDateTime(item.lastUsedAt)}</span>
                    {item.lastUsedIp && <Text type="secondary" style={{ fontSize: 12 }}>{item.lastUsedIp}</Text>}
                  </Space>
                ) : (
                  <Text type="secondary">未使用</Text>
                )
            },
            {
              title: '操作',
              key: 'actions',
              width: 120,
              fixed: 'right',
              render: (_, item) => (
                <Space size={4}>
                  <Button type="link" size="small" disabled={!item.active} onClick={() => openEditScope(item)}>
                    范围
                  </Button>
                  <Popconfirm
                    title={`吊销令牌「${item.name}」？`}
                    description="吊销后所有使用该令牌的 CLI 与 CI 脚本会立即收到 401。"
                    okText="吊销"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => void revoke(item)}
                  >
                    <Button type="link" size="small" danger disabled={Boolean(item.revokedAt)}>
                      吊销
                    </Button>
                  </Popconfirm>
                </Space>
              )
            }
          ]}
        />
      </Space>

      <Modal
        title="创建访问令牌"
        open={creating}
        okText="生成令牌"
        cancelText="取消"
        confirmLoading={saving}
        onOk={() => void submitCreate()}
        onCancel={() => setCreating(false)}
        destroyOnHidden
      >
        <Form form={createForm} layout="vertical" requiredMark={false}>
          <Form.Item name="name" label="令牌名称" rules={[{ required: true, message: '请输入令牌名称' }]}>
            <Input placeholder="例如：gitlab-ci 或本机终端" maxLength={120} />
          </Form.Item>
          <Form.Item
            name="expiresInDays"
            label="有效期（天，最长 365 天）"
            rules={[{ required: true, message: '请输入有效期' }]}
          >
            <InputNumber min={1} max={365} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="connectionScope" label="允许访问的连接范围" rules={[{ required: true }]}>
            <Radio.Group>
              <Space direction="vertical">
                <Radio value="LISTED">仅指定连接（推荐，与账号权限取交集）</Radio>
                <Radio value="ALL">继承账号全部连接</Radio>
              </Space>
            </Radio.Group>
          </Form.Item>
          {createScope === 'ALL' ? (
            <Alert
              type="warning"
              showIcon
              title="继承账号全部连接将包含系统中的所有「共享」连接。用于 CI 时建议选择「仅指定连接」。"
              style={{ marginBottom: 12 }}
            />
          ) : (
            <Form.Item
              name="connectionIds"
              label="连接白名单"
              rules={[{ required: true, type: 'array', min: 1, message: '请至少选择一条连接' }]}
            >
              <Select
                mode="multiple"
                placeholder="选择该令牌可访问的连接"
                optionFilterProp="label"
                options={connectionSelectOptions}
                optionRender={(option) => {
                  const conn = option.data.conn as TokenConnectionOption;
                  return (
                    <Space>
                      <span>{conn.name}</span>
                      <Tag>{conn.dbType}</Tag>
                      {conn.environment === 'prod' && <Tag color="red">生产</Tag>}
                      {conn.shared && <Tag color="orange">共享</Tag>}
                    </Space>
                  );
                }}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <Modal
        title={`修改连接范围：${editingScope?.name ?? ''}`}
        open={Boolean(editingScope)}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onOk={() => void submitEditScope()}
        onCancel={() => setEditingScope(null)}
        destroyOnHidden
      >
        <Form form={scopeForm} layout="vertical" requiredMark={false}>
          <Form.Item name="connectionScope" label="连接范围" rules={[{ required: true }]}>
            <Radio.Group>
              <Space direction="vertical">
                <Radio value="LISTED">仅指定连接（推荐）</Radio>
                <Radio value="ALL">继承账号全部连接</Radio>
              </Space>
            </Radio.Group>
          </Form.Item>
          {editScope === 'ALL' ? (
            <Alert
              type="warning"
              showIcon
              title="继承账号全部连接将包含所有「共享」连接。"
              style={{ marginBottom: 12 }}
            />
          ) : (
            <Form.Item
              name="connectionIds"
              label="连接白名单"
              rules={[{ required: true, type: 'array', min: 1, message: '请至少选择一条连接' }]}
            >
              <Select
                mode="multiple"
                placeholder="选择该令牌可访问的连接"
                optionFilterProp="label"
                options={connectionSelectOptions}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </Modal>
  );
}
