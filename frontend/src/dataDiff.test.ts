import { describe, expect, it } from 'vitest';
import {
  buildDataDiffRequest,
  buildSyncScript,
  canRunDataDiff,
  cellPair,
  EMPTY_DATA_DIFF_FORM,
  parseKeyColumns,
  summarizeDataDiff
} from './dataDiff';
import type { DataDiffResponse } from './types';

const response = (patch: Partial<DataDiffResponse> = {}): DataDiffResponse => ({
  source: { connectionId: 1, connectionName: '预发', dbType: 'mysql', schemaName: 'shop' },
  target: { connectionId: 2, connectionName: '生产', dbType: 'mysql', schemaName: 'shop' },
  sourceTable: 'orders',
  targetTable: 'orders',
  keyColumns: ['ID'],
  columns: ['ID', 'AMOUNT'],
  summary: { onlyInSource: 1, onlyInTarget: 0, different: 1, identical: 8 },
  rows: [],
  script: ["UPDATE `orders` SET `AMOUNT` = '1' WHERE `ID` = '2';"],
  truncated: false,
  warnings: [],
  ...patch
});

describe('canRunDataDiff', () => {
  it('缺连接或表名时不发请求', () => {
    expect(canRunDataDiff(EMPTY_DATA_DIFF_FORM)).toBe(false);
    expect(canRunDataDiff({ ...EMPTY_DATA_DIFF_FORM, sourceConnectionId: 1, targetConnectionId: 2 })).toBe(false);
  });

  it('跨连接只要有表名就能比', () => {
    expect(canRunDataDiff({
      ...EMPTY_DATA_DIFF_FORM, sourceConnectionId: 1, targetConnectionId: 2, sourceTable: 'orders'
    })).toBe(true);
  });

  /** 同一条连接、同一个 Schema、同一张表，就是在和自己比。 */
  it('同连接下必须换 Schema 或换表', () => {
    const base = { ...EMPTY_DATA_DIFF_FORM, sourceConnectionId: 1, targetConnectionId: 1, sourceTable: 'orders' };

    expect(canRunDataDiff(base)).toBe(false);
    expect(canRunDataDiff({ ...base, targetTable: 'orders_backup' })).toBe(true);
    expect(canRunDataDiff({ ...base, sourceSchema: 'a', targetSchema: 'b' })).toBe(true);
  });
});

describe('buildDataDiffRequest', () => {
  it('留空的目标表与 Schema 不发送，由服务端按源表推定', () => {
    const request = buildDataDiffRequest({
      ...EMPTY_DATA_DIFF_FORM, sourceConnectionId: 1, targetConnectionId: 2, sourceTable: ' orders '
    });

    expect(request.sourceTable).toBe('orders');
    expect(request.targetTable).toBeUndefined();
    expect(request.sourceSchema).toBeUndefined();
    expect(request.keyColumns).toEqual([]);
  });

  it('匹配字段按逗号、换行或空格拆分', () => {
    expect(parseKeyColumns('id, tenant_id\n order_no')).toEqual(['id', 'tenant_id', 'order_no']);
    expect(parseKeyColumns('  ')).toEqual([]);
  });
});

describe('buildSyncScript', () => {
  /** 方向弄反的代价是往生产库写错数据，所以抬头必须写清楚谁对齐谁。 */
  it('抬头写明方向与匹配字段', () => {
    const script = buildSyncScript(response());

    expect(script).toContain('把 生产 / shop.orders 对齐到 预发 / shop.orders');
    expect(script).toContain('-- 匹配字段：ID');
  });

  /** 只在界面上提示一次的话，复制走脚本的人就看不到了。 */
  it('截断警告要留在脚本里', () => {
    const script = buildSyncScript(response({ truncated: true, warnings: ['差异超过 2000 条，脚本不完整'] }));

    expect(script).toContain('-- 注意：差异超过 2000 条，脚本不完整');
  });

  it('没有差异时不生成脚本', () => {
    expect(buildSyncScript(response({ script: [] }))).toBe('');
  });
});

describe('summarizeDataDiff 与 cellPair', () => {
  it('完全一致时只说比了多少行', () => {
    expect(summarizeDataDiff(response({ summary: { onlyInSource: 0, onlyInTarget: 0, different: 0, identical: 12 } })))
      .toBe('两侧数据一致，共比较 12 行。');
  });

  it('按列名取两侧取值', () => {
    const row = { key: ['2'], change: 'DIFFERENT' as const, columns: ['AMOUNT'], sourceValues: ['2', '1'], targetValues: ['2', '9'] };

    expect(cellPair(response(), row, 'AMOUNT')).toEqual({ source: '1', target: '9' });
    expect(cellPair(response(), row, '不存在的列')).toEqual({});
  });
});
