import { describe, expect, it } from 'vitest';
import {
  objectPreferenceKey,
  readFavoriteConnectionIds,
  readFavoriteObjectKeys,
  rememberRecentObject,
  writeFavoriteConnectionIds,
  writeFavoriteObjectKeys
} from './workspacePreferences';

function memoryStorage(initial: Record<string, string> = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value)
  };
}

describe('workspacePreferences', () => {
  it('去重并忽略无效的连接收藏', () => {
    const storage = memoryStorage();
    writeFavoriteConnectionIds([2, 2, -1, 3], storage);
    expect(readFavoriteConnectionIds(storage)).toEqual([2, 3]);
  });

  it('遇到损坏的本地数据时安全降级', () => {
    const storage = memoryStorage({ 'db-admin-favorite-objects-v1': '{bad json' });
    expect(readFavoriteObjectKeys(storage)).toEqual([]);
    writeFavoriteObjectKeys(['one', 'one', 'two'], storage);
    expect(readFavoriteObjectKeys(storage)).toEqual(['one', 'two']);
  });

  it('将最近访问对象移到顶部并限制重复项', () => {
    const storage = memoryStorage();
    const table = { schemaName: 'public', name: 'users', type: 'TABLE' };
    const first = rememberRecentObject([], 7, table, 10, storage);
    const second = rememberRecentObject(first, 7, table, 20, storage);
    expect(second).toHaveLength(1);
    expect(second[0]).toMatchObject({ key: objectPreferenceKey(7, table), visitedAt: 20 });
  });
});
