import { describe, expect, it } from 'vitest';
import {
  buildDataTransferRequest,
  canRunTransfer,
  describeTransfer,
  EMPTY_DATA_TRANSFER_FORM,
  isSelfCopy,
  sourceConfirmationName,
  withSourceTable,
  type DataTransferForm
} from './dataTransfer';
import type { Connection } from './types';

const CONNECTIONS = [
  { id: 1, name: '生产库', dbType: 'mysql', environment: 'prod' },
  { id: 2, name: '分析库', dbType: 'postgresql', environment: 'dev' }
] as unknown as Connection[];

function form(overrides: Partial<DataTransferForm> = {}): DataTransferForm {
  return {
    ...EMPTY_DATA_TRANSFER_FORM,
    sourceConnectionId: 1,
    sourceTable: 'orders',
    targetConnectionId: 2,
    targetTable: 'orders',
    ...overrides
  };
}

describe('canRunTransfer', () => {
  it('要求源、目标连接与目标表都填齐', () => {
    expect(canRunTransfer(form())).toBe(true);
    expect(canRunTransfer(form({ targetConnectionId: undefined }))).toBe(false);
    expect(canRunTransfer(form({ targetTable: '  ' }))).toBe(false);
  });

  it('查询模式看的是 SQL 而不是表名', () => {
    expect(canRunTransfer(form({ sourceMode: 'query', sourceTable: 'orders', sourceSql: '' }))).toBe(false);
    expect(canRunTransfer(form({ sourceMode: 'query', sourceTable: '', sourceSql: 'SELECT 1' }))).toBe(true);
  });

  /** 整表插进它自己会让行数翻倍且毫无提示，比报错难查得多。 */
  it('拦下同一条连接上的同表自我复制', () => {
    const selfCopy = form({ targetConnectionId: 1, targetTable: 'ORDERS' });
    expect(isSelfCopy(selfCopy)).toBe(true);
    expect(canRunTransfer(selfCopy)).toBe(false);
  });

  it('同名不同 Schema 不算自我复制', () => {
    expect(isSelfCopy(form({ targetConnectionId: 1, targetSchema: 'archive' }))).toBe(false);
  });

  /** 自定义查询回填同一张表是有意义的操作（例如按条件补历史数据），不该被拦。 */
  it('查询模式不受自我复制判定影响', () => {
    const sameTable = form({ sourceMode: 'query', sourceSql: 'SELECT * FROM orders WHERE id > 100', targetConnectionId: 1 });
    expect(isSelfCopy(sameTable)).toBe(false);
    expect(canRunTransfer(sameTable)).toBe(true);
  });
});

describe('buildDataTransferRequest', () => {
  it('整表模式不带上源 SQL', () => {
    const request = buildDataTransferRequest(form({ sourceSql: 'SELECT 1' }));
    expect(request.sourceTable).toBe('orders');
    expect(request.sourceSql).toBeUndefined();
  });

  /** 留着上一次输入的表名，只会让审计明细里写着一个根本没用上的表。 */
  it('查询模式不带上源表名', () => {
    const request = buildDataTransferRequest(form({ sourceMode: 'query', sourceSql: 'SELECT 1' }));
    expect(request.sourceSql).toBe('SELECT 1');
    expect(request.sourceTable).toBeUndefined();
  });

  it('空 Schema 发 undefined 而不是空串，交给连接的默认命名空间', () => {
    const request = buildDataTransferRequest(form({ sourceSchema: '  ', targetSchema: 'shop' }));
    expect(request.sourceSchema).toBeUndefined();
    expect(request.targetSchema).toBe('shop');
  });

  it('缺目标表时报错而不是发一个空表名', () => {
    expect(() => buildDataTransferRequest(form({ targetTable: '' }))).toThrow('目标表');
  });
});

describe('describeTransfer', () => {
  it('把方向写清楚，包括两侧的连接名与 Schema', () => {
    expect(describeTransfer(form({ sourceSchema: 'shop', targetSchema: 'ods' }), CONNECTIONS))
      .toBe('从「生产库」的 shop.orders 到「分析库」的 ods.orders');
  });

  it('查询模式不假装知道源表叫什么', () => {
    expect(describeTransfer(form({ sourceMode: 'query', sourceSql: 'SELECT 1' }), CONNECTIONS))
      .toContain('的 查询结果');
  });
});

describe('sourceConfirmationName', () => {
  it('只有生产源连接才要确认', () => {
    expect(sourceConfirmationName(form(), CONNECTIONS)).toBe('生产库');
    expect(sourceConfirmationName(form({ sourceConnectionId: 2 }), CONNECTIONS)).toBeUndefined();
  });
});

describe('withSourceTable', () => {
  it('目标表还跟着源表时一起改名', () => {
    expect(withSourceTable(form(), 'items').targetTable).toBe('items');
  });

  it('用户已经改过目标表就不再覆盖', () => {
    expect(withSourceTable(form({ targetTable: 'orders_copy' }), 'items').targetTable).toBe('orders_copy');
  });
});
