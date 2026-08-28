import { Alert, Button, Checkbox, Collapse, Form, Input, InputNumber, Select, Space, Tooltip, Typography } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { DB_TYPE_OPTIONS, ENVIRONMENT_OPTIONS, SSH_AUTH_MODE_OPTIONS } from '../constants';
import type { ConnectionForm, ConnectionSshAuthMode, ConnectionSshForm } from '../types';
import { normalizeEnvironment } from '../utils';

const { Text } = Typography;

export function ConnectionFormPanel({ form, editing, loading, onChange, onDbTypeChange, onCancel, onTest, onSave }: {
  form: ConnectionForm;
  editing: boolean;
  loading: boolean;
  onChange: (form: ConnectionForm) => void;
  onDbTypeChange: (dbType: string) => void;
  onCancel: () => void;
  onTest: () => void;
  onSave: () => void;
}) {
  const [touched, setTouched] = useState({ name: false, jdbcUrl: false });
  const nameInvalid = form.name.trim().length === 0;
  const jdbcUrlInvalid = form.jdbcUrl.trim().length === 0 || !form.jdbcUrl.trim().startsWith('jdbc:');
  const ssh = form.ssh;
  const sshInvalid = ssh.enabled
    && (ssh.host.trim().length === 0
      || ssh.username.trim().length === 0
      || (!ssh.skipHostKeyCheck && ssh.serverFingerprint.trim().length === 0)
      || (ssh.authMode === 'PRIVATE_KEY' && ssh.privateKey.trim().length === 0));
  const canSubmit = !nameInvalid && !jdbcUrlInvalid && !sshInvalid && !loading;

  const updateSsh = (patch: Partial<ConnectionSshForm>) => onChange({ ...form, ssh: { ...ssh, ...patch } });

  return (
    <section className="connection-editor-form">
      <Form layout="vertical" size="small" className="compact-form" disabled={loading}>
        <Form.Item label="连接名称" required validateStatus={touched.name && nameInvalid ? 'error' : undefined} help={touched.name && nameInvalid ? '请输入便于识别的连接名称' : undefined}>
          <Input value={form.name} maxLength={80} placeholder="例如：生产只读库" onBlur={() => setTouched((current) => ({ ...current, name: true }))} onChange={(event) => onChange({ ...form, name: event.target.value })} />
        </Form.Item>
        <Form.Item label="数据库类型">
          <Select value={form.dbType} options={DB_TYPE_OPTIONS.map(({ value, label }) => ({ value, label }))} onChange={onDbTypeChange} />
        </Form.Item>
        <Form.Item label="数据库地址" required validateStatus={touched.jdbcUrl && jdbcUrlInvalid ? 'error' : undefined} help={touched.jdbcUrl && jdbcUrlInvalid ? '请输入以 jdbc: 开头的数据库地址' : undefined}>
          <Input value={form.jdbcUrl} placeholder="jdbc:数据库类型://主机:端口/数据库" onBlur={() => setTouched((current) => ({ ...current, jdbcUrl: true }))} onChange={(event) => onChange({ ...form, jdbcUrl: event.target.value })} />
        </Form.Item>
        <Form.Item label="用户名">
          <Input value={form.username} onChange={(event) => onChange({ ...form, username: event.target.value })} />
        </Form.Item>
        <Form.Item label="密码">
          <Input.Password value={form.password} onChange={(event) => onChange({ ...form, password: event.target.value })} />
        </Form.Item>
        <Form.Item label="环境">
          <Select value={normalizeEnvironment(form.environment)} options={ENVIRONMENT_OPTIONS} onChange={(value) => onChange({ ...form, environment: value })} />
        </Form.Item>
        <Form.Item>
          <Checkbox checked={form.readonly} onChange={(event) => onChange({ ...form, readonly: event.target.checked })}>只读连接</Checkbox>
        </Form.Item>
        {/*
          档案字段默认折叠：新建一条连接只需要地址和账号，把分组、标签、会话初始化一并铺开
          会让最常走的路径变长。
        */}
        <Collapse
          ghost
          size="small"
          className="connection-profile-collapse"
          items={[{
            key: 'ssh',
            label: ssh.enabled ? 'SSH 隧道（已启用）' : 'SSH 隧道（可选）',
            children: (
              <>
                <Form.Item help="目标库只对跳板机开放时，由后端先建 SSH 隧道，再把数据库地址指向本地转发端口。">
                  <Checkbox checked={ssh.enabled} onChange={(event) => updateSsh({ enabled: event.target.checked })}>
                    通过 SSH 跳板机连接
                  </Checkbox>
                </Form.Item>
                {ssh.enabled && (
                  <>
                    <Form.Item label="跳板机地址" required validateStatus={ssh.host.trim() ? undefined : 'error'}>
                      <Input value={ssh.host} maxLength={500} placeholder="bastion.example.com" onChange={(event) => updateSsh({ host: event.target.value })} />
                    </Form.Item>
                    <Form.Item label="跳板机端口">
                      <InputNumber value={ssh.port} min={1} max={65535} style={{ width: '100%' }} onChange={(value) => updateSsh({ port: value ?? 22 })} />
                    </Form.Item>
                    <Form.Item label="登录用户名" required validateStatus={ssh.username.trim() ? undefined : 'error'}>
                      <Input value={ssh.username} maxLength={240} onChange={(event) => updateSsh({ username: event.target.value })} />
                    </Form.Item>
                    <Form.Item label="认证方式">
                      <Select
                        value={ssh.authMode}
                        options={SSH_AUTH_MODE_OPTIONS}
                        onChange={(value) => updateSsh({ authMode: value as ConnectionSshAuthMode })}
                      />
                    </Form.Item>
                    {ssh.authMode === 'PASSWORD' ? (
                      <Form.Item label="登录口令">
                        <Input.Password value={ssh.password} onChange={(event) => updateSsh({ password: event.target.value })} />
                      </Form.Item>
                    ) : (
                      <>
                        <Form.Item label="私钥" required validateStatus={ssh.privateKey.trim() ? undefined : 'error'} help="粘贴完整的 PEM / OpenSSH 私钥内容">
                          <Input.TextArea
                            value={ssh.privateKey}
                            rows={4}
                            placeholder={'-----BEGIN OPENSSH PRIVATE KEY-----\n...'}
                            onChange={(event) => updateSsh({ privateKey: event.target.value })}
                          />
                        </Form.Item>
                        <Form.Item label="私钥口令">
                          <Input.Password value={ssh.passphrase} onChange={(event) => updateSsh({ passphrase: event.target.value })} />
                        </Form.Item>
                      </>
                    )}
                    <Form.Item
                      label={<Tooltip title="用 ssh-keyscan 或 ssh-keygen -lf 取得，例如 SHA256:xxxx">跳板机主机指纹</Tooltip>}
                      required={!ssh.skipHostKeyCheck}
                      validateStatus={!ssh.skipHostKeyCheck && !ssh.serverFingerprint.trim() ? 'error' : undefined}
                    >
                      <Input
                        value={ssh.serverFingerprint}
                        maxLength={200}
                        placeholder="SHA256:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                        disabled={ssh.skipHostKeyCheck}
                        onChange={(event) => updateSsh({ serverFingerprint: event.target.value })}
                      />
                    </Form.Item>
                    <Form.Item>
                      <Checkbox checked={ssh.skipHostKeyCheck} onChange={(event) => updateSsh({ skipHostKeyCheck: event.target.checked })}>
                        跳过主机密钥校验
                      </Checkbox>
                    </Form.Item>
                    {ssh.skipHostKeyCheck && (
                      <Alert
                        type="warning"
                        showIcon
                        className="form-hint-text"
                        message="不校验主机密钥意味着无法识别中间人，仅建议在可信内网临时使用。"
                      />
                    )}
                    {editing && <Text type="secondary" className="form-hint-text">****** 表示沿用已保存的口令或私钥；清空后保存会删除它。</Text>}
                  </>
                )}
              </>
            )
          }, {
            key: 'profile',
            label: '连接档案与会话设置（可选）',
            children: (
              <>
                <Form.Item label="分组">
                  <Input value={form.groupName} maxLength={120} placeholder="例如：订单业务" onChange={(event) => onChange({ ...form, groupName: event.target.value })} />
                </Form.Item>
                <Form.Item label="标签" help="逗号分隔，用于在连接列表里按标签筛选">
                  <Input value={form.tags} maxLength={500} placeholder="核心,只读" onChange={(event) => onChange({ ...form, tags: event.target.value })} />
                </Form.Item>
                <Form.Item label={<Tooltip title="打开这条连接时资源树默认停在这里；填了服务端不存在的名字会自动退回登录默认库。">默认 Schema / 数据库</Tooltip>}>
                  <Input value={form.defaultSchema} maxLength={240} placeholder="留空则使用登录账号的默认库" onChange={(event) => onChange({ ...form, defaultSchema: event.target.value })} />
                </Form.Item>
                <Form.Item
                  label="会话初始化 SQL"
                  help="每建立一条数据库会话时执行，多条用分号分隔。只允许会话级设置语句（SET / USE / RESET / ALTER SESSION 等）。"
                >
                  <Input.TextArea
                    value={form.initSql}
                    rows={3}
                    maxLength={4000}
                    placeholder={"SET SESSION sql_mode='STRICT_TRANS_TABLES';\nSET time_zone = '+08:00';"}
                    onChange={(event) => onChange({ ...form, initSql: event.target.value })}
                  />
                </Form.Item>
                <Form.Item label="备注">
                  <Input.TextArea value={form.description} rows={2} maxLength={1000} placeholder="负责人、可维护时间窗、注意事项…" onChange={(event) => onChange({ ...form, description: event.target.value })} />
                </Form.Item>
              </>
            )
          }]}
        />
        {form.dbType === 'oracle' && (
          <Text type="secondary" className="form-hint-text">
            Oracle 示例：Service Name 使用 jdbc:oracle:thin:@//localhost:1521/ORCLPDB1；SID 使用 jdbc:oracle:thin:@localhost:1521:ORCL。
          </Text>
        )}
        {form.dbType === 'dm' && (
          <Text type="secondary" className="form-hint-text">
            达梦示例：jdbc:dm://localhost:5236；Schema 通常与登录用户名一致。
          </Text>
        )}
        {(form.dbType === 'oceanbase-mysql' || form.dbType === 'oceanbase-oracle') && (
          <Text type="secondary" className="form-hint-text">
            OceanBase 示例：jdbc:oceanbase://localhost:2881/demo；连接类型必须与租户兼容模式一致。
          </Text>
        )}
        {editing && <Text type="secondary" className="form-hint-text">编辑已有连接时，****** 表示沿用原密码；清空后保存会删除已保存密码。</Text>}
        <Space className="form-actions" size={8}>
          <Button block onClick={onCancel} disabled={loading}>取消</Button>
          <Button block onClick={onTest} loading={loading} disabled={jdbcUrlInvalid}>测试连接</Button>
          <Button block type="primary" icon={<SaveOutlined />} onClick={onSave} loading={loading} disabled={!canSubmit}>{editing ? '保存修改' : '保存连接'}</Button>
        </Space>
      </Form>
    </section>
  );
}
