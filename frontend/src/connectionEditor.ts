import { EMPTY_FORM, EMPTY_SSH_FORM, PASSWORD_MASK } from './constants';
import type { Connection, ConnectionForm, ConnectionSshForm } from './types';
import { normalizeEnvironment } from './utils';

export type ConnectionEditorState =
  | { mode: 'closed' }
  | { mode: 'create'; origin: 'blank' | 'duplicate'; form: ConnectionForm; baseline: ConnectionForm }
  | { mode: 'edit'; connectionId: number; connectionName: string; form: ConnectionForm; baseline: ConnectionForm };

export type ConnectionSaveRequest = {
  path: string;
  method: 'POST' | 'PUT';
  body: ConnectionForm;
};

export type ConnectionTestRequest = {
  path: string;
  body: Record<string, unknown>;
};

export const CLOSED_CONNECTION_EDITOR: ConnectionEditorState = { mode: 'closed' };

function cloneForm(form: ConnectionForm): ConnectionForm {
  // ssh 是嵌套对象，浅拷贝会让草稿和基线共享同一份，脏检查就永远为 false。
  return { ...form, ssh: { ...form.ssh } };
}

/**
 * 把后端回传的隧道摘要还原成表单。
 *
 * <p>摘要里没有任何密钥，只说「配过没有」：已配过的字段填掩码表示沿用，复制连接时
 * （secret 为空串）则一律留空，逼用户重新输入。</p>
 */
function sshFormFromConnection(connection: Connection, secret: string): ConnectionSshForm {
  const ssh = connection.ssh;
  if (!ssh || !ssh.enabled) return { ...EMPTY_SSH_FORM };
  return {
    enabled: true,
    host: ssh.host || '',
    port: ssh.port || EMPTY_SSH_FORM.port,
    username: ssh.username || '',
    authMode: ssh.authMode || 'PASSWORD',
    password: ssh.hasPassword ? secret : '',
    privateKey: ssh.hasPrivateKey ? secret : '',
    passphrase: ssh.hasPassphrase ? secret : '',
    serverFingerprint: ssh.serverFingerprint || '',
    skipHostKeyCheck: ssh.skipHostKeyCheck
  };
}

function formFromConnection(connection: Connection, password: string): ConnectionForm {
  return {
    name: connection.name,
    dbType: connection.dbType,
    jdbcUrl: connection.jdbcUrl,
    username: connection.username || '',
    password,
    environment: normalizeEnvironment(connection.environment),
    readonly: connection.readonly,
    groupName: connection.groupName || '',
    tags: (connection.tags || []).join(','),
    defaultSchema: connection.defaultSchema || '',
    initSql: connection.initSql || '',
    description: connection.description || '',
    ssh: sshFormFromConnection(connection, password)
  };
}

export function createBlankConnectionEditor(): ConnectionEditorState {
  const form = cloneForm(EMPTY_FORM);
  return { mode: 'create', origin: 'blank', form, baseline: cloneForm(form) };
}

export function createDuplicateConnectionEditor(connection: Connection): ConnectionEditorState {
  const form = {
    ...formFromConnection(connection, ''),
    name: `${connection.name} 副本`
  };
  return { mode: 'create', origin: 'duplicate', form, baseline: cloneForm(form) };
}

export function createEditConnectionEditor(connection: Connection): ConnectionEditorState {
  const form = formFromConnection(connection, PASSWORD_MASK);
  return {
    mode: 'edit',
    connectionId: connection.id,
    connectionName: connection.name,
    form,
    baseline: cloneForm(form)
  };
}

export function updateConnectionEditorForm(state: ConnectionEditorState, form: ConnectionForm): ConnectionEditorState {
  if (state.mode === 'closed') return state;
  return { ...state, form };
}

export function isConnectionEditorDirty(state: ConnectionEditorState): boolean {
  return state.mode !== 'closed' && JSON.stringify(state.form) !== JSON.stringify(state.baseline);
}

/**
 * 「测试连接」提交什么。
 *
 * <p>编辑已有连接时整份草稿发给 {@code /connections/{id}/test}，后端能用它解释掩码；
 * 新建时没有可参照的旧值，只发地址、账号和隧道配置。两条路径都必须带上 ssh —— 否则
 * 「测试通过」说明的只是「不走隧道能连上」。</p>
 */
export function buildConnectionTestRequest(state: ConnectionEditorState): ConnectionTestRequest {
  if (state.mode === 'closed') throw new Error('连接编辑器未打开');
  if (state.mode === 'edit') {
    return { path: `/connections/${state.connectionId}/test`, body: { ...state.form } };
  }
  const { jdbcUrl, username, password, ssh } = state.form;
  return { path: '/connections/test', body: { jdbcUrl, username, password, ssh } };
}

export function buildConnectionSaveRequest(state: ConnectionEditorState): ConnectionSaveRequest {
  if (state.mode === 'closed') throw new Error('连接编辑器未打开');
  return state.mode === 'edit'
    ? { path: `/connections/${state.connectionId}`, method: 'PUT', body: state.form }
    : { path: '/connections', method: 'POST', body: state.form };
}
