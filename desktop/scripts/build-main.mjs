import { rm } from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const desktopDirectory = path.resolve(directory, '..');
const tsc = process.platform === 'win32' ? 'tsc.cmd' : 'tsc';

await rm(path.join(desktopDirectory, 'dist'), { recursive: true, force: true });
const result = spawnSync(tsc, ['-p', 'tsconfig.json'], {
  cwd: desktopDirectory,
  env: process.env,
  shell: process.platform === 'win32',
  stdio: 'inherit'
});
if (result.status !== 0) process.exit(result.status || 1);
