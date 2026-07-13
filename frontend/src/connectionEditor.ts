import { EMPTY_FORM, PASSWORD_MASK } from './constants';
import type { Connection, ConnectionForm } from './types';
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

export const CLOSED_CONNECTION_EDITOR: ConnectionEditorState = { mode: 'closed' };

function cloneForm(form: ConnectionForm): ConnectionForm {
  return { ...form };
}

function formFromConnection(connection: Connection, password: string): ConnectionForm {
  return {
    name: connection.name,
    dbType: connection.dbType,
    jdbcUrl: connection.jdbcUrl,
    username: connection.username || '',
    password,
    environment: normalizeEnvironment(connection.environment),
    readonly: connection.readonly
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

export function buildConnectionSaveRequest(state: ConnectionEditorState): ConnectionSaveRequest {
  if (state.mode === 'closed') throw new Error('连接编辑器未打开');
  return state.mode === 'edit'
    ? { path: `/connections/${state.connectionId}`, method: 'PUT', body: state.form }
    : { path: '/connections', method: 'POST', body: state.form };
}
