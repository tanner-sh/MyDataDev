import { describe, expect, it, vi } from 'vitest';
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

  it('shares an intent prefetch with the later foreground load', async () => {
    const cache = new AsyncResourceCache<string, number>();
    const loader = vi.fn(async () => 42);

    const prefetched = cache.load('object-detail', loader);
    const opened = cache.load('object-detail', loader);

    await expect(Promise.all([prefetched, opened])).resolves.toEqual([42, 42]);
    expect(loader).toHaveBeenCalledTimes(1);
  });

  it('does not let a deleted in-flight value overwrite an explicit refresh', async () => {
    const cache = new AsyncResourceCache<string, number>();
    let resolve!: (value: number) => void;
    const stale = cache.load('object-detail', () => new Promise<number>((done) => { resolve = done; }));
    await Promise.resolve();

    cache.delete('object-detail');
    const refreshed = cache.load('object-detail', async () => 2);
    resolve(1);

    await expect(stale).resolves.toBe(1);
    await expect(refreshed).resolves.toBe(2);
    expect(cache.get('object-detail')).toBe(2);
  });

  it('can inspect a value without promoting it in the eviction order', () => {
    const cache = new AsyncResourceCache<string, number>({ maxEntries: 2 });
    cache.set('a', 1);
    cache.set('b', 2);

    expect(cache.peek('a')).toBe(1);
    cache.set('c', 3);

    expect(cache.has('a')).toBe(false);
    expect(cache.has('b')).toBe(true);
    expect(cache.has('c')).toBe(true);
  });
});
