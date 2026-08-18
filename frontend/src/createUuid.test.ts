import { describe, expect, it, vi } from 'vitest';
import { createUuid } from './createUuid';

describe('createUuid', () => {
  it('优先使用浏览器原生 randomUUID', () => {
    vi.stubGlobal('crypto', { randomUUID: () => 'native-uuid' });
    expect(createUuid()).toBe('native-uuid');
    vi.unstubAllGlobals();
  });

  it('HTTP 非安全上下文没有 randomUUID 时仍生成 UUID v4', () => {
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => { bytes.fill(0); return bytes; } });
    expect(createUuid()).toBe('00000000-0000-4000-8000-000000000000');
    vi.unstubAllGlobals();
  });
});
