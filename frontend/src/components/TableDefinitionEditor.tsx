import { AutoComplete, Button, Checkbox, Input, InputNumber, Select, Space, Table, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useMemo } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import type { ColumnDesign, IndexDesign, ObjectDetail } from '../types';

const { Text } = Typography;
const COLUMN_TYPE_OPTIONS = ['VARCHAR', 'CHAR', 'TEXT', 'INTEGER', 'BIGINT', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'JSON', 'BLOB']
  .map((value) => ({ value, label: value }));
let rowSequence = 0;

export type DesignColumnRow = ColumnDesign & { key: string };
export type DesignIndexRow = IndexDesign & { key: string };

export function TableDefinitionEditor({
  mode,
  columns,
  indexes,
  primaryKeys,
  commentsSupported = true,
  disabled,
  dirty,
  onReset,
  setColumns,
  setIndexes,
  setPrimaryKeys
}: {
  mode: 'create' | 'edit';
  columns: DesignColumnRow[];
  indexes: DesignIndexRow[];
  primaryKeys: string[];
  /** 当前数据库能不能改列注释；不能时整列不显示，而不是给一个填了会报错的输入框。 */
  commentsSupported?: boolean;
  disabled?: boolean;
  dirty?: boolean;
  onReset?: () => void;
  setColumns: Dispatch<SetStateAction<DesignColumnRow[]>>;
  setIndexes: Dispatch<SetStateAction<DesignIndexRow[]>>;
  setPrimaryKeys: Dispatch<SetStateAction<string[]>>;
}) {
  const activeColumns = columns.filter((column) => !column.deleted);
  const columnOptions = useMemo(
    () => activeColumns.filter((column) => column.name).map((column) => ({ value: column.name, label: column.name })),
    [columns]
  );

  function patchColumn(row: DesignColumnRow, patch: Partial<DesignColumnRow>) {
    if (patch.name !== undefined && patch.name !== row.name) {
      const nextName = patch.name;
      setPrimaryKeys((keys) => keys.map((name) => name === row.name ? nextName : name));
      setIndexes((rows) => rows.map((index) => ({
        ...index,
        columns: index.columns.map((name) => name === row.name ? nextName : name)
      })));
    }
    setColumns((rows) => rows.map((item) => item.key === row.key ? { ...item, ...patch } : item));
  }

  function removeColumn(row: DesignColumnRow) {
    setPrimaryKeys((keys) => keys.filter((name) => name !== row.name));
    setIndexes((rows) => rows.flatMap((index) => {
      const nextColumns = index.columns.filter((name) => name !== row.name);
      if (mode === 'create' && nextColumns.length === 0) return [];
      return [{ ...index, columns: nextColumns, deleted: index.deleted || nextColumns.length === 0 }];
    }));
    if (mode === 'create') {
      setColumns((rows) => rows.filter((item) => item.key !== row.key));
    } else {
      patchColumn(row, { deleted: !row.deleted });
    }
  }

  function removeIndex(row: DesignIndexRow) {
    if (mode === 'create') {
      setIndexes((rows) => rows.filter((item) => item.key !== row.key));
    } else {
      setIndexes((rows) => rows.map((item) => item.key === row.key ? { ...item, deleted: !item.deleted } : item));
    }
  }

  return (
    <div className="table-definition-editor">
      <section className="designer-section designer-column-section">
        <div className="designer-toolbar">
          <Text strong>字段</Text>
          <Space size={6}>
            {dirty && <Text type="warning">有未保存修改</Text>}
            {onReset && <Button size="small" disabled={disabled || !dirty} onClick={onReset}>撤销全部</Button>}
            <Button size="small" icon={<PlusOutlined />} disabled={disabled} onClick={() => setColumns((rows) => [...rows, newColumnRow()])}>新增字段</Button>
          </Space>
        </div>
        <div className="designer-table-viewport">
          <Table<DesignColumnRow>
            size="small"
            className="data-grid object-detail-grid designer-grid designer-column-grid"
            rowClassName={(row) => row.deleted ? 'deleted-row' : ''}
            pagination={false}
            dataSource={columns}
            scroll={{ x: 900 }}
            sticky
            columns={[
              { title: '字段名', dataIndex: 'name', key: 'name', width: 150, render: (value, row) => <Input aria-label="字段名" size="small" disabled={disabled || row.deleted} value={value} onChange={(event) => patchColumn(row, { name: event.target.value })} /> },
              { title: '类型', dataIndex: 'type', key: 'type', width: 150, render: (value, row) => <AutoComplete aria-label="字段类型" size="small" className="full-width" disabled={disabled || row.deleted} value={value} options={COLUMN_TYPE_OPTIONS} filterOption={(input, option) => String(option?.value || '').includes(input.toUpperCase())} onChange={(next) => patchColumn(row, { type: next.toUpperCase() })} /> },
              { title: '长度', dataIndex: 'size', key: 'size', width: 90, render: (value, row) => <InputNumber aria-label="字段长度" size="small" min={0} disabled={disabled || row.deleted} value={value || undefined} onChange={(next) => patchColumn(row, { size: next || null })} /> },
              { title: '可空', dataIndex: 'nullable', key: 'nullable', width: 80, render: (value, row) => <Checkbox aria-label={`${row.name || '新字段'}允许为空`} disabled={disabled || row.deleted} checked={value} onChange={(event) => patchColumn(row, { nullable: event.target.checked })} /> },
              { title: '默认值', dataIndex: 'defaultValue', key: 'defaultValue', width: 150, render: (value, row) => <Input aria-label="字段默认值" size="small" disabled={disabled || row.deleted} value={value} onChange={(event) => patchColumn(row, { defaultValue: event.target.value })} /> },
              // 注释是这个产品最依赖的元数据（资源树、结构对比、AI 的结构搜索都在读它），
              // 以前只能在数据库里改。不支持的方言直接不显示这一列。
              ...(commentsSupported ? [{ title: '注释', dataIndex: 'remarks', key: 'remarks', width: 180, render: (value: string | undefined, row: DesignColumnRow) => <Input aria-label="字段注释" size="small" placeholder="说明这个字段是什么" disabled={disabled || row.deleted} value={value ?? ''} onChange={(event) => patchColumn(row, { remarks: event.target.value })} /> }] : []),
              { title: '主键', key: 'pk', width: 70, render: (_, row) => <Checkbox aria-label={`${row.name || '新字段'}设为主键`} disabled={disabled || row.deleted || !row.name} checked={primaryKeys.includes(row.name)} onChange={(event) => setPrimaryKeys((keys) => event.target.checked ? [...new Set([...keys, row.name])] : keys.filter((key) => key !== row.name))} /> },
              { title: '操作', key: 'action', width: 90, render: (_, row) => <Button size="small" danger disabled={disabled} onClick={() => removeColumn(row)}>{mode === 'edit' && row.deleted ? '恢复' : '删除'}</Button> }
            ]}
          />
        </div>
      </section>
      <section className="designer-section designer-index-section">
        <div className="designer-toolbar">
          <Text strong>索引</Text>
          <Button size="small" icon={<PlusOutlined />} disabled={disabled || activeColumns.length === 0} onClick={() => setIndexes((rows) => [...rows, newIndexRow()])}>新增索引</Button>
        </div>
        <div className="designer-table-viewport">
          <Table<DesignIndexRow>
            size="small"
            className="data-grid object-detail-grid designer-grid designer-index-grid"
            rowClassName={(row) => row.deleted ? 'deleted-row' : ''}
            pagination={false}
            dataSource={indexes}
            scroll={{ x: 720 }}
            sticky
            columns={[
              { title: '索引名', dataIndex: 'name', key: 'name', width: 170, render: (value, row) => <Input aria-label="索引名" size="small" disabled={disabled || row.deleted} value={value} onChange={(event) => setIndexes((rows) => rows.map((item) => item.key === row.key ? { ...item, name: event.target.value } : item))} /> },
              { title: '字段', dataIndex: 'columns', key: 'columns', width: 360, render: (value, row) => <Select aria-label="索引字段" size="small" mode="multiple" className="full-width" disabled={disabled || row.deleted} value={value} options={columnOptions} onChange={(next) => setIndexes((rows) => rows.map((item) => item.key === row.key ? { ...item, columns: next } : item))} /> },
              { title: '唯一', dataIndex: 'unique', key: 'unique', width: 80, render: (value, row) => <Checkbox aria-label={`${row.name || '新索引'}设为唯一索引`} disabled={disabled || row.deleted} checked={value} onChange={(event) => setIndexes((rows) => rows.map((item) => item.key === row.key ? { ...item, unique: event.target.checked } : item))} /> },
              { title: '操作', key: 'action', width: 90, render: (_, row) => <Button size="small" danger disabled={disabled} onClick={() => removeIndex(row)}>{mode === 'edit' && row.deleted ? '恢复' : '删除'}</Button> }
            ]}
          />
        </div>
      </section>
    </div>
  );
}

