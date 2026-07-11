export type AsyncResourceCacheOptions = {
  /** Time before a loaded value expires. Use 0 to disable expiration. */
  ttlMs?: number;
  /** Optional clock override for deterministic tests. */
  now?: () => number;
};

type CacheEntry<Value> = {
  value: Value;
  expiresAt?: number;
};

/**
 * A small cache that coalesces concurrent loads for the same key. It is useful
 * for lazy metadata/column loading where several completion requests may race.
 */
export class AsyncResourceCache<Key, Value> {
  private readonly values = new Map<Key, CacheEntry<Value>>();
  private readonly inFlight = new Map<Key, Promise<Value>>();
  private readonly keyVersions = new Map<Key, number>();
  private readonly ttlMs: number;
  private readonly now: () => number;
  private generation = 0;

  constructor(options: AsyncResourceCacheOptions = {}) {
    this.ttlMs = Math.max(0, options.ttlMs ?? 0);
    this.now = options.now ?? Date.now;
  }

  get size(): number {
    this.deleteExpired();
    return this.values.size;
  }

  get pendingCount(): number {
    return this.inFlight.size;
  }

  has(key: Key): boolean {
    return this.validEntry(key) !== undefined;
  }

  get(key: Key): Value | undefined {
    return this.validEntry(key)?.value;
  }

  set(key: Key, value: Value): Value {
    this.keyVersions.set(key, (this.keyVersions.get(key) ?? 0) + 1);
    this.inFlight.delete(key);
    return this.store(key, value);
  }

  private store(key: Key, value: Value): Value {
    this.values.set(key, {
      value,
      expiresAt: this.ttlMs > 0 ? this.now() + this.ttlMs : undefined
    });
    return value;
  }

  /** Returns a cached value or shares one loader promise between all callers. */
  load(key: Key, loader: () => Promise<Value>): Promise<Value> {
    const cached = this.validEntry(key);
    if (cached) return Promise.resolve(cached.value);

    const pending = this.inFlight.get(key);
    if (pending) return pending;

    const generation = this.generation;
    const keyVersion = this.keyVersions.get(key) ?? 0;
    const request = Promise.resolve()
      .then(loader)
      .then((value) => {
        if (this.generation === generation && (this.keyVersions.get(key) ?? 0) === keyVersion) {
          this.store(key, value);
        }
        return value;
      })
      .finally(() => {
        if (this.inFlight.get(key) === request) this.inFlight.delete(key);
      });
    this.inFlight.set(key, request);
    return request;
  }

  delete(key: Key): boolean {
    this.keyVersions.set(key, (this.keyVersions.get(key) ?? 0) + 1);
    this.inFlight.delete(key);
    return this.values.delete(key);
  }

  clear(): void {
    this.generation += 1;
    this.values.clear();
    this.keyVersions.clear();
    // Existing callers still receive their promises, but stale results cannot
    // repopulate the cache after a schema refresh.
    this.inFlight.clear();
  }

  keys(): Key[] {
    this.deleteExpired();
    return [...this.values.keys()];
  }

  private deleteExpired(): void {
    const now = this.now();
    for (const [key, entry] of this.values) {
      if (entry.expiresAt !== undefined && entry.expiresAt <= now) this.values.delete(key);
    }
  }

  private validEntry(key: Key): CacheEntry<Value> | undefined {
    const entry = this.values.get(key);
    if (!entry) return undefined;
    if (entry.expiresAt !== undefined && entry.expiresAt <= this.now()) {
      this.values.delete(key);
      return undefined;
    }
    return entry;
  }
}
