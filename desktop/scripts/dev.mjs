import { randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const desktopDirectory = path.resolve(directory, '..');
const projectDirectory = path.resolve(desktopDirectory, '..');
const devHome = path.join(desktopDirectory, '.dev-data');
const keyFile = path.join(devHome, 'dev-crypto-key');
const children = new Set();

function command(name) {
  return process.platform === 'win32' ? `${name}.cmd` : name;
}

async function developmentKey() {
  await mkdir(devHome, { recursive: true });
  try {
    return (await readFile(keyFile, 'utf8')).trim();
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const key = randomBytes(32).toString('base64url');
    await writeFile(keyFile, `${key}\n`, { mode: 0o600 });
    return key;
  }
}

function start(executable, args, cwd, environment) {
  const child = spawn(executable, args, { cwd, env: environment, stdio: 'inherit', windowsHide: true });
  children.add(child);
  child.once('exit', () => children.delete(child));
  return child;
}

function stop(child) {
  if (!child.pid || child.exitCode !== null) return;
  if (process.platform === 'win32') spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], { windowsHide: true });
  else child.kill('SIGTERM');
}

async function wait(url, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // Process may still be starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`等待 ${url} 超时`);
}

async function portAvailable(port) {
  return await new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.once('listening', () => server.close(() => resolve(true)));
    server.listen(port, '127.0.0.1');
  });
}

for (const port of [8080, 5173]) {
  if (!await portAvailable(port)) throw new Error(`桌面开发模式需要的本机端口 ${port} 已被占用。`);
}

const environment = {
  ...process.env,
  DB_ADMIN_CRYPTO_KEY: await developmentKey(),
  MYDATADEV_DESKTOP_HOME: devHome,
  MYDATADEV_DESKTOP_CONTROL_TOKEN: randomBytes(32).toString('base64url'),
  MYDATADEV_DESKTOP_PARENT_PID: String(process.pid),
  MYDATADEV_DESKTOP_DEV_SERVER_URL: 'http://127.0.0.1:5173',
  MYDATADEV_DESKTOP_BACKEND_PORT: '8080'
};

const backend = start(command('mvn'), ['spring-boot:run', '-Dspring-boot.run.profiles=desktop,desktop-dev'], path.join(projectDirectory, 'backend'), environment);
await wait('http://127.0.0.1:8080/actuator/health');
const frontend = start(command('npm'), ['run', 'dev', '--', '--host', '127.0.0.1', '--strictPort'], path.join(projectDirectory, 'frontend'), environment);
await wait('http://127.0.0.1:5173');
const electron = start(command('npx'), ['electron-forge', 'start'], desktopDirectory, environment);

function cleanup() {
  for (const child of children) stop(child);
}

process.once('SIGINT', () => { cleanup(); process.exit(130); });
process.once('SIGTERM', () => { cleanup(); process.exit(143); });
electron.once('exit', (code) => {
  cleanup();
  process.exit(code || 0);
});

backend.once('exit', (code) => {
  if (electron.exitCode === null && code !== 0) {
    cleanup();
    process.exit(code || 1);
  }
});

frontend.once('exit', (code) => {
  if (electron.exitCode === null) {
    cleanup();
    process.exit(code || 1);
  }
});