export function designColumns(detail: ObjectDetail): DesignColumnRow[] {
  return detail.columns.map((column) => ({
    key: column.name,
    name: column.name,
    type: column.type,
    size: column.size,
    nullable: column.nullable,
    defaultValue: column.defaultValue || '',
    originalName: column.name,
    deleted: false,
    remarks: column.remarks || ''
  }));
}

export function designIndexes(detail: ObjectDetail): DesignIndexRow[] {
  const grouped = new Map<string, DesignIndexRow>();
  detail.indexes.slice().sort((left, right) => (left.ordinalPosition || 0) - (right.ordinalPosition || 0)).forEach((index) => {
    const current = grouped.get(index.name);
    if (current) current.columns.push(index.columnName);
    else grouped.set(index.name, { key: index.name, name: index.name, originalName: index.name, columns: [index.columnName], unique: index.unique, deleted: false });
  });
  return [...grouped.values()].filter((index) => {
    if (detail.primaryKeyName && index.name === detail.primaryKeyName) return false;
    return detail.primaryKeys.length === 0 || !index.unique || !sameColumns(index.columns, detail.primaryKeys);
  });
}

export function newColumnRow(): DesignColumnRow {
  rowSequence += 1;
  return { key: `new-column-${Date.now()}-${rowSequence}`, name: '', type: 'VARCHAR', size: 255, nullable: true, defaultValue: '', deleted: false, remarks: '' };
}

export function newIndexRow(): DesignIndexRow {
  rowSequence += 1;
  return { key: `new-index-${Date.now()}-${rowSequence}`, name: '', columns: [], unique: false, deleted: false };
}

export function tableDefinitionSignature(columns: DesignColumnRow[], indexes: DesignIndexRow[], primaryKeys: string[]) {
  return JSON.stringify({ columns: serializeColumns(columns), indexes: serializeIndexes(indexes), primaryKeys });
}

export function serializeColumns(columns: DesignColumnRow[]): ColumnDesign[] {
  return columns.map(({ key: _key, ...column }) => column);
}

export function serializeIndexes(indexes: DesignIndexRow[]): IndexDesign[] {
  return indexes.map(({ key: _key, ...index }) => index);
}

function sameColumns(left: string[], right: string[]) {
  return left.length === right.length && left.every((column, index) => column === right[index]);
}
