import { describe, expect, it } from 'vitest';
import {
  buildConnectionSaveRequest,
  buildConnectionTestRequest,
  CLOSED_CONNECTION_EDITOR,
  createBlankConnectionEditor,
  createDuplicateConnectionEditor,
  createEditConnectionEditor,
  isConnectionEditorDirty,
  updateConnectionEditorForm
} from './connectionEditor';
import type { Connection } from './types';

const connection: Connection = {
  id: 42,
  name: '清算旗舰版',
  dbType: 'mysql',
  jdbcUrl: 'jdbc:mysql://localhost:3306/demo',
  username: 'demo',
  environment: 'dev',
  readonly: false,
  capabilities: {
    tableBrowse: true,
    tableEdit: true,
    tableDesign: true,
    explain: true,
    nativeBackupMethods: ['MYSQLDUMP']
  }
};

const tunnelled: Connection = {
  ...connection,
  id: 43,
  ssh: {
    enabled: true,
    host: 'bastion.example.com',
    port: 2222,
    username: 'ops',
    authMode: 'PRIVATE_KEY',
    hasPassword: false,
    hasPrivateKey: true,
    hasPassphrase: false,
    serverFingerprint: 'SHA256:abc',
    skipHostKeyCheck: false
  }
};

describe('connection editor', () => {
  it('新建和复制始终生成 POST 请求', () => {
    expect(buildConnectionSaveRequest(createBlankConnectionEditor())).toMatchObject({
      path: '/connections',
      method: 'POST'
    });
    expect(buildConnectionSaveRequest(createDuplicateConnectionEditor(connection))).toMatchObject({
      path: '/connections',
      method: 'POST',
      body: { name: '清算旗舰版 副本', password: '' }
    });
  });

  it('只有编辑模式生成带目标 ID 的 PUT 请求', () => {
    expect(buildConnectionSaveRequest(createEditConnectionEditor(connection))).toMatchObject({
      path: '/connections/42',
      method: 'PUT',
      body: { name: '清算旗舰版', password: '******' }
    });
  });

  it('编辑带隧道的连接时用掩码代表已保存的密钥', () => {
    const editor = createEditConnectionEditor(tunnelled);
    if (editor.mode !== 'edit') throw new Error('unexpected editor mode');
    expect(editor.form.ssh).toMatchObject({
      enabled: true,
      host: 'bastion.example.com',
      port: 2222,
      authMode: 'PRIVATE_KEY',
      privateKey: '******',
      password: '',
      passphrase: ''
    });
  });

  it('复制连接不携带任何隧道密钥', () => {
    const editor = createDuplicateConnectionEditor(tunnelled);
    if (editor.mode !== 'create') throw new Error('unexpected editor mode');
    expect(editor.form.ssh.enabled).toBe(true);
    expect(editor.form.ssh.privateKey).toBe('');
  });

  it('隧道字段的改动会被脏检查识别', () => {
    const editor = createEditConnectionEditor(tunnelled);
    if (editor.mode === 'closed') throw new Error('unexpected closed editor');
    const changed = updateConnectionEditorForm(editor, { ...editor.form, ssh: { ...editor.form.ssh, port: 2022 } });
    expect(isConnectionEditorDirty(changed)).toBe(true);
    // 基线必须是深拷贝，否则改了草稿基线也跟着变，脏检查恒为 false。
    expect(isConnectionEditorDirty(editor)).toBe(false);
  });

  it('测试连接始终带上隧道配置', () => {
    expect(buildConnectionTestRequest(createEditConnectionEditor(tunnelled))).toMatchObject({
      path: '/connections/43/test',
      body: { ssh: { enabled: true, host: 'bastion.example.com' } }
    });
    expect(buildConnectionTestRequest(createBlankConnectionEditor())).toMatchObject({
      path: '/connections/test',
      body: { ssh: { enabled: false } }
    });
    expect(() => buildConnectionTestRequest(CLOSED_CONNECTION_EDITOR)).toThrow('连接编辑器未打开');
  });

  it('能识别草稿变更并且关闭状态不可保存', () => {
    const editor = createBlankConnectionEditor();
    expect(isConnectionEditorDirty(editor)).toBe(false);
    if (editor.mode === 'closed') throw new Error('unexpected closed editor');
    const changed = updateConnectionEditorForm(editor, { ...editor.form, name: '新连接' });
    expect(isConnectionEditorDirty(changed)).toBe(true);
    expect(() => buildConnectionSaveRequest(CLOSED_CONNECTION_EDITOR)).toThrow('连接编辑器未打开');
  });
});
