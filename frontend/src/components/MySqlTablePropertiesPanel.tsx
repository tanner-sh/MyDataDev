import { Alert, Button, Descriptions, Form, Input, Modal, Select, Space, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { api } from '../api';
import { productionConfirmationHeaders } from '../productionConfirmation';
import type { ObjectDetail } from '../types';

type Properties = { schemaName: string; tableName: string; engine: string; characterSet: string; collation: string; autoIncrement?: string;
  estimatedRows?: string; dataBytes?: string; indexBytes?: string; version: string; engines: string[]; collations: { name: string; characterSet: string }[] };
type Change = Pick<Properties, 'engine' | 'characterSet' | 'collation' | 'autoIncrement'>;
export function MySqlTablePropertiesPanel({ connectionId, detail, active, disabled, productionConfirmationText, onReloadDetail }: {
  connectionId?: number; detail: ObjectDetail; active: boolean; disabled: boolean; productionConfirmationText?: string; onReloadDetail: () => void;
}) {
  const [info, setInfo] = useState<Properties>();
  const [change, setChange] = useState<Change>();
  const [preview, setPreview] = useState<string[]>([]);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const [production, setProduction] = useState('');
  const endpoint = `/metadata/${connectionId}/table-properties`;
  const target = `${info?.schemaName}.${info?.tableName}`;
  useEffect(() => {
    let live = true;
    if (active && connectionId && !info) {
      void api<Properties>(`${endpoint}?${new URLSearchParams({ schema: detail.schemaName || '', table: detail.name })}`)
        .then(value => { if (live) { setInfo(value); setChange({ engine: value.engine, characterSet: value.characterSet, collation: value.collation, autoIncrement: value.autoIncrement }); setMessage(''); } })
        .catch(error => { if (live) setMessage(error.message); });
    }
    return () => { live = false; };
  }, [active, connectionId, detail.schemaName, detail.name, info]);
  const patch = (value: Partial<Change>) => { setChange(current => current ? { ...current, ...value } : current); setPreview([]); };
  async function submit(execute: boolean) {
    if (!info || !change) return;
    setBusy(true); setMessage('');
    try {
      const result = await api<{ sql: string[]; message: string }>(`${endpoint}/${execute ? 'execute' : 'preview'}`, {
        method: 'POST', headers: productionConfirmationHeaders(production || undefined),
        body: JSON.stringify({ ...change, schemaName: info.schemaName, tableName: info.tableName, version: info.version, confirmation })
      });
      setPreview(result.sql); setMessage(result.message);
      if (execute) { setConfirmOpen(false); setInfo(undefined); setPreview([]); onReloadDetail(); }
    } catch (error) { setMessage(error instanceof Error ? error.message : '表属性操作失败'); }
    finally { setBusy(false); }
  }
  return <Space orientation="vertical" className="full-width" size={12}>
    {message && <Alert showIcon type="info" title={message} />}
    {!info || !change ? !message && <Spin /> : <>
      <Descriptions size="small" column={3} items={[
        { key: 'rows', label: '估算行数', children: info.estimatedRows ?? '未知' },
        { key: 'data', label: '数据字节', children: info.dataBytes ?? '未知' },
        { key: 'indexes', label: '索引字节', children: info.indexBytes ?? '未知' }
      ]} />
      <Alert type="info" showIcon title="更换引擎可能重建并锁定表。默认字符集和排序规则仅影响后续新增列，不转换现有列的数据。" />
      <Form layout="vertical" disabled={disabled || busy}>
        <Form.Item label="存储引擎"><Select value={change.engine} options={info.engines.map(value => ({ value }))} onChange={engine => patch({ engine })} /></Form.Item>
        <Form.Item label="默认字符集"><Select showSearch value={change.characterSet} options={[...new Set(info.collations.map(c => c.characterSet))].map(value => ({ value }))}
          onChange={characterSet => patch({ characterSet, collation: info.collations.find(c => c.characterSet === characterSet)?.name || '' })} /></Form.Item>
        <Form.Item label="默认排序规则"><Select showSearch value={change.collation} options={info.collations.filter(c => c.characterSet === change.characterSet).map(c => ({ value: c.name }))} onChange={collation => patch({ collation })} /></Form.Item>
        <Form.Item label="下一自增值"><Input disabled={disabled || busy || !info.autoIncrement} value={change.autoIncrement || ''} placeholder="当前表没有自增字段" onChange={event => patch({ autoIncrement: event.target.value })} /></Form.Item>
      </Form>
      <Space><Button disabled={disabled || busy} onClick={() => void submit(false)}>预览属性 DDL</Button>
        <Button type="primary" disabled={disabled || busy || preview.length === 0} onClick={() => { setConfirmation(''); setProduction(''); setConfirmOpen(true); }}>执行属性变更</Button>
        <Button disabled={busy} onClick={() => { setInfo(undefined); setPreview([]); setMessage(''); }}>刷新并重置</Button></Space>
      {preview.length > 0 && <Input.TextArea readOnly autoSize value={preview.join(';\n')} />}
    </>}
    <Modal open={confirmOpen} title="确认表属性变更" confirmLoading={busy} onCancel={() => setConfirmOpen(false)} onOk={() => void submit(true)}
      okButtonProps={{ disabled: disabled || confirmation !== target || Boolean(productionConfirmationText && production !== productionConfirmationText) }}>
      <Input.TextArea readOnly autoSize value={preview.join(';\n')} />
      <Form layout="vertical"><Form.Item label={`输入完整表名：${target}`}><Input value={confirmation} onChange={e => setConfirmation(e.target.value)} /></Form.Item>
        {productionConfirmationText && <Form.Item label={`输入生产连接名：${productionConfirmationText}`}><Input value={production} onChange={e => setProduction(e.target.value)} /></Form.Item>}
      </Form>
    </Modal>
  </Space>;
}
