import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { access, lstat, mkdir, symlink, unlink } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { bundledCliPaths } from './paths.js';

export type CliInstallResult = {
  ok: boolean;
  installedPath?: string;
  needsPathNote?: boolean;
  needsReopenTerminal?: boolean;
  message: string;
};

/**
 * Squirrel 安装目录带版本号（如 %LOCALAPPDATA%\MyDataDev\app-0.10.2\MyDataDev.exe）。
 * 为避免每次升级都让用户 PATH 失效，把 PATH 指向 Squirrel 根目录下不随版本变化的 bin\mdd.cmd 中转脚本。
 */
export function resolveWindowsStableBinDir(execPath: string, resourcesPath: string): {
  binDir: string;
  usesStableShim: boolean;
} {
  const execDir = path.win32.dirname(execPath);
  const folder = path.win32.basename(execDir);
  if (/^app-/i.test(folder)) {
    return {
      binDir: path.win32.join(path.win32.dirname(execDir), 'bin'),
      usesStableShim: true
    };
  }
  return {
    binDir: bundledCliPaths(resourcesPath, 'win32').binDir,
    usesStableShim: false
  };
}

export function buildWindowsShimContent(targetLauncher: string): string {
  return [
    '@echo off',
    `call "${targetLauncher}" %*`,
    'exit /b %ERRORLEVEL%',
    ''
  ].join('\r\n');
}

export function mergeWindowsPath(currentPath: string, entry: string): { next: string; changed: boolean } {
  const normalizedTarget = entry.trim().replace(/[\\/]+$/, '').toLowerCase();
  const segments = currentPath
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean);
  const exists = segments.some((part) => part.replace(/[\\/]+$/, '').toLowerCase() === normalizedTarget);
  if (exists) return { next: segments.join(';'), changed: false };
  return { next: [...segments, entry.trim()].join(';'), changed: true };
}

export function removeWindowsPath(currentPath: string, entry: string): { next: string; changed: boolean } {
  const normalizedTarget = entry.trim().replace(/[\\/]+$/, '').toLowerCase();
  const segments = currentPath
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean);
  const filtered = segments.filter((part) => part.replace(/[\\/]+$/, '').toLowerCase() !== normalizedTarget);
  return { next: filtered.join(';'), changed: filtered.length !== segments.length };
}

function updateWindowsUserPathSync(entry: string, mode: 'add' | 'remove'): boolean {
  const read = spawnSync(
    'powershell.exe',
    ['-NoProfile', '-NonInteractive', '-Command', "[Environment]::GetEnvironmentVariable('Path', 'User')"],
    { encoding: 'utf8', windowsHide: true }
  );
  if (read.status !== 0) return false;
  const current = (read.stdout || '').trim();
  const updated = mode === 'add' ? mergeWindowsPath(current, entry) : removeWindowsPath(current, entry);
  if (!updated.changed) return true;

  // SetEnvironmentVariable('Path', ..., 'User') 会自动写入 HKCU\Environment 并广播 WM_SETTINGCHANGE
  const write = spawnSync(
    'powershell.exe',
    [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      "[Environment]::SetEnvironmentVariable('Path', $env:MDD_NEXT_USER_PATH, 'User')"
    ],
    {
      env: { ...process.env, MDD_NEXT_USER_PATH: updated.next },
      encoding: 'utf8',
      windowsHide: true
    }
  );
  return write.status === 0;
}

/**
 * 处理 Windows Squirrel 安装、升级与卸载事件：
 * 安装/升级时更新稳定的 bin\mdd.cmd 中转脚本并确保 HKCU PATH 包含该目录；卸载时清理。
 */
export function handleSquirrelCliEvent(
  argv: string[],
  execPath: string,
  resourcesPath: string
): boolean {
  if (process.platform !== 'win32') return false;
  const event = argv.find((arg) =>
    arg === '--squirrel-install' || arg === '--squirrel-updated' || arg === '--squirrel-uninstall'
  );
  if (!event) return false;

  try {
    const { binDir, usesStableShim } = resolveWindowsStableBinDir(execPath, resourcesPath);
    const shimPath = path.win32.join(binDir, 'mdd.cmd');
    if (event === '--squirrel-uninstall') {
      updateWindowsUserPathSync(binDir, 'remove');
      if (usesStableShim) rmSync(shimPath, { force: true });
      return true;
    }
    const targetLauncher = bundledCliPaths(resourcesPath, 'win32').launcher;
    if (usesStableShim) {
      mkdirSync(binDir, { recursive: true });
      writeFileSync(shimPath, buildWindowsShimContent(targetLauncher), 'utf8');
    }
    updateWindowsUserPathSync(binDir, 'add');
    return true;
  } catch {
    return false;
  }
}

