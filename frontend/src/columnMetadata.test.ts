import { describe, expect, it, vi } from 'vitest';
import { createColumnMetadataLoader, findColumnMetadata } from './columnMetadata';
import type { ObjectColumns } from './types';

const data: ObjectColumns = { name: 'USERS', type: 'TABLE', columns: [] };
describe('共享字段元数据缓存', () => {
  it('补全和悬停合并请求，按连接和精确 Schema 隔离', async () => {
    const fetcher = vi.fn(async () => data);
    const cache = createColumnMetadataLoader(fetcher);
    const identity = { schemaName: 'Case', name: 'USERS' };
    await Promise.all([cache.load(1, identity), cache.load(1, identity)]);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await cache.load(2, identity);
    await cache.load(1, { ...identity, schemaName: 'case' });
    expect(fetcher).toHaveBeenCalledTimes(3);
  });
  it('失败可重试，刷新期间的旧响应不能填回缓存', async () => {
    let resolve!: (value: ObjectColumns) => void;
    const fetcher = vi.fn<() => Promise<ObjectColumns>>().mockRejectedValueOnce(new Error('offline'))
      .mockImplementationOnce(() => new Promise(done => { resolve = done; })).mockResolvedValue(data);
    const cache = createColumnMetadataLoader(fetcher);
    await expect(cache.load(1, data)).rejects.toThrow('offline');
    const pending = cache.load(1, data);
    await Promise.resolve();
    cache.clear();
    resolve(data);
    await pending;
    await cache.load(1, data);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });
  it('优先匹配精确字段名，大小写有歧义时不猜测', () => {
    const columns = ['Code', 'code'].map(name => ({ name, type: 'VARCHAR', size: 20, nullable: true, remarks: name }));
    expect(findColumnMetadata(columns, 'Code')?.remarks).toBe('Code');
    expect(findColumnMetadata(columns, 'CODE')).toBeUndefined();
  });
});
