import { chmod, cp, mkdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const desktopDirectory = path.resolve(directory, '..');
const projectDirectory = path.resolve(desktopDirectory, '..');
const frontendDirectory = path.join(projectDirectory, 'frontend');
const backendDirectory = path.join(projectDirectory, 'backend');
const cliDirectory = path.join(projectDirectory, 'cli');
const resourcesDirectory = path.join(desktopDirectory, 'resources');
const runtimeDirectory = path.join(resourcesDirectory, 'runtime');
const backendResourceDirectory = path.join(resourcesDirectory, 'backend');
const cliResourceDirectory = path.join(resourcesDirectory, 'cli');
const cliBinDirectory = path.join(cliResourceDirectory, 'bin');

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
run(command('npm'), ['run', 'build'], cliDirectory);
// The desktop profile copies fingerprinted Vite assets into target/classes.
// Clear the previous target first so obsolete hashes are not retained in the
// packaged JAR across successive local builds.
run(command('mvn'), ['-Pdesktop', '-DskipTests', 'clean', 'package'], backendDirectory);
run(process.execPath, [path.join(directory, 'generate-icons.mjs')], desktopDirectory);

await rm(resourcesDirectory, { recursive: true, force: true });
await mkdir(backendResourceDirectory, { recursive: true });
await mkdir(cliBinDirectory, { recursive: true });
await cp(path.join(backendDirectory, 'target', 'mydatadev-backend.jar'), path.join(backendResourceDirectory, 'mydatadev-backend.jar'));
await cp(path.join(cliDirectory, 'dist', 'mdd.js'), path.join(cliResourceDirectory, 'mdd.js'));

const posixLauncher = `#!/bin/sh
set -eu
SCRIPT="$0"
while [ -L "$SCRIPT" ]; do
  LINK=$(readlink "$SCRIPT")
  case "$LINK" in
    /*) SCRIPT="$LINK" ;;
    *) SCRIPT="$(dirname "$SCRIPT")/$LINK" ;;
  esac
done
BIN_DIR=$(CDPATH= cd -- "$(dirname -- "$SCRIPT")" && pwd)
CLI_ENTRY="$BIN_DIR/../mdd.js"
if [ "$(uname -s)" = "Darwin" ]; then
  APP_BIN="$BIN_DIR/../../../MacOS/MyDataDev"
else
  APP_BIN="$BIN_DIR/../../../MyDataDev"
fi
ELECTRON_RUN_AS_NODE=1 exec "$APP_BIN" "$CLI_ENTRY" "$@"
`;
const winLauncher = [
  '@echo off',
  'setlocal',
  'set "ELECTRON_RUN_AS_NODE=1"',
  '"%~dp0..\\..\\..\\MyDataDev.exe" "%~dp0..\\mdd.js" %*',
  'exit /b %ERRORLEVEL%',
  ''
].join('\r\n');

await writeFile(path.join(cliBinDirectory, 'mdd'), posixLauncher, { encoding: 'utf8', mode: 0o755 });
if (process.platform !== 'win32') {
  await chmod(path.join(cliBinDirectory, 'mdd'), 0o755);
}
await writeFile(path.join(cliBinDirectory, 'mdd.cmd'), winLauncher, 'utf8');

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
