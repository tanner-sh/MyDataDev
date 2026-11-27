import { chmod, mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import type { CliConfigFile } from '../types.js';

export function resolveConfigPath(
  platform: NodeJS.Platform = process.platform,
  env: NodeJS.ProcessEnv = process.env,
  userHome: string = os.homedir()
): string {
  if (env.MDD_CONFIG && env.MDD_CONFIG.trim()) {
    return path.resolve(env.MDD_CONFIG.trim());
  }
  if (platform === 'win32') {
    const base = env.LOCALAPPDATA || path.win32.join(userHome, 'AppData', 'Local');
    return path.win32.join(base, 'MyDataDev', 'cli.json');
  }
  const base = env.XDG_CONFIG_HOME || path.posix.join(userHome, '.config');
  return path.posix.join(base, 'mydatadev', 'cli.json');
}

export async function loadConfig(configPath: string = resolveConfigPath()): Promise<CliConfigFile> {
  try {
    const raw = await readFile(configPath, 'utf8');
    const parsed = JSON.parse(raw) as Partial<CliConfigFile>;
    return {
      defaultProfile: typeof parsed.defaultProfile === 'string' ? parsed.defaultProfile : undefined,
      profiles: parsed.profiles && typeof parsed.profiles === 'object' ? parsed.profiles : {}
    };
  } catch (error) {
    if ((error as NodeJS.ErrnoException)?.code === 'ENOENT') {
      return { profiles: {} };
    }
    throw error;
  }
}

export async function saveConfig(
  config: CliConfigFile,
  configPath: string = resolveConfigPath(),
  platform: NodeJS.Platform = process.platform
): Promise<void> {
  const dir = path.dirname(configPath);
  await mkdir(dir, { recursive: true, mode: platform === 'win32' ? undefined : 0o700 });
  const tempPath = `${configPath}.${process.pid}.${Date.now()}.tmp`;
  await writeFile(tempPath, JSON.stringify(config, null, 2) + '\n', {
    encoding: 'utf8',
    mode: platform === 'win32' ? undefined : 0o600
  });
  await rename(tempPath, configPath);
  if (platform === 'win32') {
    restrictWindowsAcl(configPath);
  } else {
    await chmod(configPath, 0o600).catch(() => undefined);
  }
}

export function restrictWindowsAcl(filePath: string): void {
  const user = process.env.USERNAME || process.env.USER;
  if (!user) return;
  try {
    spawnSync('icacls', [filePath, '/inheritance:r', '/grant:r', `${user}:(R,W)`], {
      stdio: 'ignore',
      windowsHide: true
    });
  } catch {
    // Best-effort ACL tightening on Windows
  }
}
