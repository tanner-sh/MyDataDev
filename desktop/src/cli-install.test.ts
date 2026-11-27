import { describe, expect, it } from 'vitest';
import {
  buildWindowsShimContent,
  mergeWindowsPath,
  removeWindowsPath,
  resolveWindowsStableBinDir
} from './cli-install.js';

describe('desktop CLI Windows PATH and shim helpers', () => {
  it('places Squirrel shim in stable parent bin directory unaffected by app-x.y.z upgrades', () => {
    const squirrel = resolveWindowsStableBinDir(
      'C:\\Users\\tanner\\AppData\\Local\\MyDataDev\\app-0.10.2\\MyDataDev.exe',
      'C:\\Users\\tanner\\AppData\\Local\\MyDataDev\\app-0.10.2\\resources'
    );
    expect(squirrel.usesStableShim).toBe(true);
    expect(squirrel.binDir).toBe('C:\\Users\\tanner\\AppData\\Local\\MyDataDev\\bin');

    const portable = resolveWindowsStableBinDir(
      'D:\\Tools\\MyDataDev\\MyDataDev.exe',
      'D:\\Tools\\MyDataDev\\resources'
    );
    expect(portable.usesStableShim).toBe(false);
    expect(portable.binDir).toBe('D:\\Tools\\MyDataDev\\resources\\cli\\bin');
  });

  it('generates CRLF shim forwarding all args and exit code', () => {
    const content = buildWindowsShimContent('C:\\App\\app-0.10.2\\resources\\cli\\bin\\mdd.cmd');
    expect(content).toContain('call "C:\\App\\app-0.10.2\\resources\\cli\\bin\\mdd.cmd" %*');
    expect(content).toContain('exit /b %ERRORLEVEL%');
    expect(content).toContain('\r\n');
  });

  it('merges and removes Windows PATH entries case-insensitively without duplicates', () => {
    const added = mergeWindowsPath('C:\\Windows\\System32;C:\\Tools\\', 'c:\\users\\tanner\\AppData\\Local\\MyDataDev\\bin');
    expect(added.changed).toBe(true);
    expect(added.next).toContain('c:\\users\\tanner\\AppData\\Local\\MyDataDev\\bin');

    const deduped = mergeWindowsPath(added.next, 'C:\\Users\\tanner\\AppData\\Local\\MyDataDev\\bin\\');
    expect(deduped.changed).toBe(false);

    const removed = removeWindowsPath(added.next, 'C:\\Users\\tanner\\AppData\\Local\\MyDataDev\\bin');
    expect(removed.changed).toBe(true);
    expect(removed.next).toBe('C:\\Windows\\System32;C:\\Tools\\');
  });
});
