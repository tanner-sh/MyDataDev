import { useEffect, useMemo, useState } from 'react';
import { AutoComplete, Form, Select } from 'antd';
import { api } from '../api';
import { connectionOptions, scopeSuggestions, tableNameCandidates } from '../connectionScope';
import type { Connection, Metadata } from '../types';

/**
 * 管理面板共用的「连接 / Schema / 表」字段。规则见 connectionScope.ts。
 *
 * 候选来自 `/metadata/{id}`（服务端有元数据缓存）。读不到 —— 没权限、连不上 —— 就没有候选，
 * 输入框照常能手打：候选是帮手，不是闸门。
 */

/**
 * 同一类字段在所有面板里一样宽。写成 style 而不是样式类：antd 运行时注入的选择器优先级更高，
 * 普通类名上的 width 会被盖掉。
 */
const FIELD_WIDTH = { connection: 240, schema: 160, table: 180 } as const;

const schemaCache = new Map<number, Promise<string[]>>();

function loadSchemas(connectionId: number): Promise<string[]> {
  let pending = schemaCache.get(connectionId);
  if (!pending) {
    pending = api<Metadata>(`/metadata/${connectionId}?page=0&pageSize=1`)
      .then((metadata) => metadata.schemas ?? [])
      .catch(() => {
        schemaCache.delete(connectionId);
        return [];
      });
    schemaCache.set(connectionId, pending);
  }
  return pending;
}

function useSchemas(connectionId?: number): string[] {
  const [schemas, setSchemas] = useState<string[]>([]);
  useEffect(() => {
    let active = true;
    setSchemas([]);
    if (connectionId != null) void loadSchemas(connectionId).then((value) => { if (active) setSchemas(value); });
    return () => { active = false; };
  }, [connectionId]);
  return schemas;
}

/** 表名候选按输入去服务端搜：大库的表上万张，一次拉全不现实。 */
function useTableNames(connectionId: number | undefined, schemaName: string, keyword: string): string[] {
  const [names, setNames] = useState<string[]>([]);
  useEffect(() => {
    if (connectionId == null) { setNames([]); return; }
    let active = true;
    const timer = window.setTimeout(() => {
      const params = new URLSearchParams({ page: '0', pageSize: '30' });
      if (schemaName.trim()) params.set('schema', schemaName.trim());
      if (keyword.trim()) params.set('keyword', keyword.trim());
      void api<Metadata>(`/metadata/${connectionId}?${params.toString()}`)
        .then((metadata) => { if (active) setNames(tableNameCandidates(metadata.objects ?? [])); })
        .catch(() => { if (active) setNames([]); });
    }, 250);
    return () => { active = false; window.clearTimeout(timer); };
  }, [connectionId, schemaName, keyword]);
  return names;
}

export function ConnectionField({ label = '连接', required, tooltip, connections, value, placeholder = '选择连接', onChange }: {
  label?: string;
  required?: boolean;
  tooltip?: string;
  connections: Connection[];
  value?: number;
  placeholder?: string;
  onChange: (connectionId: number) => void;
}) {
  const options = useMemo(() => connectionOptions(connections), [connections]);
  return (
    <Form.Item label={label} required={required} tooltip={tooltip}>
      <Select style={{ width: FIELD_WIDTH.connection }} showSearch optionFilterProp="label" value={value} options={options} placeholder={placeholder} onChange={onChange} />
    </Form.Item>
  );
}

export function SchemaField({ label = 'Schema', tooltip = '留空则用连接当前所在的 Schema/数据库', connectionId, value, placeholder = '默认', onChange }: {
  label?: string;
  tooltip?: string;
  connectionId?: number;
  value: string;
  placeholder?: string;
  onChange: (schemaName: string) => void;
}) {
  const schemas = useSchemas(connectionId);
  const options = useMemo(() => scopeSuggestions(schemas, value).map((schema) => ({ value: schema })), [schemas, value]);
  return (
    <Form.Item label={label} tooltip={tooltip}>
      <AutoComplete style={{ width: FIELD_WIDTH.schema }} allowClear value={value} options={options} placeholder={placeholder}
        onChange={(next) => onChange(next ?? '')} />
    </Form.Item>
  );
}

export function TableField({ label = '表', required, tooltip, connectionId, schemaName, value, placeholder, onChange }: {
  label?: string;
  required?: boolean;
  tooltip?: string;
  connectionId?: number;
  schemaName: string;
  value: string;
  placeholder?: string;
  onChange: (tableName: string) => void;
}) {
  const names = useTableNames(connectionId, schemaName, value);
  const options = useMemo(() => scopeSuggestions(names, value).map((name) => ({ value: name })), [names, value]);
  return (
    <Form.Item label={label} required={required} tooltip={tooltip}>
      <AutoComplete style={{ width: FIELD_WIDTH.table }} allowClear value={value} options={options} placeholder={placeholder}
        onChange={(next) => onChange(next ?? '')} />
    </Form.Item>
  );
}
