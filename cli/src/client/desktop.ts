import { readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { resolveDesktopPaths } from '../../../desktop/src/paths.js';

export interface DesktopRuntimeEndpoint {
  pid: number;
  port: number;
  version?: string;
  startedAt?: string;
}

export interface DiscoveredDesktop {
  url: string;
  pid: number;
  port: number;
  version?: string;
  endpointFile: string;
}

export function resolveDesktopRuntimeFile(
  platform: NodeJS.Platform = process.platform,
  env: NodeJS.ProcessEnv = process.env,
  userHome: string = os.homedir()
): string {
  const desktopPaths = resolveDesktopPaths(platform, env, userHome);
  const platformPath = platform === 'win32' ? path.win32 : path.posix;
  return platformPath.join(desktopPaths.home, 'runtime', 'cli-endpoint.json');
}

export function isProcessAlive(pid: number, killFn: (pid: number, signal: number) => void = process.kill): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    killFn(pid, 0);
    return true;
  } catch (error) {
    const code = (error as NodeJS.ErrnoException)?.code;
    // On Windows and POSIX, EPERM indicates the process exists but cannot be signaled.
    if (code === 'EPERM') return true;
    return false;
  }
}

export async function discoverDesktopInstance(options?: {
  platform?: NodeJS.Platform;
  env?: NodeJS.ProcessEnv;
  userHome?: string;
  isAlive?: (pid: number) => boolean;
}): Promise<DiscoveredDesktop | null> {
  const platform = options?.platform ?? process.platform;
  const env = options?.env ?? process.env;
  const userHome = options?.userHome ?? os.homedir();
  const aliveCheck = options?.isAlive ?? isProcessAlive;
  const endpointFile = resolveDesktopRuntimeFile(platform, env, userHome);

  let raw: string;
  try {
    raw = await readFile(endpointFile, 'utf8');
  } catch {
    return null;
  }

  let parsed: Partial<DesktopRuntimeEndpoint>;
  try {
    parsed = JSON.parse(raw) as Partial<DesktopRuntimeEndpoint>;
  } catch {
    return null;
  }

  if (
    typeof parsed.pid !== 'number' ||
    typeof parsed.port !== 'number' ||
    !Number.isInteger(parsed.port) ||
    parsed.port <= 0 ||
    parsed.port > 65535
  ) {
    return null;
  }

  if (!aliveCheck(parsed.pid)) {
    return null;
  }

  return {
    url: `http://127.0.0.1:${parsed.port}`,
    pid: parsed.pid,
    port: parsed.port,
    version: typeof parsed.version === 'string' ? parsed.version : undefined,
    endpointFile
  };
}
