import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { discoverDesktopInstance, isProcessAlive, resolveDesktopRuntimeFile } from '../src/client/desktop.js';
import { resolveInstance } from '../src/client/instance.js';
import { ExitCode } from '../src/errors.js';
import type { CliConfigFile } from '../src/types.js';

describe('resolveInstance', () => {
  const sampleConfig: CliConfigFile = {
    defaultProfile: 'team',
    profiles: {
      team: {
        url: 'https://team.example.com',
        token: 'mdd_pat_team_secret',
        headers: { 'X-Gateway': 'team' }
      },
      staging: {
        url: 'https://staging.example.com',
        token: 'mdd_pat_staging_secret'
      }
    }
  };

  it('binds credentials strictly to the chosen profile', async () => {
    const resolved = await resolveInstance(
      { profile: 'staging' },
      { config: sampleConfig, env: { MDD_TOKEN: 'mdd_pat_env_ignored' } }
    );
    expect(resolved.url).toBe('https://staging.example.com');
    expect(resolved.token).toBe('mdd_pat_staging_secret');
    expect(resolved.source).toBe('profile');
  });

  it('does not inherit profile credentials when --server is passed', async () => {
    const resolved = await resolveInstance(
      { server: 'https://other.example.com/' },
      { config: sampleConfig, env: {} }
    );
    expect(resolved.url).toBe('https://other.example.com');
    expect(resolved.token).toBeUndefined();
    expect(resolved.headers).toBeUndefined();
    expect(resolved.source).toBe('flag');
  });

  it('rejects MDD_TOKEN without MDD_SERVER or --server', async () => {
    await expect(
      resolveInstance({}, { config: sampleConfig, env: { MDD_TOKEN: 'mdd_pat_orphan_secret' } })
    ).rejects.toMatchObject({
      exitCode: ExitCode.USAGE_ERROR,
      code: 'TOKEN_WITHOUT_SERVER'
    });
  });

  it('uses MDD_SERVER and MDD_TOKEN as a pair over default profile', async () => {
    const resolved = await resolveInstance(
      {},
      {
        config: sampleConfig,
        env: { MDD_SERVER: 'https://ci.example.com', MDD_TOKEN: 'mdd_pat_ci_secret' }
      }
    );
    expect(resolved.url).toBe('https://ci.example.com');
    expect(resolved.token).toBe('mdd_pat_ci_secret');
    expect(resolved.source).toBe('env');
  });

  it('falls back to desktop runtime when no profile or server is configured', async () => {
    const resolved = await resolveInstance(
      {},
      {
        config: { profiles: {} },
        env: {},
        discoverDesktop: async () => ({
          url: 'http://127.0.0.1:5173',
          pid: process.pid,
          port: 5173,
          endpointFile: '/tmp/cli-endpoint.json'
        })
      }
    );
    expect(resolved.label).toBe('local');
    expect(resolved.url).toBe('http://127.0.0.1:5173');
    expect(resolved.source).toBe('desktop');
  });

  it('returns exit code 5 with helpful prompt when desktop is not running', async () => {
    await expect(
      resolveInstance(
        {},
        {
          config: { profiles: {} },
          env: {},
          discoverDesktop: async () => null
        }
      )
    ).rejects.toMatchObject({
      exitCode: ExitCode.UNREACHABLE,
      code: 'DESKTOP_NOT_RUNNING'
    });
  });
});

describe('desktop runtime discovery', () => {
  it('uses LOCALAPPDATA on Windows consistently with Desktop paths', () => {
    const runtimePath = resolveDesktopRuntimeFile(
      'win32',
      { LOCALAPPDATA: 'C:\\Users\\tanner\\AppData\\Local' },
      'C:\\Users\\tanner'
    );
    expect(runtimePath).toBe('C:\\Users\\tanner\\AppData\\Local\\MyDataDev\\runtime\\cli-endpoint.json');
  });

  it('treats EPERM as alive process for Windows compatibility', () => {
    const alive = isProcessAlive(1234, () => {
      const err = new Error('Operation not permitted') as NodeJS.ErrnoException;
      err.code = 'EPERM';
      throw err;
    });
    expect(alive).toBe(true);

    const dead = isProcessAlive(1234, () => {
      const err = new Error('No such process') as NodeJS.ErrnoException;
      err.code = 'ESRCH';
      throw err;
    });
    expect(dead).toBe(false);
  });

  it('ignores stale runtime file when pid is not alive', async () => {
    const home = await mkdtemp(path.join(os.tmpdir(), 'mdd-desktop-test-'));
    try {
      const runtimeDir = path.join(home, 'runtime');
      await mkdir(runtimeDir, { recursive: true });
      await writeFile(
        path.join(runtimeDir, 'cli-endpoint.json'),
        JSON.stringify({ pid: 999999, port: 5173, version: '0.10.2' }),
        'utf8'
      );
      const stale = await discoverDesktopInstance({
        env: { MYDATADEV_DESKTOP_HOME: home },
        isAlive: () => false
      });
      expect(stale).toBeNull();

      const live = await discoverDesktopInstance({
        env: { MYDATADEV_DESKTOP_HOME: home },
        isAlive: () => true
      });
      expect(live?.url).toBe('http://127.0.0.1:5173');
    } finally {
      await rm(home, { recursive: true, force: true });
    }
  });
});
