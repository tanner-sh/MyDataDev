import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { bundledBackendPaths, resolveDesktopPaths } from './paths.js';

describe('desktop paths', () => {
  it('uses platform-specific application data roots', () => {
    expect(resolveDesktopPaths('darwin', {}, '/Users/test').home)
      .toBe(path.resolve('/Users/test/Library/Application Support/MyDataDev'));
    expect(resolveDesktopPaths('win32', { LOCALAPPDATA: 'C:\\Users\\test\\AppData\\Local' }, 'C:\\Users\\test').home)
      .toBe('C:\\Users\\test\\AppData\\Local\\MyDataDev');
    expect(resolveDesktopPaths('linux', { XDG_DATA_HOME: '/data/test' }, '/home/test').home)
      .toBe('/data/test/MyDataDev');
  });

  it('honors an explicit isolated desktop home', () => {
    expect(resolveDesktopPaths('linux', { MYDATADEV_DESKTOP_HOME: '/tmp/mydatadev' }, '/home/test').home)
      .toBe('/tmp/mydatadev');
  });

  it('resolves the bundled runtime executable for each platform', () => {
    expect(bundledBackendPaths('C:\\resources', 'win32').java).toBe('C:\\resources\\runtime\\bin\\java.exe');
    expect(bundledBackendPaths('/resources', 'darwin').java).toContain(path.join('runtime', 'bin', 'java'));
  });
});
