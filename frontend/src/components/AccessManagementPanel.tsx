import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Popconfirm, Radio, Select, Space, Table, Tabs, Typography, message } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { api } from '../api';
import type { Connection } from '../types';
import type { WebUser } from '../users';
import {
  CONNECTION_PERMISSIONS,
  CONNECTION_PERMISSION_LABELS,
  type ConnectionAccessPolicy,
  type ConnectionGrant,
  type PermissionTemplate,
  type UserGroup
} from '../accessControl';

const { Text } = Typography;
type GroupFields = { name: string; description?: string; memberUserIds: number[] };

export function AccessManagementPanel({ connections }: { connections: Connection[] }) {
  const [groups, setGroups] = useState<UserGroup[]>([]);
  const [users, setUsers] = useState<WebUser[]>([]);
  const [templates, setTemplates] = useState<PermissionTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editingGroup, setEditingGroup] = useState<UserGroup | 'new'>();
  const [groupForm] = Form.useForm<GroupFields>();
  const [selectedConnectionId, setSelectedConnectionId] = useState<number>();
  const [policy, setPolicy] = useState<ConnectionAccessPolicy>();
  const [policyLoading, setPolicyLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [messageApi, messageContext] = message.useMessage();

  const enabledUsers = useMemo(() => users.filter((user) => user.enabled), [users]);
  const principalOptions = useMemo(() => [
    ...enabledUsers.map((user) => ({ value: `USER:${user.id}`, label: `用户 · ${user.displayName} (${user.username})` })),
    ...groups.map((group) => ({ value: `GROUP:${group.id}`, label: `用户组 · ${group.name}` }))
  ], [enabledUsers, groups]);

  async function loadBase() {
    setLoading(true);
    setError('');
    try {
      const [nextGroups, nextUsers, nextTemplates] = await Promise.all([
        api<UserGroup[]>('/admin/access/groups'),
        api<WebUser[]>('/admin/users'),
        api<PermissionTemplate[]>('/admin/access/templates')
      ]);
      setGroups(nextGroups);
      setUsers(nextUsers);
      setTemplates(nextTemplates);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '访问控制数据加载失败');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void loadBase(); }, []);
  useEffect(() => {
    if (selectedConnectionId == null && connections.length > 0) setSelectedConnectionId(connections[0].id);
  }, [connections, selectedConnectionId]);
  useEffect(() => {
    if (selectedConnectionId == null) return;
    setPolicyLoading(true);
    api<ConnectionAccessPolicy>(`/admin/access/connections/${selectedConnectionId}`)
      .then(setPolicy)
      .catch((cause) => setError(cause instanceof Error ? cause.message : '连接权限加载失败'))
      .finally(() => setPolicyLoading(false));
  }, [selectedConnectionId]);

  function openGroup(group: UserGroup | 'new') {
    setEditingGroup(group);
    groupForm.setFieldsValue(group === 'new'
      ? { name: '', description: '', memberUserIds: [] }
      : { name: group.name, description: group.description || '', memberUserIds: group.memberUserIds });
  }

  async function saveGroup() {
    const values = await groupForm.validateFields();
    setSaving(true);
    try {
      if (editingGroup === 'new') await api('/admin/access/groups', { method: 'POST', body: JSON.stringify(values) });
      else if (editingGroup) await api(`/admin/access/groups/${editingGroup.id}`, { method: 'PUT', body: JSON.stringify(values) });
      setEditingGroup(undefined);
      messageApi.success('用户组已保存');
      await loadBase();
    } catch (cause) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return;
      messageApi.error(cause instanceof Error ? cause.message : '用户组保存失败');
    } finally {
      setSaving(false);
    }
  }

  async function deleteGroup(group: UserGroup) {
    try {
      await api(`/admin/access/groups/${group.id}`, { method: 'DELETE' });
      messageApi.success('用户组已删除');
      await loadBase();
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '用户组删除失败');
    }
  }

  function updateGrant(index: number, change: Partial<ConnectionGrant>) {
    if (!policy) return;
    setPolicy({ ...policy, grants: policy.grants.map((grant, current) => current === index ? { ...grant, ...change } : grant) });
  }

  async function savePolicy() {
    if (!policy) return;
    setSaving(true);
    try {
      const next = await api<ConnectionAccessPolicy>(`/admin/access/connections/${policy.connectionId}`, {
        method: 'PUT',
        body: JSON.stringify({ accessMode: policy.accessMode, ownerUserId: policy.ownerUserId || null, grants: policy.grants })
      });
      setPolicy(next);
      messageApi.success('连接权限已生效');
    } catch (cause) {
      messageApi.error(cause instanceof Error ? cause.message : '连接权限保存失败');
    } finally {
      setSaving(false);
    }
  }

  const groupTab = (
    <Card size="small" loading={loading} extra={<Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => openGroup('new')}>新建用户组</Button>}>
      <Table<UserGroup> rowKey="id" size="small" pagination={false} dataSource={groups} columns={[
        { title: '用户组', dataIndex: 'name', render: (_, group) => <Space direction="vertical" size={0}><Text strong>{group.name}</Text><Text type="secondary">{group.description || '无备注'}</Text></Space> },
        { title: '成员', width: 110, render: (_, group) => `${group.memberUserIds.length} 人` },
        { title: '操作', width: 140, render: (_, group) => <Space><Button type="link" size="small" onClick={() => openGroup(group)}>编辑</Button><Popconfirm title={`删除用户组 ${group.name}？`} onConfirm={() => void deleteGroup(group)}><Button danger type="link" size="small">删除</Button></Popconfirm></Space> }
      ]} />
    </Card>
  );

  const policyTab = (
    <div className="access-policy-editor">
      <Card size="small" loading={policyLoading}>
        <Space direction="vertical" size={14} className="full-width">
          <Select className="full-width" placeholder="选择连接" value={selectedConnectionId} onChange={setSelectedConnectionId}
            options={connections.map((connection) => ({ value: connection.id, label: connection.name }))} />
          {policy && <>
            <div><Text strong>访问模式</Text><div><Text type="secondary">共享保持升级前的行为；受限模式只允许所有者和显式授权对象访问。</Text></div></div>
            <Radio.Group value={policy.accessMode} onChange={(event) => setPolicy({ ...policy, accessMode: event.target.value })}>
              <Radio value="SHARED">所有操作员共享</Radio><Radio value="RESTRICTED">按用户/用户组授权</Radio>
            </Radio.Group>
            <Select allowClear className="full-width" placeholder="连接所有者（可选）" value={policy.ownerUserId || undefined}
              onChange={(ownerUserId) => setPolicy({ ...policy, ownerUserId })}
              options={enabledUsers.map((user) => ({ value: user.id, label: `${user.displayName} (${user.username})` }))} />
            {policy.accessMode === 'RESTRICTED' && <>
              <Space direction="vertical" className="full-width" size={8}>
                {policy.grants.map((grant, index) => <Card size="small" key={`${grant.granteeType}:${grant.granteeId}:${index}`}>
                  <Space direction="vertical" className="full-width">
                    <Space.Compact block>
                      <Select className="full-width" value={`${grant.granteeType}:${grant.granteeId}`} options={principalOptions} onChange={(value) => {
                        const [granteeType, id] = value.split(':');
                        updateGrant(index, { granteeType: granteeType as 'USER' | 'GROUP', granteeId: Number(id) });
                      }} />
                      <Button danger icon={<DeleteOutlined />} onClick={() => setPolicy({ ...policy, grants: policy.grants.filter((_, current) => current !== index) })} />
                    </Space.Compact>
                    <Select mode="multiple" className="full-width" placeholder="选择权限" value={grant.permissions}
                      options={CONNECTION_PERMISSIONS.map((permission) => ({ value: permission, label: CONNECTION_PERMISSION_LABELS[permission] }))}
                      onChange={(permissions) => updateGrant(index, { permissions })} />
                    <Select
                      allowClear
                      className="full-width"
                      placeholder="从权限模板快速填充"
                      options={templates.map((template) => ({ value: template.key, label: `${template.name} · ${template.description}` }))}
                      onChange={(key) => {
                        const template = templates.find((item) => item.key === key);
                        if (template) updateGrant(index, { permissions: template.permissions });
                      }}
                    />
                  </Space>
                </Card>)}
              </Space>
              <Button disabled={principalOptions.length === 0} onClick={() => {
                const [granteeType, id] = principalOptions[0].value.split(':');
                setPolicy({ ...policy, grants: [...policy.grants, { granteeType: granteeType as 'USER' | 'GROUP', granteeId: Number(id), permissions: ['VIEW_METADATA', 'QUERY'] }] });
              }} icon={<PlusOutlined />}>添加授权</Button>
            </>}
            <Button type="primary" loading={saving} onClick={() => void savePolicy()}>保存连接权限</Button>
          </>}
        </Space>
      </Card>
    </div>
  );

  return <div className="management-section">
    {messageContext}
    <header className="management-section-header"><div><Text strong>访问控制</Text><div><Text type="secondary">用户组用于批量授权，所有权限均由后端强制执行。</Text></div></div></header>
    {error && <Alert type="error" showIcon message={error} closable onClose={() => setError('')} />}
    <Tabs items={[{ key: 'groups', label: '用户组', children: groupTab }, { key: 'connections', label: '连接权限', children: policyTab }]} />
    <Modal open={Boolean(editingGroup)} title={editingGroup === 'new' ? '新建用户组' : '编辑用户组'} okText="保存" cancelText="取消" confirmLoading={saving}
      onOk={() => void saveGroup()} onCancel={() => setEditingGroup(undefined)} destroyOnHidden>
      <Form form={groupForm} layout="vertical"><Form.Item name="name" label="名称" rules={[{ required: true }, { max: 120 }]}><Input /></Form.Item>
        <Form.Item name="description" label="备注"><Input.TextArea rows={2} maxLength={500} /></Form.Item>
        <Form.Item name="memberUserIds" label="成员"><Select mode="multiple" options={enabledUsers.map((user) => ({ value: user.id, label: `${user.displayName} (${user.username})` }))} /></Form.Item>
      </Form>
    </Modal>
  </div>;
}
