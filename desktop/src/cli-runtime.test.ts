import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { removeCliRuntimeEndpoint, runtimeEndpointPath, writeCliRuntimeEndpoint } from './cli-runtime.js';
import { resolveDesktopPaths } from './paths.js';

describe('desktop CLI runtime endpoint', () => {
  it('writes with restricted permissions and removes cleanly on shutdown', async () => {
    const tempHome = await mkdtemp(path.join(os.tmpdir(), 'mdd-desktop-rt-'));
    try {
      const paths = resolveDesktopPaths(process.platform, { MYDATADEV_DESKTOP_HOME: tempHome }, os.homedir());
      const written = await writeCliRuntimeEndpoint(paths, {
        pid: process.pid,
        port: 5173,
        version: '0.10.2'
      });
      expect(written).toBe(runtimeEndpointPath(paths));
      const parsed = JSON.parse(await readFile(written, 'utf8')) as {
        pid: number;
        port: number;
        version: string;
        startedAt: string;
      };
      expect(parsed.pid).toBe(process.pid);
      expect(parsed.port).toBe(5173);
      expect(parsed.version).toBe('0.10.2');
      expect(parsed.startedAt).toBeTruthy();

      if (process.platform !== 'win32') {
        const mode = (await stat(written)).mode & 0o777;
        expect(mode).toBe(0o600);
      }

      await removeCliRuntimeEndpoint(paths);
      await expect(readFile(written, 'utf8')).rejects.toThrow();
    } finally {
      await rm(tempHome, { recursive: true, force: true });
    }
  });
});
