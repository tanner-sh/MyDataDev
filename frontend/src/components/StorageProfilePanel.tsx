import { useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Divider,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  message
} from 'antd';
import { ApiOutlined, DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '../api';
import type { StorageProfile, StorageProfileRequest, StorageTestResponse, StorageType } from '../types';
import { formatHistoryTime } from '../utils';

const { Text } = Typography;
const SECRET_MASK = '******';

type StorageProfileFormValues = Omit<StorageProfileRequest, 'nfsGroups'> & { nfsGroupsText?: string };

type StorageProfilePanelProps = {
  profiles: StorageProfile[];
  loading: boolean;
  onReload: () => Promise<void>;
};

export function StorageProfilePanel({ profiles, loading, onReload }: StorageProfilePanelProps) {
  const [form] = Form.useForm<StorageProfileFormValues>();
  const [toast, holder] = message.useMessage();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<StorageProfile | null>(null);
  const [saving, setSaving] = useState(false);
  const [testingId, setTestingId] = useState<number | 'draft' | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const type = Form.useWatch('type', form) || 'SFTP';
  const sftpAuthMode = Form.useWatch('sftpAuthMode', form) || 'PASSWORD';
  const skipServerVerification = Form.useWatch('skipServerVerification', form);
  const ftpTlsMode = Form.useWatch('ftpTlsMode', form) || 'NONE';

  function openCreate() {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue(emptyProfile());
    setEditorOpen(true);
  }

  function openEdit(profile: StorageProfile) {
    setEditing(profile);
    form.resetFields();
    form.setFieldsValue({
      name: profile.name,
      type: profile.type,
      host: profile.host,
      port: profile.port,
      basePath: profile.basePath || '',
      username: profile.username || '',
      password: profile.passwordConfigured ? SECRET_MASK : undefined,
      smbShare: profile.smbShare || '',
      smbDomain: profile.smbDomain || '',
      nfsExportPath: profile.nfsExportPath || '',
      nfsUid: profile.nfsUid,
      nfsGid: profile.nfsGid,
      nfsGroupsText: profile.nfsGroups?.join(', ') || '',
      ftpTlsMode: profile.ftpTlsMode || 'NONE',
      sftpAuthMode: profile.sftpAuthMode || 'PASSWORD',
      privateKey: profile.privateKeyConfigured ? SECRET_MASK : undefined,
      privateKeyPassphrase: profile.privateKeyPassphraseConfigured ? SECRET_MASK : undefined,
      serverFingerprint: profile.serverFingerprint || '',
      skipServerVerification: profile.skipServerVerification,
      enabled: profile.enabled
    });
    setEditorOpen(true);
  }

  function payload(values: StorageProfileFormValues): StorageProfileRequest {
    const { nfsGroupsText, ...request } = values;
    const groups = (nfsGroupsText || '').split(/[,，\s]+/).map((value) => value.trim()).filter(Boolean).map(Number);
    return {
      ...request,
      name: values.name.trim(),
      host: values.host.trim(),
      basePath: values.basePath?.trim(),
      username: values.username?.trim(),
      smbShare: values.smbShare?.trim(),
      smbDomain: values.smbDomain?.trim(),
      nfsExportPath: values.nfsExportPath?.trim(),
      nfsGroups: groups,
      serverFingerprint: values.serverFingerprint?.trim()
    };
  }

  async function save(values: StorageProfileFormValues) {
    setSaving(true);
    try {
      await api<StorageProfile>(editing ? `/storage-profiles/${editing.id}` : '/storage-profiles', {
        method: editing ? 'PUT' : 'POST',
        body: JSON.stringify(payload(values))
      });
      toast.success(editing ? '文件服务配置已更新' : '文件服务配置已创建');
      setEditorOpen(false);
      await onReload();
    } catch (error) {
      toast.error(`保存失败：${(error as Error).message}`);
    } finally {
      setSaving(false);
    }
  }

  async function testDraftOrSaved() {
    setTestingId(editing?.id || 'draft');
    try {
      let result: StorageTestResponse;
      if (editing) {
        result = await api<StorageTestResponse>(`/storage-profiles/${editing.id}/test`, { method: 'POST' });
      } else {
        const values = await form.validateFields();
        result = await api<StorageTestResponse>('/storage-profiles/test', { method: 'POST', body: JSON.stringify(payload(values)) });
      }
      toast.success(result.message);
      await onReload();
    } catch (error) {
      toast.error(`测试失败：${(error as Error).message}`);
      if (editing) await onReload();
    } finally {
      setTestingId(null);
    }
  }

  async function testSaved(profile: StorageProfile) {
    setTestingId(profile.id);
    try {
      const result = await api<StorageTestResponse>(`/storage-profiles/${profile.id}/test`, { method: 'POST' });
      toast.success(result.message);
    } catch (error) {
      toast.error(`测试失败：${(error as Error).message}`);
    } finally {
      setTestingId(null);
      await onReload();
    }
  }

  async function remove(profile: StorageProfile) {
    setDeletingId(profile.id);
    try {
      await api(`/storage-profiles/${profile.id}`, { method: 'DELETE' });
      toast.success('文件服务配置已删除');
      await onReload();
    } catch (error) {
      toast.error(`删除失败：${(error as Error).message}`);
    } finally {
      setDeletingId(null);
    }
  }

  return <section className="inspector-section storage-profile-panel">
    {holder}
    <div className="inspector-section-header">
      <Space>
        <Button size="small" type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建文件服务</Button>
        <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={() => void onReload()}>刷新</Button>
      </Space>
    </div>
    <Alert type="info" showIcon title="备份文件可保存到 SMB、NFSv3、FTP/显式 FTPS 或 SFTP" description="连接密钥会加密保存。新建配置默认启用服务端身份校验；远端目录使用临时文件上传并原子改名。" />
    <Spin spinning={loading}>
      {profiles.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无文件服务配置" /> : <div className="backup-task-list storage-profile-list">
        {profiles.map((profile) => <article className="backup-task-item" key={profile.id}>
          <div className="backup-task-content">
            <Space size={5} wrap>
              <span className="backup-task-title">{profile.name}</span>
              <Tag color="blue">{profile.type}</Tag>
              <Tag color={profile.enabled ? 'green' : 'default'}>{profile.enabled ? '已启用' : '已停用'}</Tag>
              {profile.lastTestStatus && <Tag color={profile.lastTestStatus === 'SUCCESS' ? 'green' : 'red'}>{profile.lastTestStatus === 'SUCCESS' ? '测试通过' : '测试失败'}</Tag>}
            </Space>
            <Text type="secondary">{profile.host}:{profile.port}{storageLocation(profile)}</Text>
            <Text type="secondary">任务引用 {profile.taskReferences} · 历史引用 {profile.historyReferences}{profile.lastTestedAt ? ` · 最近测试 ${formatHistoryTime(profile.lastTestedAt)}` : ''}</Text>
            {profile.lastTestMessage && <Text type="secondary" className="backup-history-message">{profile.lastTestMessage}</Text>}
          </div>
          <Space size={2} className="backup-task-actions">
            <Button size="small" type="text" icon={<ApiOutlined />} loading={testingId === profile.id} disabled={testingId !== null || deletingId !== null} onClick={() => void testSaved(profile)}>测试</Button>
            <Button size="small" type="text" icon={<EditOutlined />} disabled={testingId !== null || deletingId !== null} onClick={() => openEdit(profile)}>编辑</Button>
            <Popconfirm title="删除文件服务配置？" description="只有未被任务和历史引用的配置才能删除。" okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => remove(profile)}>
              <Button size="small" type="text" danger icon={<DeleteOutlined />} loading={deletingId === profile.id} disabled={profile.taskReferences > 0 || profile.historyReferences > 0 || deletingId !== null}>删除</Button>
            </Popconfirm>
          </Space>
        </article>)}
      </div>}
    </Spin>

    <Modal title={editing ? '编辑文件服务' : '新建文件服务'} open={editorOpen} width={680} forceRender confirmLoading={saving}
      okText="保存" cancelText="取消" onOk={() => form.submit()} onCancel={() => !saving && setEditorOpen(false)}
      footer={(_, { OkBtn, CancelBtn }) => <Space><Button icon={<ApiOutlined />} loading={testingId !== null} disabled={saving} onClick={() => void testDraftOrSaved()}>{editing ? '测试已保存配置' : '测试连接'}</Button><CancelBtn /><OkBtn /></Space>}>
      <Form form={form} layout="vertical" size="small" disabled={saving} onFinish={save} initialValues={emptyProfile()}>
        <Divider titlePlacement="start" plain>连接信息</Divider>
        <Space size={12} align="start" className="full-width storage-profile-form-row">
          <Form.Item label="配置名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入配置名称' }]}><Input /></Form.Item>
          <Form.Item label="协议" name="type" rules={[{ required: true }]}><Select disabled={Boolean(editing)} options={(['SMB', 'NFS', 'FTP', 'SFTP'] as StorageType[]).map((value) => ({ value, label: value }))}
            onChange={(value: StorageType) => form.setFieldValue('port', defaultPort(value))} /></Form.Item>
        </Space>
        <Space size={12} align="start" className="full-width storage-profile-form-row">
          <Form.Item label="主机" name="host" rules={[{ required: true, whitespace: true, message: '请输入主机名或 IP' }]}><Input placeholder="files.example.internal" /></Form.Item>
          <Form.Item label="端口" name="port" rules={[{ required: true, message: '请输入端口' }]}><InputNumber min={1} max={65535} disabled={type === 'NFS'} /></Form.Item>
        </Space>
        <Form.Item label="备份基础目录" name="basePath" extra="相对于共享目录、NFS export 或登录目录；留空表示根目录。"><Input placeholder="db-backups" /></Form.Item>

        {type !== 'NFS' && <>
          <Form.Item label="用户名" name="username" rules={[{ required: true, whitespace: true, message: '请输入用户名' }]}><Input autoComplete="off" /></Form.Item>
          {(type !== 'SFTP' || sftpAuthMode === 'PASSWORD') && <Form.Item label="密码" name="password" rules={type === 'SFTP' && !editing ? [{ required: true, message: '请输入密码' }] : []} extra={editing ? '保留 ****** 表示不修改；输入新值会覆盖原密码。' : undefined}><Input.Password autoComplete="new-password" /></Form.Item>}
        </>}

        {type === 'SMB' && <>
          <Form.Item label="共享名" name="smbShare" rules={[{ required: true, whitespace: true, message: '请输入 SMB 共享名' }]}><Input placeholder="backups" /></Form.Item>
          <Form.Item label="域" name="smbDomain"><Input placeholder="可选，例如 CORP" /></Form.Item>
          <Alert type="info" showIcon title="仅支持 SMB 2/3，不启用 SMB 1。" />
        </>}
        {type === 'NFS' && <>
          <Form.Item label="Export 路径" name="nfsExportPath" rules={[{ required: true, whitespace: true, message: '请输入 NFS export 路径' }]}><Input placeholder="/exports/backups" /></Form.Item>
          <Space size={12} align="start" className="full-width storage-profile-form-row">
            <Form.Item label="UID" name="nfsUid" rules={[{ required: true, message: '请输入 UID' }]}><InputNumber min={0} /></Form.Item>
            <Form.Item label="GID" name="nfsGid" rules={[{ required: true, message: '请输入 GID' }]}><InputNumber min={0} /></Form.Item>
          </Space>
          <Form.Item label="附加组" name="nfsGroupsText" rules={[{ pattern: /^\s*(\d+([,，\s]+\d+)*)?\s*$/, message: '请输入逗号或空格分隔的数字组 ID' }]}><Input placeholder="100, 101" /></Form.Item>
          <Alert type="warning" showIcon title="当前为 NFSv3 AUTH_SYS" description="请确认服务端 export 权限允许这里填写的 UID/GID；暂不支持 NFSv4 或 Kerberos。" />
        </>}
        {type === 'FTP' && <>
          <Form.Item label="TLS 模式" name="ftpTlsMode"><Select options={[{ value: 'NONE', label: 'FTP（无 TLS）' }, { value: 'EXPLICIT', label: '显式 FTPS' }]} /></Form.Item>
          <Alert type="warning" showIcon title={ftpTlsMode === 'NONE' ? '普通 FTP 不加密凭据与文件内容。' : '显式 FTPS 会校验证书与主机名。'} />
          {ftpTlsMode === 'EXPLICIT' && <>
            <Form.Item label="服务端证书 SHA-256 指纹" name="serverFingerprint" extra="可选；留空时使用应用服务器的系统信任库校验证书。"><Input placeholder="SHA256:..." /></Form.Item>
            <Form.Item name="skipServerVerification" valuePropName="checked"><Checkbox>跳过 FTPS 证书与主机名校验（不推荐）</Checkbox></Form.Item>
            {skipServerVerification && <Alert type="warning" showIcon title="跳过 FTPS 服务端身份校验可能遭受中间人攻击。" />}
          </>}
        </>}
        {type === 'SFTP' && <>
          <Form.Item label="认证方式" name="sftpAuthMode"><Select options={[{ value: 'PASSWORD', label: '密码' }, { value: 'PRIVATE_KEY', label: '私钥' }]} /></Form.Item>
          {sftpAuthMode === 'PRIVATE_KEY' && <>
            <Form.Item label="私钥" name="privateKey" rules={!editing ? [{ required: true, whitespace: true, message: '请输入 PEM/OpenSSH 私钥' }] : []} extra={editing ? '保留 ****** 表示不修改。' : undefined}><Input.TextArea rows={5} autoComplete="off" /></Form.Item>
            <Form.Item label="私钥口令" name="privateKeyPassphrase" extra="私钥未加密时留空。"><Input.Password autoComplete="new-password" /></Form.Item>
          </>}
          <Form.Item name="skipServerVerification" valuePropName="checked"><Checkbox>跳过服务端主机密钥校验（不推荐）</Checkbox></Form.Item>
          {!skipServerVerification && <Form.Item label="主机密钥 SHA-256 指纹" name="serverFingerprint" rules={[{ required: true, whitespace: true, message: '请输入服务端主机密钥指纹' }]}><Input placeholder="SHA256:..." /></Form.Item>}
          {skipServerVerification && <Alert type="warning" showIcon title="跳过主机密钥校验可能遭受中间人攻击。" />}
        </>}
        <Form.Item name="enabled" valuePropName="checked"><Checkbox>启用此文件服务供备份任务选择</Checkbox></Form.Item>
      </Form>
    </Modal>
  </section>;
}

function emptyProfile(): StorageProfileFormValues {
  return { name: '', type: 'SFTP', host: '', port: 22, basePath: '', username: '', password: '', ftpTlsMode: 'NONE', sftpAuthMode: 'PASSWORD', skipServerVerification: false, enabled: true };
}

function defaultPort(type: StorageType) {
  if (type === 'SMB') return 445;
  if (type === 'NFS') return 2049;
  if (type === 'SFTP') return 22;
  return 21;
}

function storageLocation(profile: StorageProfile) {
  if (profile.type === 'SMB') return ` / ${profile.smbShare}${profile.basePath ? `/${profile.basePath}` : ''}`;
  if (profile.type === 'NFS') return `${profile.nfsExportPath || ''}${profile.basePath ? `/${profile.basePath}` : ''}`;
  return profile.basePath ? ` / ${profile.basePath}` : '';
}
