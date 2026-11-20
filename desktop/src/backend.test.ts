import { EventEmitter } from 'node:events';
import { createServer, type Server } from 'node:http';
import { describe, expect, it, vi } from 'vitest';
import {
  BackendManager,
  backendArguments,
  backendCryptoKeyInput,
  backendEnvironment,
  requestBackendShutdown,
  type BackendStartOptions
} from './backend.js';

function options(): BackendStartOptions {
  return {
    java: '/runtime/bin/java',
    jar: '/resources/backend.jar',
    port: 5173,
    desktopPaths: {
      home: '/data/MyDataDev',
      data: '/data/MyDataDev/data',
      backups: '/data/MyDataDev/backups',
      exports: '/data/MyDataDev/exports',
      sqlFiles: '/data/MyDataDev/sql-files',
      migrations: '/data/MyDataDev/migrations',
      logs: '/data/MyDataDev/logs',
      secretFile: '/data/MyDataDev/secret.json'
    },
    cryptoKey: 'secret-key',
    controlToken: 'control-token',
    parentPid: 123,
    platform: 'linux',
    environment: {
      PATH: '/usr/bin',
      db_admin_crypto_key: 'inherited-legacy-key',
      APP_CRYPTO_KEY: 'inherited-spring-key'
    }
  };
}

describe('desktop backend launch contract', () => {
  it('keeps secrets out of process arguments', () => {
    const args = backendArguments(options().jar);
    expect(args).toEqual(['-jar', '/resources/backend.jar', '--spring.profiles.active=desktop']);
    expect(args.join(' ')).not.toContain('secret-key');
    expect(args.join(' ')).not.toContain('control-token');
  });

  it('keeps the master key out of the child environment', () => {
    const environment = backendEnvironment(options());
    expect(environment.DB_ADMIN_CRYPTO_KEY).toBeUndefined();
    expect(environment.db_admin_crypto_key).toBeUndefined();
    expect(environment.APP_CRYPTO_KEY).toBeUndefined();
    expect(Object.values(environment)).not.toContain('secret-key');
    expect(environment.MYDATADEV_DESKTOP_HOME).toBe('/data/MyDataDev');
    expect(environment.MYDATADEV_DESKTOP_CONTROL_TOKEN).toBe('control-token');
    expect(environment.MYDATADEV_DESKTOP_PARENT_PID).toBe('123');
  });

  it('delivers exactly one key line over standard input', () => {
    expect(backendCryptoKeyInput('secret-key')).toBe('secret-key\n');
    expect(() => backendCryptoKeyInput('secret\nkey')).toThrow('包含换行符');
  });
});

async function listen(server: Server) {
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('missing server address');
  return address.port;
}

async function close(server: Server) {
  server.closeAllConnections();
  await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

describe('desktop backend shutdown', () => {
  it('sends the authenticated shutdown request', async () => {
    const requests: { method?: string; url?: string; token?: string | string[] }[] = [];
    const server = createServer((request, response) => {
      requests.push({ method: request.method, url: request.url, token: request.headers['x-mydatadev-desktop-token'] });
      response.writeHead(200).end();
    });
    const port = await listen(server);
    try {
      await requestBackendShutdown(port, 'control-token');
      expect(requests).toEqual([{ method: 'POST', url: '/internal/desktop/shutdown', token: 'control-token' }]);
    } finally {
      await close(server);
    }
  });

  it('times out when the backend accepts the connection but never responds', async () => {
    let received = false;
    const server = createServer(() => { received = true; });
    const port = await listen(server);
    try {
      await expect(requestBackendShutdown(port, 'control-token', 300)).rejects.toMatchObject({ name: 'TimeoutError' });
      expect(received).toBe(true);
    } finally {
      await close(server);
    }
  });

  it('lets the JVM finish graceful shutdown after a failed request', async () => {
    const child = Object.assign(new EventEmitter(), {
      pid: 12345,
      exitCode: null as number | null,
      signalCode: null as NodeJS.Signals | null,
      kill: vi.fn()
    });
    const manager = new BackendManager(options());
    Reflect.set(manager, 'child', child);
    const request = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('connection failed'));
    try {
      let finished = false;
      const stopped = manager.stop().then(() => { finished = true; });
      await vi.waitFor(() => expect(child.kill).toHaveBeenCalledWith('SIGTERM'));
      expect(finished).toBe(false);
      expect(child.kill).not.toHaveBeenCalledWith('SIGKILL');
      child.exitCode = 0;
      child.emit('exit', 0, null);
      await stopped;
      expect(finished).toBe(true);
      expect(child.kill).toHaveBeenCalledTimes(1);
    } finally {
      request.mockRestore();
    }
  });

  it('gives the backend time to clean up and bounds the wait if it remains stuck', async () => {
    vi.useFakeTimers();
    const child = Object.assign(new EventEmitter(), {
      pid: 12345,
      exitCode: null,
      signalCode: null,
      kill: vi.fn()
    });
    const manager = new BackendManager(options());
    Reflect.set(manager, 'child', child);
    const request = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 200 }));
    try {
      const stopped = manager.stop();
      await vi.advanceTimersByTimeAsync(59_999);
      expect(child.kill).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(1);
      expect(child.kill).toHaveBeenCalledWith('SIGTERM');
      await vi.advanceTimersByTimeAsync(5_000);
      await stopped;
      expect(child.kill).toHaveBeenCalledWith('SIGKILL');
      expect(child.listenerCount('exit')).toBe(0);
    } finally {
      request.mockRestore();
      vi.useRealTimers();
    }
  });

  it('recognizes a backend already terminated by a signal', async () => {
    const child = Object.assign(new EventEmitter(), {
      pid: 12345,
      exitCode: null,
      signalCode: 'SIGTERM'
    });
    const manager = new BackendManager(options());
    Reflect.set(manager, 'child', child);
    const request = vi.spyOn(globalThis, 'fetch');
    try {
      await manager.stop();
      expect(request).not.toHaveBeenCalled();
    } finally {
      request.mockRestore();
    }
  });
});
