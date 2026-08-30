import { useEffect, useState } from 'react';
import { Button, Divider, Drawer, Empty, Input, Select, Space, Typography } from 'antd';
import { DeleteOutlined, FilterOutlined, PlusOutlined } from '@ant-design/icons';
import type { TableColumn } from '../types';
import { EMPTY_TABLE_QUERY, tableQueryRuleCount, type TableFilterOperator, type TableQuery } from '../tableQuery';

const { Text } = Typography;
const OPERATORS: Array<{ value: TableFilterOperator; label: string }> = [
  { value: 'EQ', label: '等于' }, { value: 'NE', label: '不等于' },
  { value: 'CONTAINS', label: '包含' }, { value: 'NOT_CONTAINS', label: '不包含' },
  { value: 'STARTS_WITH', label: '开头是' }, { value: 'ENDS_WITH', label: '结尾是' },
  { value: 'GT', label: '大于' }, { value: 'GTE', label: '大于等于' },
  { value: 'LT', label: '小于' }, { value: 'LTE', label: '小于等于' },
  { value: 'BETWEEN', label: '介于' }, { value: 'IN', label: '属于列表' },
  { value: 'IS_NULL', label: '为空' }, { value: 'IS_NOT_NULL', label: '不为空' }
];

export function TableQueryBuilder({ columns, value, disabled, onApply }: {
  columns: TableColumn[];
  value: TableQuery;
  disabled?: boolean;
  onApply: (query: TableQuery) => void;
}) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<TableQuery>(value);
  useEffect(() => setDraft({
    ...value,
    filters: value.filters.map((rule) => rule.operator === 'IN'
      ? { ...rule, value: rule.value ?? rule.values?.join(', ') ?? '' }
      : rule)
  }), [value]);
  const columnOptions = columns.map((column) => ({ value: column.name, label: `${column.name} · ${column.typeName}` }));

  function apply() {
    const filters = draft.filters.map((rule) => rule.operator === 'IN'
      ? { ...rule, values: (rule.value || '').split(',').map((item) => item.trim()).filter(Boolean), value: undefined }
      : rule);
    onApply({ ...draft, filters });
    setOpen(false);
  }

  return (
    <>
      <Button size="small" icon={<FilterOutlined />} disabled={disabled || columns.length === 0} onClick={() => setOpen(true)}>
        筛选 / 排序{tableQueryRuleCount(value) ? ` (${tableQueryRuleCount(value)})` : ''}
      </Button>
      <Drawer title="服务端筛选与排序" width={600} open={open} onClose={() => setOpen(false)}
        extra={<Space><Button onClick={() => setDraft(EMPTY_TABLE_QUERY)}>清空</Button><Button type="primary" onClick={apply}>应用</Button></Space>}>
        <Space direction="vertical" size={12} className="table-query-section">
          <Space>
            <Text strong>筛选条件</Text>
            <Select size="small" value={draft.filterLogic} options={[{ value: 'AND', label: '同时满足' }, { value: 'OR', label: '满足任一' }]}
              onChange={(filterLogic) => setDraft({ ...draft, filterLogic })} />
          </Space>
          {draft.filters.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无筛选条件" />}
          {draft.filters.map((rule, index) => {
            const noValue = rule.operator === 'IS_NULL' || rule.operator === 'IS_NOT_NULL';
            return (
              <Space key={index} className="table-query-rule" align="start">
                <Select showSearch value={rule.column || undefined} placeholder="字段" options={columnOptions} onChange={(column) => {
                  const filters = [...draft.filters]; filters[index] = { ...rule, column }; setDraft({ ...draft, filters });
                }} />
                <Select value={rule.operator} options={OPERATORS} onChange={(operator) => {
                  const filters = [...draft.filters]; filters[index] = { ...rule, operator }; setDraft({ ...draft, filters });
                }} />
                {!noValue && <Input value={rule.value} placeholder={rule.operator === 'IN' ? '逗号分隔多个值' : '值'} onChange={(event) => {
                  const filters = [...draft.filters]; filters[index] = { ...rule, value: event.target.value }; setDraft({ ...draft, filters });
                }} />}
                {rule.operator === 'BETWEEN' && <Input value={rule.secondValue} placeholder="结束值" onChange={(event) => {
                  const filters = [...draft.filters]; filters[index] = { ...rule, secondValue: event.target.value }; setDraft({ ...draft, filters });
                }} />}
                <Button type="text" danger icon={<DeleteOutlined />} aria-label="删除筛选条件" onClick={() => setDraft({ ...draft, filters: draft.filters.filter((_, itemIndex) => itemIndex !== index) })} />
              </Space>
            );
          })}
          <Button type="dashed" icon={<PlusOutlined />} disabled={draft.filters.length >= 20 || columns.length === 0}
            onClick={() => setDraft({ ...draft, filters: [...draft.filters, { column: columns[0]?.name || '', operator: 'EQ', value: '' }] })}>添加筛选条件</Button>
        </Space>
        <Divider />
        <Space direction="vertical" size={12} className="table-query-section">
          <Text strong>排序优先级</Text>
          {draft.sorts.length === 0 && <Text type="secondary">未指定时按稳定主键正序分页。</Text>}
          {draft.sorts.map((rule, index) => (
            <Space key={index} className="table-query-rule">
              <Select showSearch value={rule.column || undefined} placeholder="字段" options={columnOptions} onChange={(column) => {
                const sorts = [...draft.sorts]; sorts[index] = { ...rule, column }; setDraft({ ...draft, sorts });
              }} />
              <Select value={rule.direction} options={[{ value: 'ASC', label: '升序' }, { value: 'DESC', label: '降序' }]} onChange={(direction) => {
                const sorts = [...draft.sorts]; sorts[index] = { ...rule, direction }; setDraft({ ...draft, sorts });
              }} />
              <Button type="text" danger icon={<DeleteOutlined />} aria-label="删除排序条件" onClick={() => setDraft({ ...draft, sorts: draft.sorts.filter((_, itemIndex) => itemIndex !== index) })} />
            </Space>
          ))}
          <Button type="dashed" icon={<PlusOutlined />} disabled={draft.sorts.length >= 10 || columns.length === 0}
            onClick={() => setDraft({ ...draft, sorts: [...draft.sorts, { column: columns[0]?.name || '', direction: 'ASC' }] })}>添加排序字段</Button>
        </Space>
      </Drawer>
    </>
  );
}
