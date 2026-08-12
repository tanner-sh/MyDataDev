import { cp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const desktopDirectory = path.resolve(directory, '..');
const projectDirectory = path.resolve(desktopDirectory, '..');
const frontendDirectory = path.join(projectDirectory, 'frontend');
const backendDirectory = path.join(projectDirectory, 'backend');
const resourcesDirectory = path.join(desktopDirectory, 'resources');
const runtimeDirectory = path.join(resourcesDirectory, 'runtime');
const backendResourceDirectory = path.join(resourcesDirectory, 'backend');

function command(name) {
  return process.platform === 'win32' ? `${name}.cmd` : name;
}

function run(executable, args, cwd) {
  const shell = process.platform === 'win32' && executable.toLowerCase().endsWith('.cmd');
  const result = spawnSync(executable, args, { cwd, stdio: 'inherit', env: process.env, shell });
  if (result.status !== 0) process.exit(result.status || 1);
}

run(process.execPath, [path.join(directory, 'check-versions.mjs')], desktopDirectory);
run(command('npm'), ['run', 'build'], frontendDirectory);
// The desktop profile copies fingerprinted Vite assets into target/classes.
// Clear the previous target first so obsolete hashes are not retained in the
// packaged JAR across successive local builds.
run(command('mvn'), ['-Pdesktop', '-DskipTests', 'clean', 'package'], backendDirectory);
run(process.execPath, [path.join(directory, 'generate-icons.mjs')], desktopDirectory);

await rm(resourcesDirectory, { recursive: true, force: true });
await mkdir(backendResourceDirectory, { recursive: true });
await cp(path.join(backendDirectory, 'target', 'mydatadev-backend.jar'), path.join(backendResourceDirectory, 'mydatadev-backend.jar'));

const jlink = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink')
  : command('jlink');
run(jlink, [
  '--add-modules', 'ALL-MODULE-PATH',
  '--strip-debug',
  '--no-header-files',
  '--no-man-pages',
  '--compress=2',
  '--output', runtimeDirectory
], desktopDirectory);
