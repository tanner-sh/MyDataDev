import { chmod, mkdir, rename, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import type { DesktopPaths } from './paths.js';

export type CliRuntimeEndpoint = {
  pid: number;
  port: number;
  version: string;
  startedAt: string;
};

export function runtimeEndpointPath(desktopPaths: DesktopPaths): string {
  return path.join(desktopPaths.runtime, 'cli-endpoint.json');
}

export async function writeCliRuntimeEndpoint(
  desktopPaths: DesktopPaths,
  endpoint: Omit<CliRuntimeEndpoint, 'startedAt'> & { startedAt?: string },
  platform: NodeJS.Platform = process.platform
): Promise<string> {
  const target = runtimeEndpointPath(desktopPaths);
  const payload: CliRuntimeEndpoint = {
    pid: endpoint.pid,
    port: endpoint.port,
    version: endpoint.version,
    startedAt: endpoint.startedAt ?? new Date().toISOString()
  };

  await mkdir(desktopPaths.runtime, { recursive: true, mode: platform === 'win32' ? undefined : 0o700 });
  const temp = `${target}.${process.pid}.${Date.now()}.tmp`;
  await writeFile(temp, `${JSON.stringify(payload, null, 2)}\n`, {
    encoding: 'utf8',
    mode: platform === 'win32' ? undefined : 0o600
  });
  await rename(temp, target);

  if (platform === 'win32') {
    const user = process.env.USERNAME || process.env.USER;
    if (user) {
      try {
        spawnSync('icacls', [target, '/inheritance:r', '/grant:r', `${user}:(R,W)`], {
          stdio: 'ignore',
          windowsHide: true
        });
      } catch {
        // Best-effort ACL restriction on Windows
      }
    }
  } else {
    await chmod(target, 0o600).catch(() => undefined);
  }
  return target;
}

export async function removeCliRuntimeEndpoint(desktopPaths: DesktopPaths): Promise<void> {
  await rm(runtimeEndpointPath(desktopPaths), { force: true }).catch(() => undefined);
}
