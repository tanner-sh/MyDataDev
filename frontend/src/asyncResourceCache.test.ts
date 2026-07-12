import { describe, expect, it } from 'vitest';
import { AsyncResourceCache } from './asyncResourceCache';

describe('AsyncResourceCache', () => {
  it('evicts the least recently used value at the configured bound', () => {
    const cache = new AsyncResourceCache<string, number>({ maxEntries: 2 });
    cache.set('a', 1);
    cache.set('b', 2);
    expect(cache.get('a')).toBe(1);
    cache.set('c', 3);

    expect(cache.has('a')).toBe(true);
    expect(cache.has('b')).toBe(false);
    expect(cache.has('c')).toBe(true);
    expect(cache.size).toBe(2);
  });

  it('does not let a stale in-flight request repopulate a cleared cache', async () => {
    const cache = new AsyncResourceCache<string, number>();
    let resolve!: (value: number) => void;
    const pending = cache.load('a', () => new Promise<number>((done) => { resolve = done; }));
    await Promise.resolve();
    cache.clear();
    resolve(1);

    await expect(pending).resolves.toBe(1);
    expect(cache.has('a')).toBe(false);
  });
});
