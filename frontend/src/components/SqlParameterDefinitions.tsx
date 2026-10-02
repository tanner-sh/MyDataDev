import { Button, Checkbox, Input, Select, Space, Typography } from 'antd';
import { PARAMETER_TYPES, parameterError, type SqlParameterDefinition } from '../sqlParameters';

export function SqlParameterDefinitions({ value, onChange, onDetect, detecting }: {
  value: SqlParameterDefinition[];
  onChange: (value: SqlParameterDefinition[]) => void;
  onDetect: () => void;
  detecting: boolean;
}) {
  function update(index: number, patch: Partial<SqlParameterDefinition>) {
    onChange(value.map((item, i) => i === index ? { ...item, ...patch } : item));
  }
  return <div className="snippet-field">
    <Space wrap><Typography.Text strong>查询模板参数</Typography.Text><Button size="small" loading={detecting} onClick={onDetect}>从 SQL 识别参数</Button></Space>
    <Typography.Text type="secondary">使用 :参数名，例如 WHERE user_id = :user_id。仅支持单条 SELECT；参数用于值，不用于表名或列名。默认值随模板共享，请勿保存敏感信息。</Typography.Text>
    {value.map((item, index) => <div className="sql-parameter-definition" key={item.name}>
      <Space wrap>
        <Typography.Text code>:{item.name}</Typography.Text>
        <Select aria-label={`${item.name} 类型`} value={item.type} options={[...PARAMETER_TYPES]} onChange={(type) => update(index, { type, defaultValue: undefined })} style={{ width: 110 }} />
        <Checkbox checked={item.required} onChange={(event) => update(index, { required: event.target.checked })}>不允许 NULL</Checkbox>
        <Checkbox checked={item.defaultValue != null} onChange={(event) => update(index, { defaultValue: event.target.checked ? '' : undefined })}>设置默认值</Checkbox>
        {item.defaultValue != null && <Input aria-label={`${item.name} 默认值`} placeholder="默认值（文本可为空字符串）" value={item.defaultValue}
          onChange={(event) => update(index, { defaultValue: event.target.value })} style={{ width: 230 }} />}
      </Space>
      {item.defaultValue != null && parameterError(item, item.defaultValue) && <Typography.Text type="danger">{parameterError(item, item.defaultValue)}</Typography.Text>}
    </div>)}
  </div>;
}
