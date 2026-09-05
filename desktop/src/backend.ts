import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { createWriteStream, type WriteStream } from 'node:fs';
import { access, mkdir } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import type { DesktopPaths } from './paths.js';

const HEALTH_TIMEOUT_MS = 60_000;
const SHUTDOWN_TIMEOUT_MS = 30_000;

export type BackendStartOptions = {
  java: string;
  jar: string;
  port: number;
  desktopPaths: DesktopPaths;
  cryptoKey: string;
  controlToken: string;
  parentPid: number;
  platform: NodeJS.Platform;
  environment: NodeJS.ProcessEnv;
};

export function backendArguments(jar: string) {
  return ['-jar', jar, '--spring.profiles.active=desktop'];
}

export function backendEnvironment(options: BackendStartOptions): NodeJS.ProcessEnv {
  const inheritedEnvironment = Object.fromEntries(
    Object.entries(options.environment).filter(([name]) => {
      const normalized = name.toUpperCase();
      return normalized !== 'DB_ADMIN_CRYPTO_KEY' && normalized !== 'APP_CRYPTO_KEY';
    })
  );
  return {
    ...inheritedEnvironment,
    MYDATADEV_DESKTOP_HOME: options.desktopPaths.home,
    MYDATADEV_DESKTOP_CONTROL_TOKEN: options.controlToken,
    MYDATADEV_DESKTOP_PARENT_PID: String(options.parentPid)
  };
}

export function backendCryptoKeyInput(cryptoKey: string) {
  if (!cryptoKey || /[\r\n]/.test(cryptoKey)) {
    throw new Error('桌面主密钥为空或包含换行符。');
  }
  return `${cryptoKey}\n`;
}

export async function waitForHealthyBackend(port: number, timeoutMs = HEALTH_TIMEOUT_MS) {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/actuator/health`);
      if (response.ok && (await response.json() as { status?: string }).status === 'UP') return;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`后端在 ${Math.round(timeoutMs / 1000)} 秒内未就绪。`, { cause: lastError });
}

export async function portAvailable(port: number) {
  return await new Promise<boolean>((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.once('listening', () => server.close(() => resolve(true)));
    server.listen(port, '127.0.0.1');
  });
}

export async function requestBackendShutdown(port: number, controlToken: string) {
  const response = await fetch(`http://127.0.0.1:${port}/internal/desktop/shutdown`, {
    method: 'POST',
    headers: { 'X-MyDataDev-Desktop-Token': controlToken }
  });
  if (!response.ok) throw new Error(`后端拒绝关闭请求（HTTP ${response.status}）。`);
}

export class BackendManager {
  private child?: ChildProcess;
  private output?: WriteStream;

  constructor(private readonly options: BackendStartOptions) {
  }

  async start() {
    if (!await portAvailable(this.options.port)) {
      throw new Error(`本机端口 ${this.options.port} 已被占用。请关闭正在运行的 Web 开发服务或其他程序后重试。`);
    }
    await Promise.all([
      mkdir(this.options.desktopPaths.data, { recursive: true }),
      mkdir(this.options.desktopPaths.backups, { recursive: true }),
      mkdir(this.options.desktopPaths.exports, { recursive: true }),
      mkdir(this.options.desktopPaths.sqlFiles, { recursive: true }),
      mkdir(this.options.desktopPaths.logs, { recursive: true }),
      access(this.options.java),
      access(this.options.jar)
    ]);
    this.output = createWriteStream(path.join(this.options.desktopPaths.logs, 'desktop-launcher.log'), { flags: 'a' });
    this.child = spawn(this.options.java, backendArguments(this.options.jar), {
      env: backendEnvironment(this.options),
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true
    });
    this.child.stdout?.pipe(this.output, { end: false });
    this.child.stderr?.pipe(this.output, { end: false });
    this.child.once('exit', (code, signal) => {
      this.output?.write(`\n[desktop] backend exited code=${code ?? 'null'} signal=${signal ?? 'null'}\n`);
    });
    try {
      await this.sendCryptoKey();
      await waitForHealthyBackend(this.options.port);
    } catch (error) {
      await this.forceStop();
      throw error;
    }
  }

  private async sendCryptoKey() {
    const input = this.child?.stdin;
    if (!input) throw new Error('无法向后端安全交付主密钥。');
    const payload = backendCryptoKeyInput(this.options.cryptoKey);
    await new Promise<void>((resolve, reject) => {
      const failed = (error: Error) => reject(new Error('向后端交付主密钥失败。', { cause: error }));
      input.once('error', failed);
      input.end(payload, () => {
        input.off('error', failed);
        resolve();
      });
    });
  }

  async stop() {
    if (!this.child || this.child.exitCode !== null) return;
    try {
      await requestBackendShutdown(this.options.port, this.options.controlToken);
      await this.waitForExit(SHUTDOWN_TIMEOUT_MS);
    } catch {
      await this.forceStop();
    } finally {
      this.output?.end();
    }
  }

  private async waitForExit(timeoutMs: number) {
    const child = this.child;
    if (!child || child.exitCode !== null) return;
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('backend shutdown timeout')), timeoutMs);
      child.once('exit', () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }

  private async forceStop() {
    const child = this.child;
    if (!child || child.exitCode !== null || !child.pid) return;
    if (this.options.platform === 'win32') {
      spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], { windowsHide: true });
    } else {
      child.kill('SIGTERM');
      await this.waitForExit(5_000).catch(() => child.kill('SIGKILL'));
    }
  }
}
