import { Alert, Button, Drawer, Input, Modal, Select, Space, Typography } from 'antd';
import { EyeOutlined, SwapOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api';
import type { Connection, DbObject, ObjectDetail, TableDesignResponse } from '../types';
import { localizeMessage } from '../utils';
import { createTableRequest, fullTableName, tableActionRequest } from '../tableLifecycle';
import {
  newColumnRow,
  TableDefinitionEditor,
  tableDefinitionSignature
} from './TableDefinitionEditor';
import type { DesignColumnRow, DesignIndexRow } from './TableDefinitionEditor';
import { TypedConfirmationFields } from './TypedConfirmationFields';

const { Text, Title } = Typography;

export type TableLifecycleAction = {
  operation: 'RENAME' | 'DROP';
  object: DbObject;
};

export function TableLifecyclePanel({
  createOpen,
  action,
  connection,
  schemas,
  defaultSchema,
  onCloseCreate,
  onCloseAction,
  onCompleted
}: {
  createOpen: boolean;
  action?: TableLifecycleAction;
  connection: Connection | null;
  schemas: string[];
  defaultSchema?: string;
  onCloseCreate: () => void;
  onCloseAction: () => void;
  onCompleted: (operation: 'CREATE' | 'RENAME' | 'DROP', source: DbObject | null, newTableName?: string) => void;
}) {
  const [modalApi, modalContextHolder] = Modal.useModal();
  const [schemaName, setSchemaName] = useState(defaultSchema || '');
  const [tableName, setTableName] = useState('');
  const [columns, setColumns] = useState<DesignColumnRow[]>(() => [newColumnRow()]);
  const [indexes, setIndexes] = useState<DesignIndexRow[]>([]);
  const [primaryKeys, setPrimaryKeys] = useState<string[]>([]);
  const [createMessage, setCreateMessage] = useState('');
  const [createPreview, setCreatePreview] = useState<string[]>([]);
  const [createConfirmOpen, setCreateConfirmOpen] = useState(false);
  const [createConfirmation, setCreateConfirmation] = useState('');
  const [createProductionConfirmation, setCreateProductionConfirmation] = useState('');
  const [createSubmitting, setCreateSubmitting] = useState(false);
  const [createBaseline, setCreateBaseline] = useState('');
  const createRequestIdRef = useRef(0);

  const [actionDetail, setActionDetail] = useState<ObjectDetail | null>(null);
  const [newTableName, setNewTableName] = useState('');
  const [actionMessage, setActionMessage] = useState('');
  const [actionPreview, setActionPreview] = useState<string[]>([]);
  const [actionConfirmation, setActionConfirmation] = useState('');
  const [actionProductionConfirmation, setActionProductionConfirmation] = useState('');
  const [actionSubmitting, setActionSubmitting] = useState(false);
  const actionRequestIdRef = useRef(0);

  const productionConfirmationText = connection?.environment === 'prod' ? connection.name : undefined;
  const createTarget = fullTableName(schemaName || undefined, tableName.trim());
  const createSignature = useMemo(
    () => JSON.stringify({ schemaName, tableName, definition: tableDefinitionSignature(columns, indexes, primaryKeys) }),
    [columns, indexes, primaryKeys, schemaName, tableName]
  );
  const createDirty = createBaseline !== '' && createSignature !== createBaseline;
  const actionSource = action ? fullTableName(action.object.schemaName, action.object.name) : '';
  const canExecuteCreate = createPreview.length > 0
    && createConfirmation === createTarget
    && (!productionConfirmationText || createProductionConfirmation === productionConfirmationText);
  const canExecuteAction = actionPreview.length > 0
    && actionConfirmation === actionSource
    && (!productionConfirmationText || actionProductionConfirmation === productionConfirmationText);

  useEffect(() => {
    if (!createOpen) return;
    const nextColumns = [newColumnRow()];
    const nextSchema = defaultSchema || schemas[0] || '';
    setSchemaName(nextSchema);
    setTableName('');
    setColumns(nextColumns);
    setIndexes([]);
    setPrimaryKeys([]);
    setCreateMessage('');
    setCreatePreview([]);
    setCreateConfirmOpen(false);
    setCreateConfirmation('');
    setCreateProductionConfirmation('');
    setCreateSubmitting(false);
    setCreateBaseline(JSON.stringify({ schemaName: nextSchema, tableName: '', definition: tableDefinitionSignature(nextColumns, [], []) }));
  }, [connection?.id, createOpen, defaultSchema]);

  useEffect(() => {
    actionRequestIdRef.current += 1;
    setActionDetail(null);
    setNewTableName('');
    setActionMessage('');
    setActionPreview([]);
    setActionConfirmation('');
    setActionProductionConfirmation('');
    setActionSubmitting(false);
  }, [action?.operation, action?.object.schemaName, action?.object.name, connection?.id]);

  useEffect(() => {
    if (!createOpen) return;
    setCreatePreview([]);
    setCreateConfirmOpen(false);
  }, [createOpen, createSignature]);

  function requestCreateClose() {
    if (!createDirty || createSubmitting) {
      if (!createSubmitting) onCloseCreate();
      return;
    }
    modalApi.confirm({
      title: '放弃新建表配置？',
      content: '尚未执行的字段和索引配置将丢失。',
      okText: '放弃并关闭',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: onCloseCreate
    });
  }

  async function previewCreate() {
    if (!connection) return;
    const requestId = ++createRequestIdRef.current;
    setCreateSubmitting(true);
    setCreateMessage('');
    setCreatePreview([]);
    try {
      const response = await api<TableDesignResponse>(`/metadata/${connection.id}/tables/lifecycle/preview`, {
        method: 'POST',
        body: JSON.stringify(createTableRequest(schemaName || undefined, tableName, columns, indexes, primaryKeys))
      });
      if (requestId !== createRequestIdRef.current) return;
      setCreatePreview(response.sql);
      setCreateMessage(response.message);
      setCreateConfirmOpen(true);
    } catch (error) {
      if (requestId === createRequestIdRef.current) setCreateMessage(localizeMessage((error as Error).message));
    } finally {
      if (requestId === createRequestIdRef.current) setCreateSubmitting(false);
    }
  }

  async function executeCreate() {
    if (!connection || !canExecuteCreate) return;
    const requestId = ++createRequestIdRef.current;
    setCreateSubmitting(true);
    try {
      const response = await api<TableDesignResponse>(`/metadata/${connection.id}/tables/lifecycle/execute`, {
        method: 'POST',
        headers: productionConfirmationText ? { 'X-Production-Confirmation': createProductionConfirmation } : undefined,
        body: JSON.stringify(createTableRequest(schemaName || undefined, tableName, columns, indexes, primaryKeys, createConfirmation))
      });
      if (requestId !== createRequestIdRef.current) return;
      setCreateMessage(response.message);
      setCreateConfirmOpen(false);
      onCloseCreate();
      onCompleted('CREATE', {
        schemaName: schemaName || undefined,
        name: tableName.trim(),
        type: 'TABLE',
        columns: [],
        indexes: []
      }, tableName.trim());
    } catch (error) {
      if (requestId === createRequestIdRef.current) setCreateMessage(localizeMessage((error as Error).message));
    } finally {
      if (requestId === createRequestIdRef.current) setCreateSubmitting(false);
    }
  }

  async function previewAction() {
    if (!connection || !action) return;
    const requestId = ++actionRequestIdRef.current;
    setActionSubmitting(true);
    setActionMessage('');
    setActionPreview([]);
    setActionDetail(null);
    try {
      const params = new URLSearchParams({ objectName: action.object.name, refresh: 'true' });
      if (action.object.schemaName) params.set('schemaName', action.object.schemaName);
      const detail = await api<ObjectDetail>(`/metadata/${connection.id}/objects/detail?${params.toString()}`);
      if (requestId !== actionRequestIdRef.current) return;
      const response = await api<TableDesignResponse>(`/metadata/${connection.id}/tables/lifecycle/preview`, {
        method: 'POST',
        body: JSON.stringify(tableActionRequest(action.operation, detail, newTableName))
      });
      if (requestId !== actionRequestIdRef.current) return;
      setActionDetail(detail);
      setActionPreview(response.sql);
      setActionMessage(response.message);
    } catch (error) {
      if (requestId === actionRequestIdRef.current) setActionMessage(localizeMessage((error as Error).message));
    } finally {
      if (requestId === actionRequestIdRef.current) setActionSubmitting(false);
    }
  }

  async function executeAction() {
    if (!connection || !action || !actionDetail || !canExecuteAction) return;
    const requestId = ++actionRequestIdRef.current;
    setActionSubmitting(true);
    try {
      const response = await api<TableDesignResponse>(`/metadata/${connection.id}/tables/lifecycle/execute`, {
        method: 'POST',
        headers: productionConfirmationText ? { 'X-Production-Confirmation': actionProductionConfirmation } : undefined,
        body: JSON.stringify(tableActionRequest(action.operation, actionDetail, newTableName, actionConfirmation))
      });
      if (requestId !== actionRequestIdRef.current) return;
      setActionMessage(response.message);
      onCloseAction();
      onCompleted(action.operation, action.object, action.operation === 'RENAME' ? newTableName.trim() : undefined);
    } catch (error) {
      if (requestId === actionRequestIdRef.current) setActionMessage(localizeMessage((error as Error).message));
    } finally {
      if (requestId === actionRequestIdRef.current) setActionSubmitting(false);
    }
  }

  return (
    <>
      {modalContextHolder}
      <Drawer
        title="新建表"
        size={960}
        open={createOpen}
        rootClassName="management-drawer table-create-drawer"
        mask={{ closable: !createSubmitting }}
        keyboard={!createSubmitting}
        onClose={requestCreateClose}
        destroyOnHidden
      >
        <div className="table-create-content">
          <div className="table-create-heading">
            <div>
              <Title level={5}>表定义</Title>
              <Text type="secondary">创建前会生成完整 DDL，并要求输入目标表名确认。</Text>
            </div>
            <Space size={8} wrap>
              <label className="table-lifecycle-field">
                <Text type="secondary">Schema / 数据库</Text>
                <Select
                  aria-label="Schema 或数据库"
                  showSearch
                  size="small"
                  value={schemaName || undefined}
                  placeholder="使用当前命名空间"
                  options={schemas.map((schema) => ({ value: schema, label: schema }))}
                  onChange={setSchemaName}
                />
              </label>
              <label className="table-lifecycle-field">
                <Text type="secondary">表名</Text>
                <Input aria-label="表名" size="small" value={tableName} placeholder="例如 APP_USER" onChange={(event) => { setTableName(event.target.value); setCreatePreview([]); }} />
              </label>
            </Space>
          </div>
          {createMessage && <Alert type={createPreview.length > 0 ? 'info' : 'error'} showIcon title={createMessage} />}
          <TableDefinitionEditor
            mode="create"
            columns={columns}
            indexes={indexes}
            primaryKeys={primaryKeys}
            disabled={createSubmitting}
            dirty={createDirty}
            setColumns={setColumns}
            setIndexes={setIndexes}
            setPrimaryKeys={setPrimaryKeys}
          />
          <div className="designer-actions">
            <Button onClick={requestCreateClose} disabled={createSubmitting}>取消</Button>
            <Button type="primary" icon={<EyeOutlined />} loading={createSubmitting} onClick={previewCreate}>预览 DDL</Button>
          </div>
        </div>
      </Drawer>

      <Modal
        title="确认创建表"
        open={createConfirmOpen}
        confirmLoading={createSubmitting}
        mask={{ closable: !createSubmitting }}
        keyboard={!createSubmitting}
        cancelButtonProps={{ disabled: createSubmitting }}
        okButtonProps={{ disabled: !canExecuteCreate }}
        okText="创建表"
        onOk={executeCreate}
        onCancel={() => { if (!createSubmitting) setCreateConfirmOpen(false); }}
      >
        <Alert type="warning" showIcon title="请核对最终 DDL" description="结构变更可能不可回滚。" />
        <TypedConfirmationFields
          target={{ expected: createTarget, value: createConfirmation, ariaLabel: '创建表确认文本', onChange: setCreateConfirmation }}
          production={productionConfirmationText ? { expected: productionConfirmationText, value: createProductionConfirmation, ariaLabel: '创建表生产确认', onChange: setCreateProductionConfirmation } : undefined}
        />
        <pre className="design-preview">{createPreview.join('\n')}</pre>
      </Modal>

      <Modal
        title={action?.operation === 'DROP' ? '删除表' : '重命名表'}
        open={Boolean(action)}
        confirmLoading={actionSubmitting}
        mask={{ closable: !actionSubmitting }}
        keyboard={!actionSubmitting}
        cancelButtonProps={{ disabled: actionSubmitting }}
        okButtonProps={{ danger: action?.operation === 'DROP', disabled: actionPreview.length > 0 ? !canExecuteAction : action?.operation === 'RENAME' && !newTableName.trim() }}
        okText={actionPreview.length > 0 ? action?.operation === 'DROP' ? '删除表' : '重命名表' : '预览 DDL'}
        onOk={actionPreview.length > 0 ? executeAction : previewAction}
        onCancel={() => { if (!actionSubmitting) onCloseAction(); }}
      >
        {action && (
          <Space orientation="vertical" size={12} className="full-width">
            <Alert
              type={action.operation === 'DROP' ? 'error' : 'warning'}
              showIcon
              title={action.operation === 'DROP' ? `将永久删除表 ${actionSource}` : `将重命名表 ${actionSource}`}
              description={action.operation === 'DROP' ? '不会自动备份，也不会使用 CASCADE；存在依赖时由数据库拒绝执行。' : '首版不支持仅修改表名大小写。'}
            />
            {action.operation === 'RENAME' && (
              <label className="table-lifecycle-field full-width">
                <Text type="secondary">新表名</Text>
                <Input aria-label="新表名" prefix={<SwapOutlined />} value={newTableName} placeholder="输入不含 Schema 的新表名" onChange={(event) => { setNewTableName(event.target.value); setActionPreview([]); setActionDetail(null); }} />
              </label>
            )}
            {actionMessage && <Alert type={actionPreview.length > 0 ? 'info' : 'error'} showIcon title={actionMessage} />}
            {actionPreview.length > 0 && (
              <>
                <Alert type="warning" showIcon title="请核对最终 DDL" description="结构变更可能不可回滚。" />
                <TypedConfirmationFields
                  target={{ expected: actionSource, value: actionConfirmation, ariaLabel: '表操作确认文本', onChange: setActionConfirmation }}
                  production={productionConfirmationText ? { expected: productionConfirmationText, value: actionProductionConfirmation, ariaLabel: '表操作生产确认', onChange: setActionProductionConfirmation } : undefined}
                />
                <pre className="design-preview">{actionPreview.join('\n')}</pre>
              </>
            )}
          </Space>
        )}
      </Modal>
    </>
  );
}
