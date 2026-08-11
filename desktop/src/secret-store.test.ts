import { mkdtemp, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { loadOrCreateDesktopSecret, type SafeStorageAdapter } from './secret-store.js';

function storage(backend = 'darwin'): SafeStorageAdapter {
  return {
    available: async () => true,
    encrypt: async (value) => Buffer.from(`protected:${value}`),
    decrypt: async (value) => value.toString().replace(/^protected:/, ''),
    backend: () => backend
  };
}

describe('desktop secret store', () => {
  it('creates and then reuses an encrypted desktop key', async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'mydatadev-secret-'));
    const secretFile = path.join(directory, 'secret.json');

    const created = await loadOrCreateDesktopSecret(secretFile, storage(), () => 'generated-key');
    const reused = await loadOrCreateDesktopSecret(secretFile, storage(), () => 'different-key');
    const stored = await readFile(secretFile, 'utf8');

    expect(created.cryptoKey).toBe('generated-key');
    expect(reused.cryptoKey).toBe('generated-key');
    expect(stored).not.toContain('generated-key');
  });

  it('reports the Linux plaintext fallback', async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'mydatadev-secret-'));
    const result = await loadOrCreateDesktopSecret(path.join(directory, 'secret.json'), storage('basic_text'));
    expect(result.insecureLinuxFallback).toBe(true);
  });
});
