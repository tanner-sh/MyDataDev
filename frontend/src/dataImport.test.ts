import { describe, expect, it } from 'vitest';
import {
  backgroundImportPath,
  IMPORT_CONFLICT_OPTIONS,
  formatImportSize,
  importFileExtension,
  importRoute,
  INLINE_IMPORT_MAX_BYTES,
  oversizedRowsRoute
} from './dataImport';

const small = { name: 'orders.csv', size: 2048 };

describe('importRoute', () => {
  it('小 CSV 仍走浏览器解析，用户能先核对再提交', () => {
    expect(importRoute(small, 0, 1000)).toEqual({ kind: 'inline' });
  });

  it('超过内存解析上限的 CSV 改走后台，并说明原因', () => {
    const route = importRoute({ name: 'big.csv', size: INLINE_IMPORT_MAX_BYTES + 1 }, 0, 1000);
    expect(route.kind).toBe('background');
    expect(route.kind === 'background' && route.reason).toContain('10.0 MB');
  });

  it('待提交变更已满时，小 CSV 也走后台而不是报错', () => {
    // 之前这里直接抛「单次最多提交 1000 项变更」，用户只能自己拆文件。
    const route = importRoute(small, 1000, 1000);
    expect(route.kind).toBe('background');
    expect(route.kind === 'background' && route.reason).toContain('1000 项');
  });

  it('大 SQL 文件指向 SQL 文件执行入口，而不是笼统地说太大', () => {
    const route = importRoute({ name: 'dump.sql', size: INLINE_IMPORT_MAX_BYTES + 1 }, 0, 1000);
    expect(route.kind).toBe('unsupported');
    expect(route.kind === 'unsupported' && route.message).toContain('SQL 文件执行');
  });

  it('大 JSON 提示转成 CSV', () => {
    const route = importRoute({ name: 'rows.json', size: INLINE_IMPORT_MAX_BYTES + 1 }, 0, 1000);
    expect(route.kind === 'unsupported' && route.message).toContain('CSV');
  });

  it('小 JSON / SQL 照旧走浏览器解析', () => {
    expect(importRoute({ name: 'rows.json', size: 100 }, 0, 1000)).toEqual({ kind: 'inline' });
    expect(importRoute({ name: 'rows.sql', size: 100 }, 0, 1000)).toEqual({ kind: 'inline' });
  });

  /** Excel 由服务端解析（日期这类信息只写在样式里），所以它一律走后台，不看大小。 */
  it('Excel 一律走后台导入', () => {
    const route = importRoute({ name: 'data.xlsx', size: 10 }, 0, 1000);

    expect(route.kind).toBe('background');
    expect(route.kind === 'background' && route.format).toBe('xlsx');
    expect(importRoute({ name: 'big.xlsx', size: 50 * 1024 * 1024 }, 0, 1000).kind).toBe('background');
  });

  it('不认识的扩展名直接拒绝', () => {
    expect(importRoute({ name: 'data.parquet', size: 10 }, 0, 1000).kind).toBe('unsupported');
    expect(importRoute({ name: 'noext', size: 10 }, 0, 1000).kind).toBe('unsupported');
  });

  it('扩展名大小写不敏感', () => {
    expect(importRoute({ name: 'ORDERS.CSV', size: 10 }, 0, 1000)).toEqual({ kind: 'inline' });
  });
});

describe('importFileExtension', () => {
  it('取最后一段，且没有扩展名时返回空串', () => {
    expect(importFileExtension('a.b.csv')).toBe('csv');
    expect(importFileExtension('plain')).toBe('');
    expect(importFileExtension('.gitignore')).toBe('gitignore');
  });
});

describe('formatImportSize', () => {
  it('按量级选单位', () => {
    expect(formatImportSize(512)).toBe('512 B');
    expect(formatImportSize(2048)).toBe('2 KB');
    expect(formatImportSize(3 * 1024 * 1024)).toBe('3.0 MB');
  });
});

describe('backgroundImportPath', () => {
  it('带上目标表，并对中文与特殊字符转义', () => {
    const path = backgroundImportPath({ connectionId: 7, schemaName: 'shop', tableName: '订单', fileName: 'a b.csv' });
    expect(path).toContain('connectionId=7');
    expect(path).toContain('schemaName=shop');
    expect(path).toContain(`tableName=${encodeURIComponent('订单')}`);
    expect(path).not.toContain('a b.csv');
  });

  it('没有 schema 时不发送空参数，交给连接默认命名空间', () => {
    const path = backgroundImportPath({ connectionId: 1, tableName: 't', fileName: 'a.csv' });
    expect(path).not.toContain('schemaName');
  });
});

describe('oversizedRowsRoute', () => {
  it('小体积但行数超限的 CSV 改走后台，而不是变成一条死胡同提示', () => {
    // 100 KB、1001 行的 CSV 按体积判定会先走 inline，解析完才撞上 1000 项变更上限；
    // 以前这里只报错，而那句「改用 CSV 走后台导入」对一份 CSV 来说无处可去。
    const route = oversizedRowsRoute({ name: 'orders.csv', size: 100 * 1024 }, 1001, 1000);
    expect(route.kind).toBe('background');
    expect(route.kind === 'background' && route.reason).toContain('1001 行');
  });

  it('JSON 没有后台通道，如实告诉用户要转存 CSV', () => {
    const route = oversizedRowsRoute({ name: 'orders.json', size: 100 * 1024 }, 1001, 1000);
    expect(route.kind).toBe('unsupported');
    expect(route.kind === 'unsupported' && route.message).toContain('CSV');
  });
});

describe('backgroundImportPath 的格式分流', () => {
  const base = { connectionId: 7, tableName: 'orders', fileName: 'a.csv' };

  it('CSV 与 Excel 走各自的上传端点', () => {
    expect(backgroundImportPath(base)).toContain('/sql-file-executions/csv-imports?');
    expect(backgroundImportPath({ ...base, format: 'xlsx', fileName: 'a.xlsx' }))
      .toContain('/sql-file-executions/xlsx-imports?');
  });

  it('未指定格式时按 CSV 处理，保持旧行为', () => {
    expect(backgroundImportPath(base)).toContain('csv-imports');
  });
});

describe('导入冲突策略', () => {
  const base = { connectionId: 7, tableName: 'orders', fileName: 'a.csv' };

  /** 默认那一档不进 URL：老客户端与新客户端发出的请求因此完全一样。 */
  it('只有非默认策略才出现在上传地址里', () => {
    expect(backgroundImportPath(base)).not.toContain('conflictMode');
    expect(backgroundImportPath({ ...base, conflictMode: 'INSERT' })).not.toContain('conflictMode');
    expect(backgroundImportPath({ ...base, conflictMode: 'SKIP' })).toContain('conflictMode=SKIP');
    expect(backgroundImportPath({ ...base, conflictMode: 'UPSERT' })).toContain('conflictMode=UPSERT');
  });

  it('三档都有说明，默认是最安全的那档', () => {
    expect(IMPORT_CONFLICT_OPTIONS.map((option) => option.value)).toEqual(['INSERT', 'SKIP', 'UPSERT']);
    expect(IMPORT_CONFLICT_OPTIONS.every((option) => option.hint.length > 0)).toBe(true);
  });
});
