import { chmod, mkdir, readFile, realpath, rename, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { lock } from 'proper-lockfile';
import { CliError, ExitCode } from '../errors.js';
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

export async function updateConfig<T>(configPath: string, change: (config: CliConfigFile) => T): Promise<T> {
  const directory = path.dirname(configPath);
  await mkdir(directory, { recursive: true, mode: process.platform === 'win32' ? undefined : 0o700 });
  const file = path.join(await realpath(directory), path.basename(configPath));
  const release = await lock(file, {
    realpath: false,
    retries: { retries: 30, minTimeout: 25, maxTimeout: 500 }
  }).catch((error: NodeJS.ErrnoException) => {
    if (error.code !== 'ELOCKED') throw error;
    throw new CliError(ExitCode.EXECUTION_FAILED, 'PROFILE_CONFIG_BUSY', 'CLI 配置正在被另一个进程更新，请稍后重试。');
  });
  try {
    const config = await loadConfig(file);
    const result = change(config);
    await saveConfig(config, file);
    return result;
  } finally {
    await release();
  }
}

async function saveConfig(
  config: CliConfigFile,
  configPath: string = resolveConfigPath(),
  platform: NodeJS.Platform = process.platform
): Promise<void> {
  const tempPath = `${configPath}.${process.pid}.${Date.now()}.tmp`;
  try {
    await writeFile(tempPath, JSON.stringify(config, null, 2) + '\n', {
      encoding: 'utf8',
      mode: platform === 'win32' ? undefined : 0o600
    });
    await rename(tempPath, configPath);
  } catch (error) {
    await rm(tempPath, { force: true }).catch(() => undefined);
    throw error;
  }
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
