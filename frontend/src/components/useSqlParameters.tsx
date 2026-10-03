import { useEffect, useRef, useState } from 'react';
import { Checkbox, Input, Modal, Select, Space, Typography } from 'antd';
import { initialParameterValues, parameterError, PARAMETER_TYPES, type SqlParameterDefinition, type SqlParameterValues } from '../sqlParameters';

/** Values remain in memory for result pagination; SQL drafts/history never contain them. */
export function useSqlParameters(scope: string) {
  const [definitions, setDefinitions] = useState<SqlParameterDefinition[] | null>(null);
  const [values, setValues] = useState<SqlParameterValues>({});
  const resolver = useRef<((value: SqlParameterValues | null) => void) | null>(null);
  const currentScope = useRef(scope);
  currentScope.current = scope;
  function finish(value: SqlParameterValues | null) {
    resolver.current?.(value);
    resolver.current = null;
    setDefinitions(null);
    setValues({});
  }
  useEffect(() => {
    finish(null);
    return () => { resolver.current?.(null); resolver.current = null; };
  }, [scope]);
  function request(items: SqlParameterDefinition[]): Promise<SqlParameterValues | null> {
    if (currentScope.current !== scope) return Promise.resolve(null);
    resolver.current?.(null);
    setDefinitions(items);
    setValues(initialParameterValues(items));
    return new Promise((resolve) => { resolver.current = resolve; });
  }
  const invalid = definitions?.some((item) => parameterError(item, values[item.name]?.value ?? null));
  const dialog = <Modal open={definitions !== null} title="填写查询参数" okText="执行查询" cancelText="取消"
    onCancel={() => finish(null)} onOk={() => { if (!invalid) finish(values); }} okButtonProps={{ disabled: !!invalid }} destroyOnHidden styles={{ body: { maxHeight: '65vh', overflowY: 'auto' } }}>
    <Typography.Paragraph type="secondary">参数值仅在内存中用于本次查询、翻页或导出，不写入历史。文本空字符串与 NULL 分开处理；日期时间不带时区。</Typography.Paragraph>
    {definitions?.map((item) => {
      const current = values[item.name]?.value ?? null;
      const error = parameterError(item, current);
      const update = (value: string | null) => setValues((previous) => ({ ...previous, [item.name]: { type: item.type, value } }));
      return <div className="snippet-field" key={item.name}>
        <Space wrap><Typography.Text strong>{item.name}</Typography.Text>
          <Select aria-label={`${item.name} 本次类型`} value={item.type} options={[...PARAMETER_TYPES]} style={{ width: 110 }} onChange={(type) => {
            setDefinitions((items) => items?.map((definition) => definition.name === item.name ? { ...definition, type } : definition) ?? null);
            setValues((previous) => ({ ...previous, [item.name]: { type, value: current } }));
          }} />
          {!item.required && <Checkbox checked={current === null} onChange={(event) => update(event.target.checked ? null : '')}>NULL</Checkbox>}
        </Space>
        {item.type === 'BOOLEAN'
          ? <Select aria-label={item.name} disabled={current === null} value={current || undefined} options={[{ value: 'true', label: 'true' }, { value: 'false', label: 'false' }]} onChange={update} />
          : item.type === 'TEXT' ? <Input.TextArea aria-label={item.name} autoSize={{ minRows: 2, maxRows: 6 }} disabled={current === null}
              value={current ?? ''} status={error ? 'error' : undefined} onChange={event => update(event.target.value)} />
          : <Input type={item.type === 'DATE' ? 'date' : 'text'} aria-label={item.name} disabled={current === null} value={current ?? ''} status={error ? 'error' : undefined}
              placeholder={item.type === 'DATE' ? 'YYYY-MM-DD' : item.type === 'TIMESTAMP' ? 'YYYY-MM-DD HH:mm:ss' : undefined}
              onChange={(event) => update(event.target.value)} />}
        {error && <Typography.Text type="danger">{error}</Typography.Text>}
      </div>;
    })}
  </Modal>;
  return { request, dialog };
}
