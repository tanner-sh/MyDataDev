import { describe, expect, it } from 'vitest';
import {
  buildConnectionSaveRequest,
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

  it('能识别草稿变更并且关闭状态不可保存', () => {
    const editor = createBlankConnectionEditor();
    expect(isConnectionEditorDirty(editor)).toBe(false);
    if (editor.mode === 'closed') throw new Error('unexpected closed editor');
    const changed = updateConnectionEditorForm(editor, { ...editor.form, name: '新连接' });
    expect(isConnectionEditorDirty(changed)).toBe(true);
    expect(() => buildConnectionSaveRequest(CLOSED_CONNECTION_EDITOR)).toThrow('连接编辑器未打开');
  });
});