export async function installCliCommand(options: {
  platform?: NodeJS.Platform;
  execPath: string;
  resourcesPath: string;
  userHome?: string;
}): Promise<CliInstallResult> {
  const platform = options.platform ?? process.platform;
  const userHome = options.userHome ?? os.homedir();
  const cliPaths = bundledCliPaths(options.resourcesPath, platform);

  try {
    await access(cliPaths.launcher);
  } catch {
    return {
      ok: false,
      message: `未找到内置 CLI 启动脚本：${cliPaths.launcher}（开发模式请先构建 cli/）。`
    };
  }

  if (platform === 'win32') {
    const { binDir, usesStableShim } = resolveWindowsStableBinDir(options.execPath, options.resourcesPath);
    if (usesStableShim) {
      await mkdir(binDir, { recursive: true });
      writeFileSync(path.win32.join(binDir, 'mdd.cmd'), buildWindowsShimContent(cliPaths.launcher), 'utf8');
    }
    const ok = updateWindowsUserPathSync(binDir, 'add');
    if (!ok) {
      return {
        ok: false,
        message: `无法自动更新用户 PATH，请手动将以下目录加入环境变量 PATH：\n${binDir}`
      };
    }
    return {
      ok: true,
      installedPath: path.win32.join(binDir, 'mdd.cmd'),
      needsReopenTerminal: true,
      message: `已将 ${binDir} 加入当前用户 PATH。请重新打开终端后运行 mdd。`
    };
  }

  if (platform === 'darwin') {
    const systemTarget = '/usr/local/bin/mdd';
    const directOk = await tryReplaceSymlink(cliPaths.launcher, systemTarget);
    if (directOk) {
      return {
        ok: true,
        installedPath: systemTarget,
        message: `已安装命令行工具：${systemTarget}`
      };
    }
    // 请求系统管理员授权创建 /usr/local/bin/mdd
    const escapedSource = cliPaths.launcher.replace(/'/g, "'\\''");
    const script = `do shell script "mkdir -p /usr/local/bin && ln -sf '${escapedSource}' /usr/local/bin/mdd" with administrator privileges`;
    const elevated = spawnSync('osascript', ['-e', script], { encoding: 'utf8' });
    if (elevated.status === 0) {
      return {
        ok: true,
        installedPath: systemTarget,
        message: `已安装命令行工具：${systemTarget}`
      };
    }
    // 退回到 ~/.local/bin/mdd
    const fallbackTarget = path.posix.join(userHome, '.local', 'bin', 'mdd');
    await mkdir(path.posix.dirname(fallbackTarget), { recursive: true });
    await replaceSymlinkStrict(cliPaths.launcher, fallbackTarget);
    return {
      ok: true,
      installedPath: fallbackTarget,
      needsPathNote: true,
      message: `未获得 /usr/local/bin 授权，已安装到 ${fallbackTarget}。\n请确认 ~/.local/bin 已加入 PATH。`
    };
  }

  // Linux：优先安装到 ~/.local/bin/mdd（免 root）
  const linuxTarget = path.posix.join(userHome, '.local', 'bin', 'mdd');
  await mkdir(path.posix.dirname(linuxTarget), { recursive: true });
  await replaceSymlinkStrict(cliPaths.launcher, linuxTarget);
  return {
    ok: true,
    installedPath: linuxTarget,
    needsPathNote: true,
    message: `已安装命令行工具到 ${linuxTarget}。如终端找不到 mdd，请将 ~/.local/bin 加入 PATH。`
  };
}

async function tryReplaceSymlink(source: string, target: string): Promise<boolean> {
  try {
    await mkdir(path.dirname(target), { recursive: true });
    await replaceSymlinkStrict(source, target);
    return true;
  } catch {
    return false;
  }
}

async function replaceSymlinkStrict(source: string, target: string): Promise<void> {
  try {
    await lstat(target);
    await unlink(target);
  } catch (error) {
    if ((error as NodeJS.ErrnoException)?.code !== 'ENOENT') throw error;
  }
  await symlink(source, target);
}
