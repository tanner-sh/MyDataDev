import { describe, expect, it } from 'vitest';
import type { Connection } from './types';
import { readSelectedConnectionId, resolveSelectedConnection, SELECTED_CONNECTION_STORAGE_KEY, writeSelectedConnectionId } from './selectedConnectionStorage';

function memoryStorage(initial?: string) {
  const values = new Map<string, string>();
  if (initial !== undefined) values.set(SELECTED_CONNECTION_STORAGE_KEY, initial);
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); }
  };
}

const connections = [
  { id: 1, name: 'one' },
  { id: 2, name: 'two' },
  { id: 3, name: 'three' }
] as Connection[];

describe('selected connection storage', () => {
  it('reads, writes and clears a valid connection id', () => {
    const storage = memoryStorage();
    writeSelectedConnectionId(2, storage);
    expect(readSelectedConnectionId(storage)).toBe(2);
    writeSelectedConnectionId(null, storage);
    expect(readSelectedConnectionId(storage)).toBeUndefined();
  });

  it('ignores invalid or unavailable storage values', () => {
    expect(readSelectedConnectionId(memoryStorage('not-a-number'))).toBeUndefined();
    expect(readSelectedConnectionId(memoryStorage('-1'))).toBeUndefined();
    const unavailable = { getItem: () => { throw new Error('blocked'); }, setItem: () => undefined, removeItem: () => undefined };
    expect(readSelectedConnectionId(unavailable)).toBeUndefined();
  });

  it('uses explicit, current, persisted and first connection in priority order', () => {
    expect(resolveSelectedConnection(connections, [3, 2, 1])?.id).toBe(3);
    expect(resolveSelectedConnection(connections, [99, 2, 1])?.id).toBe(2);
    expect(resolveSelectedConnection(connections, [99, 98])?.id).toBe(1);
    expect(resolveSelectedConnection([], [1])).toBeNull();
  });
});
