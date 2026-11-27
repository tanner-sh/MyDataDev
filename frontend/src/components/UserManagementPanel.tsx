import { useEffect, useState } from 'react';
import { Alert, Button, Divider, Form, Input, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { api } from '../api';
import { authenticatedUsername } from '../auth';
import type { AccessTokenItem } from '../types';
import type { WebUser, WebUserForm } from '../users';
import { formatDateTime } from '../utils';

const { Text } = Typography;

export function UserManagementPanel() {
  const [users, setUsers] = useState<WebUser[]>([]);
  const [tokens, setTokens] = useState<AccessTokenItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<WebUser | 'new'>();
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<WebUserForm>();
  const [messageApi, messageContext] = message.useMessage();
  const currentUsername = authenticatedUsername();

  async function load() {
    setLoading(true);
    setError('');
    try {
      const [userList, tokenList] = await Promise.all([
        api<WebUser[]>('/admin/users'),
        api<AccessTokenItem[]>('/admin/tokens')
      ]);
      setUsers(userList);
      setTokens(tokenList);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '用户列表加载失败');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  function openCreate() {
    setEditing('new');
    form.setFieldsValue({ username: '', displayName: '', role: 'OPERATOR', password: '', enabled: true });
  }

  function openEdit(user: WebUser) {
    setEditing(user);
    form.setFieldsValue({
      username: user.username,
      displayName: user.displayName,
      role: user.role,
      password: undefined,
      enabled: user.enabled
    });
  }

  async function save() {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editing === 'new') {
        await api<WebUser>('/admin/users', { method: 'POST', body: JSON.stringify(values) });
        messageApi.success('用户已创建');
      } else if (editing) {
        const body = { ...values, password: values.password || undefined };
        await api<WebUser>(`/admin/users/${editing.id}`, { method: 'PUT', body: JSON.stringify(body) });
        messageApi.success('用户已更新');
      }
      setEditing(undefined);
      await load();
    } catch (cause) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return;
      messageApi.error(cause instanceof Error ? cause.message : '用户保存失败');
    } finally {
      setSaving(false);
    }
  }

  async function remove(user: WebUser) {
    try {
      await api<void>(`/admin/users/${user.id}`, { method: 'DELETE' });
      messageApi.success('用户已删除');
      await load();
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '用户删除失败');
    }
  }

  async function revokeToken(token: AccessTokenItem) {
    try {
      await api<void>(`/admin/tokens/${token.id}`, { method: 'DELETE' });
      messageApi.success(`已吊销令牌「${token.name}」`);
      await load();
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '令牌吊销失败');
    }
  }

  return (
    <div className="management-section user-management-panel">
      {messageContext}
      <header className="management-section-header">
        <div>
          <Text strong>用户与权限</Text>
          <div><Text type="secondary">每位使用者应拥有独立账号；操作审计记录登录用户名。</Text></div>
        </div>
        <Button type="primary" size="small" icon={<PlusOutlined />} onClick={openCreate}>新建用户</Button>
      </header>
      {error && <Alert type="error" showIcon title={error} action={<Button size="small" onClick={() => void load()}>重试</Button>} />}
      <Table<WebUser>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={users}
        pagination={false}
        scroll={{ x: 820 }}
        columns={[
          {
            title: '用户', dataIndex: 'username', width: 190,
            render: (_, user) => <Space orientation="vertical" size={0}><Text strong>{user.displayName}</Text><Text type="secondary">{user.username}{user.username === currentUsername ? ' · 当前账号' : ''}</Text></Space>
          },
          { title: '来源', dataIndex: 'provider', width: 90, render: (provider: string) => <Tag>{provider}</Tag> },
          { title: '角色', dataIndex: 'role', width: 110, render: (role: WebUser['role']) => <Tag color={role === 'ADMIN' ? 'blue' : 'default'}>{role === 'ADMIN' ? '管理员' : '操作员'}</Tag> },
          { title: '状态', dataIndex: 'enabled', width: 90, render: (enabled: boolean) => <Tag color={enabled ? 'green' : 'default'}>{enabled ? '启用' : '停用'}</Tag> },
          { title: '最近登录', dataIndex: 'lastLoginAt', width: 180, render: (value?: string) => formatDateTime(value) || '从未登录' },
          {
            title: '操作', key: 'actions', width: 150, fixed: 'right',
            render: (_, user) => <Space>
              <Button type="link" size="small" onClick={() => openEdit(user)}>编辑</Button>
              <Popconfirm title={`删除用户 ${user.username}？`} okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => void remove(user)}>
                <Button type="link" size="small" danger disabled={user.username === currentUsername || user.provider !== 'LOCAL'}>删除</Button>
              </Popconfirm>
            </Space>
          }
        ]}
      />
      <Divider style={{ margin: '16px 0 12px' }} />
      <header className="management-section-header">
        <div>
          <Text strong>CLI 访问令牌（全部用户）</Text>
          <div><Text type="secondary">管理员可查看全部用户的个人访问令牌并随时吊销。</Text></div>
        </div>
      </header>
      <Table<AccessTokenItem>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={tokens}
        pagination={false}
        scroll={{ x: 820 }}
        columns={[
          {
            title: '所属用户',
            dataIndex: 'username',
            width: 140,
            render: (_, item) => <Text strong>{item.username || `#${item.userId}`}</Text>
          },
          {
            title: '令牌名称 / ID',
            dataIndex: 'name',
            width: 170,
            render: (_, item) => (
              <Space orientation="vertical" size={0}>
                <Text>{item.name}</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>{item.id}</Text>
              </Space>
            )
          },
          {
            title: '范围',
            dataIndex: 'connectionScope',
            width: 130,
            render: (_, item) =>
              item.connectionScope === 'ALL'
                ? <Tag color="gold">全部连接</Tag>
                : <Tag color="blue">{item.connectionIds.length} 条指定连接</Tag>
          },
          {
            title: '状态',
            dataIndex: 'active',
            width: 90,
            render: (_, item) =>
              item.revokedAt ? <Tag>已吊销</Tag> : item.active ? <Tag color="green">生效中</Tag> : <Tag>已过期</Tag>
          },
          { title: '过期时间', dataIndex: 'expiresAt', width: 150, render: (value: string) => formatDateTime(value) },
          {
            title: '最近使用',
            dataIndex: 'lastUsedAt',
            width: 150,
            render: (_, item) => item.lastUsedAt ? formatDateTime(item.lastUsedAt) : '未使用'
          },
          {
            title: '操作',
            key: 'actions',
            width: 80,
            fixed: 'right',
            render: (_, item) => (
              <Popconfirm
                title={`吊销 ${item.username || item.userId} 的令牌「${item.name}」？`}
                okText="吊销"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => void revokeToken(item)}
              >
                <Button type="link" size="small" danger disabled={Boolean(item.revokedAt)}>吊销</Button>
              </Popconfirm>
            )
          }
        ]}
      />
      <Modal
        title={editing === 'new' ? '新建用户' : `编辑用户：${editing?.username || ''}`}
        open={Boolean(editing)}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onOk={() => void save()}
        onCancel={() => setEditing(undefined)}
        destroyOnHidden
      >
        {editing !== 'new' && editing?.provider !== 'LOCAL' && (
          <Alert type="info" showIcon title="SSO 账号资料和角色由身份平台声明同步；这里可以停用账号。" />
        )}
        <Form form={form} layout="vertical" requiredMark={false}>
          <Form.Item name="username" label="用户名" rules={editing !== 'new' && editing?.provider !== 'LOCAL' ? [] : [{ required: true }, { pattern: /^[A-Za-z0-9._-]+$/, message: '仅支持字母、数字、点、下划线和连字符' }]}>
            <Input autoComplete="off" disabled={editing !== 'new' && (editing?.username === currentUsername || editing?.provider !== 'LOCAL')} />
          </Form.Item>
          <Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}><Input disabled={editing !== 'new' && editing?.provider !== 'LOCAL'} /></Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: true }]}>
            <Select disabled={editing !== 'new' && (editing?.username === currentUsername || editing?.provider !== 'LOCAL')} options={[
              { value: 'ADMIN', label: '管理员：可管理用户及全部功能' },
              { value: 'OPERATOR', label: '操作员：可使用数据库功能，不能管理用户' }
            ]} />
          </Form.Item>
          {(editing === 'new' || editing?.provider === 'LOCAL') && <Form.Item name="password" label={editing === 'new' ? '初始密码' : '重置密码（留空则不修改）'}
            rules={editing === 'new' ? [{ required: true }, { min: 12, message: '密码至少需要 12 位' }] : [{ min: 12, message: '密码至少需要 12 位' }]}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>}
          <Form.Item name="enabled" label="账号状态" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" disabled={editing !== 'new' && editing?.username === currentUsername} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
