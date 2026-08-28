import { describe, expect, it } from 'vitest';
import {
  buildMigrationScript,
  buildSchemaDiffRequest,
  canRunSchemaDiff,
  EMPTY_SCHEMA_DIFF_FORM,
  parseTableFilter,
  summarizeSchemaDiff
} from './schemaDiff';
import type { SchemaDiffResponse } from './types';

const response: SchemaDiffResponse = {
  source: { connectionId: 1, connectionName: '预发布', dbType: 'mysql', schemaName: 'shop' },
  target: { connectionId: 2, connectionName: '生产', dbType: 'mysql', schemaName: 'shop' },
  summary: { onlyInSource: 1, onlyInTarget: 0, different: 1, identical: 3 },
  tables: [],
  migration: ['-- orders：结构不一致', 'ALTER TABLE `orders` ADD COLUMN `note` VARCHAR(200)'],
  warnings: ['两侧数据库类型不同']
};

describe('schema diff', () => {
  it('表名过滤按各种分隔符拆分并去重', () => {
    expect(parseTableFilter(' orders, orders \n staff;items ')).toEqual(['orders', 'staff', 'items']);
    expect(parseTableFilter('   ')).toEqual([]);
  });

  it('两端都选定后才能发起对比', () => {
    expect(canRunSchemaDiff(EMPTY_SCHEMA_DIFF_FORM)).toBe(false);
    expect(canRunSchemaDiff({ ...EMPTY_SCHEMA_DIFF_FORM, sourceConnectionId: 1, targetConnectionId: 2 })).toBe(true);
  });

  it('同一条连接必须比较两个不同的 Schema', () => {
    const sameConnection = { ...EMPTY_SCHEMA_DIFF_FORM, sourceConnectionId: 1, targetConnectionId: 1 };
    expect(canRunSchemaDiff(sameConnection)).toBe(false);
    expect(canRunSchemaDiff({ ...sameConnection, sourceSchema: 'shop', targetSchema: 'shop' })).toBe(false);
    expect(canRunSchemaDiff({ ...sameConnection, sourceSchema: 'shop', targetSchema: 'SHOP' })).toBe(false);
    expect(canRunSchemaDiff({ ...sameConnection, sourceSchema: 'shop', targetSchema: 'shop_archive' })).toBe(true);
  });

  it('空 Schema 提交为 undefined，交给后端回落到默认库', () => {
    const request = buildSchemaDiffRequest({
      ...EMPTY_SCHEMA_DIFF_FORM,
      sourceConnectionId: 1,
      targetConnectionId: 2,
      tables: 'orders'
    });
    expect(request).toEqual({
      sourceConnectionId: 1,
      sourceSchema: undefined,
      targetConnectionId: 2,
      targetSchema: undefined,
      tables: ['orders'],
      includeDrops: false
    });
    expect(() => buildSchemaDiffRequest(EMPTY_SCHEMA_DIFF_FORM)).toThrow('请先选择源和目标连接');
  });

  it('生成的脚本给语句加分号但不动注释行', () => {
    const script = buildMigrationScript(response);
    const lines = script.split('\n');
    expect(lines[0]).toContain('把 生产 / shop 对齐到 预发布 / shop');
    expect(script).toContain('-- 注意：两侧数据库类型不同');
    expect(script).toContain('-- orders：结构不一致\nALTER TABLE `orders` ADD COLUMN `note` VARCHAR(200);');
    expect(script).not.toContain('结构不一致;');
  });

  it('没有差异时不生成脚本', () => {
    expect(buildMigrationScript({ ...response, migration: [] })).toBe('');
    expect(summarizeSchemaDiff({
      ...response,
      summary: { onlyInSource: 0, onlyInTarget: 0, different: 0, identical: 4 }
    })).toBe('两侧结构一致，共比较 4 张表。');
  });

  it('有差异时的摘要覆盖四类计数', () => {
    expect(summarizeSchemaDiff(response)).toBe('目标端缺 1 张表，多 0 张表，1 张表结构不一致，3 张表一致。');
  });
});
