import { describe, expect, it } from 'vitest';
import { designColumns, serializeColumns } from './TableDefinitionEditor';
import type { ObjectDetail } from '../types';

describe('SQL Server 字段设计稿', () => {
  it('保留自增属性，但不把只读计算列标记作为可编辑定义提交', () => {
    const detail: ObjectDetail = {
      name: 'orders', type: 'TABLE', structureVersion: 'v1', primaryKeys: ['id'], indexes: [],
      columns: [
        { name: 'id', type: 'int', size: 10, nullable: false, identity: true },
        { name: 'total', type: 'decimal(18,2)', size: 18, nullable: true, generated: true }
      ]
    };
    const rows = designColumns(detail);
    expect(rows[0].identity).toBe(true);
    expect(rows[1].generated).toBe(true);
    const submitted = serializeColumns(rows);
    expect(submitted[0].identity).toBe(true);
    expect(submitted[1]).not.toHaveProperty('generated');
    expect(submitted[1]).not.toHaveProperty('key');
  });
});
