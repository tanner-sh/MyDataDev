import { describe, expect, it } from 'vitest';
import { backendArguments, backendEnvironment, type BackendStartOptions } from './backend.js';

function options(): BackendStartOptions {
  return {
    java: '/runtime/bin/java',
    jar: '/resources/backend.jar',
    port: 5173,
    desktopPaths: {
      home: '/data/MyDataDev',
      data: '/data/MyDataDev/data',
      backups: '/data/MyDataDev/backups',
      sqlFiles: '/data/MyDataDev/sql-files',
      logs: '/data/MyDataDev/logs',
      secretFile: '/data/MyDataDev/secret.json'
    },
    cryptoKey: 'secret-key',
    controlToken: 'control-token',
    parentPid: 123,
    platform: 'linux',
    environment: { PATH: '/usr/bin' }
  };
}

describe('desktop backend launch contract', () => {
  it('keeps secrets out of process arguments', () => {
    const args = backendArguments(options().jar);
    expect(args).toEqual(['-jar', '/resources/backend.jar', '--spring.profiles.active=desktop']);
    expect(args.join(' ')).not.toContain('secret-key');
    expect(args.join(' ')).not.toContain('control-token');
  });

  it('passes isolated paths and secrets only through the child environment', () => {
    const environment = backendEnvironment(options());
    expect(environment.DB_ADMIN_CRYPTO_KEY).toBe('secret-key');
    expect(environment.MYDATADEV_DESKTOP_HOME).toBe('/data/MyDataDev');
    expect(environment.MYDATADEV_DESKTOP_CONTROL_TOKEN).toBe('control-token');
    expect(environment.MYDATADEV_DESKTOP_PARENT_PID).toBe('123');
  });
});
