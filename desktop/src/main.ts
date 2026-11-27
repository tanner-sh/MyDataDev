import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import started from 'electron-squirrel-startup';
import {
  app,
  BrowserWindow,
  dialog,
  Menu,
  nativeImage,
  safeStorage,
  shell,
  Tray
} from 'electron';
import { BackendManager, requestBackendShutdown, waitForHealthyBackend } from './backend.js';
import { handleSquirrelCliEvent, installCliCommand } from './cli-install.js';
import { removeCliRuntimeEndpoint, writeCliRuntimeEndpoint } from './cli-runtime.js';
import { bundledBackendPaths, bundledCliPaths, resolveDesktopPaths, type DesktopPaths } from './paths.js';
import { loadOrCreateDesktopSecret } from './secret-store.js';
import { quitAfterWindowConsent, quitWindowGate } from './quit.js';
import {
  parseLatestRelease,
  RELEASES_URL,
  shouldNotify,
  UPDATE_CHECK_TIMEOUT_MS
} from './update-check.js';

const PRODUCT_NAME = 'MyDataDev';
const UI_PORT = 5173;
const isDevelopment = Boolean(process.env.MYDATADEV_DESKTOP_DEV_SERVER_URL);
const backendPort = Number(process.env.MYDATADEV_DESKTOP_BACKEND_PORT || (isDevelopment ? 8080 : UI_PORT));
const uiUrl = process.env.MYDATADEV_DESKTOP_DEV_SERVER_URL || `http://127.0.0.1:${UI_PORT}`;
const smokeTest = process.argv.includes('--smoke-test');
const smokeTimeoutOverride = Number(process.env.MYDATADEV_SMOKE_TIMEOUT_MS);
// 写坏了就当没写：NaN 传进 setTimeout 会被当成 0，看门狗立刻开火，比没有它更糟。
const smokeTimeoutMs = Number.isFinite(smokeTimeoutOverride) && smokeTimeoutOverride > 0
  ? smokeTimeoutOverride
  : 75_000;
let smokeTimeout: NodeJS.Timeout | undefined;

// CI 的桌面冒烟不能在 Electron 初始化、系统安全存储或内置后端异常时无限挂起。
if (smokeTest) {
  smokeTimeout = setTimeout(() => {
    console.error(`桌面冒烟在 ${Math.round(smokeTimeoutMs / 1000)} 秒内未完成。`);
    app.exit(1);
  }, smokeTimeoutMs);
}

let mainWindow: BrowserWindow | undefined;
let tray: Tray | undefined;
let trayAvailable = false;
let backend: BackendManager | undefined;
let desktopPaths: DesktopPaths;
let controlToken = process.env.MYDATADEV_DESKTOP_CONTROL_TOKEN || '';
let quitting = false;
let closingForQuit = false;
let quitStarted = false;

if (started && process.platform === 'win32') {
  handleSquirrelCliEvent(process.argv, process.execPath, process.resourcesPath);
}

const shouldStart = !started && app.requestSingleInstanceLock();
if (!shouldStart) app.quit();

function iconPath() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'icon.png')
    : path.join(app.getAppPath(), 'assets', 'icon.png');
}

function showWindow() {
  if (!mainWindow) return;
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
}

function createWindow() {
  mainWindow = new BrowserWindow({
    title: PRODUCT_NAME,
    width: 1440,
    height: 920,
    minWidth: 1040,
    minHeight: 700,
    show: false,
    backgroundColor: '#f4f7fb',
    icon: iconPath(),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  mainWindow.once('ready-to-show', () => mainWindow?.show());
  mainWindow.on('close', (event) => {
    if (quitting || closingForQuit) return;
    event.preventDefault();
    if (trayAvailable) mainWindow?.hide();
    else mainWindow?.minimize();
  });
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://') || url.startsWith('http://')) void shell.openExternal(url);
    return { action: 'deny' };
  });
  mainWindow.webContents.on('will-navigate', (event, target) => {
    if (new URL(target).origin !== new URL(uiUrl).origin) event.preventDefault();
  });
  void mainWindow.loadURL(uiUrl);
}

function createTray() {
  if (smokeTest) return;
  try {
    const image = nativeImage.createFromPath(iconPath()).resize({ width: 18, height: 18 });
    if (process.platform === 'darwin') image.setTemplateImage(true);
    tray = new Tray(image);
    tray.setToolTip(`${PRODUCT_NAME} · MCP http://localhost:${UI_PORT}/mcp`);
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: '显示 MyDataDev', click: showWindow },
      { label: `MCP 运行中 · localhost:${UI_PORT}`, enabled: false },
      { type: 'separator' },
      { label: '打开数据目录', click: () => void shell.openPath(desktopPaths.home) },
      { label: '打开日志目录', click: () => void shell.openPath(desktopPaths.logs) },
      { label: '安装命令行工具 mdd', click: () => void handleInstallCliCommand() },
      { type: 'separator' },
      { label: '退出 MyDataDev', click: () => void requestQuit(true) }
    ]));
    tray.on('click', showWindow);
    trayAvailable = true;
  } catch {
    trayAvailable = false;
  }
}

async function hasActiveOperations() {
  const results = await Promise.allSettled([
    fetch(`http://127.0.0.1:${backendPort}/api/restores/operations/active`, {
      headers: { 'X-User': 'desktop' }, signal: AbortSignal.timeout(5000)
    }).then(async response => {
      if (!response.ok) return false;
      const payload = await response.json() as { backups?: unknown[]; restores?: unknown[]; sqlFiles?: unknown[] };
      return Boolean(payload.backups?.length || payload.restores?.length || payload.sqlFiles?.length);
    }),
    fetch(`http://127.0.0.1:${backendPort}/api/scheduled-queries/active`, {
      headers: { 'X-User': 'desktop' }, signal: AbortSignal.timeout(5000)
    }).then(async response => response.ok && (await response.json() as unknown[]).length > 0)
  ]);
  return results.some(result => result.status === 'fulfilled' && result.value);
}

async function handleInstallCliCommand() {
  const result = await installCliCommand({
    execPath: process.execPath,
    resourcesPath: process.resourcesPath,
    userHome: app.getPath('home')
  });
  await dialog.showMessageBox({
    type: result.ok ? 'info' : 'warning',
    title: result.ok ? '命令行工具 mdd 已安装' : '安装命令行工具失败',
    message: result.message
  });
}

async function stopBackend() {
  if (desktopPaths) {
    await removeCliRuntimeEndpoint(desktopPaths);
  }
  if (backend) await backend.stop();
  else if (controlToken) await requestBackendShutdown(backendPort, controlToken).catch(() => undefined);
}

async function requestQuit(confirmActive: boolean) {
  if (quitStarted) return;
  quitStarted = true;
  if (confirmActive && await hasActiveOperations()) {
    const answer = await dialog.showMessageBox({
      type: 'warning',
      title: '仍有任务正在运行',
      message: '备份、恢复、定时导出或 SQL 文件/CSV 导入任务仍在运行，退出可能中断任务。',
      detail: '是否仍然退出 MyDataDev？',
      buttons: ['继续使用', '退出'],
      defaultId: 0,
      cancelId: 0
    });
    if (answer.response === 0) {
      quitStarted = false;
      return;
    }
  }
  const window = mainWindow && !mainWindow.isDestroyed() ? mainWindow : undefined;
  closingForQuit = true;
  try {
    await quitAfterWindowConsent(window && quitWindowGate(window), () => dialog.showMessageBoxSync(window!, {
      type: 'warning',
      title: '仍有未保存的工作',
      message: '页面中有未保存的修改或尚未结束的事务。',
      detail: '退出后未保存的修改会丢失，未提交的事务将回滚。是否仍然退出？',
      buttons: ['继续使用', '退出'],
      defaultId: 0,
      cancelId: 0
    }) === 1, async () => {
      quitting = true;
      await stopBackend();
    }, () => app.quit());
  } finally {
    closingForQuit = false;
    if (!quitting) quitStarted = false;
  }
}

async function startBackend() {
  await mkdir(desktopPaths.home, { recursive: true });
  if (isDevelopment) {
    if (!controlToken) throw new Error('桌面开发模式缺少控制 Token。');
    await waitForHealthyBackend(backendPort);
    await writeCliRuntimeEndpoint(desktopPaths, {
      pid: process.pid,
      port: backendPort,
      version: app.getVersion()
    });
    return false;
  }

  const secret = await loadOrCreateDesktopSecret(desktopPaths.secretFile, {
    available: () => safeStorage.isAsyncEncryptionAvailable(),
    encrypt: (value) => safeStorage.encryptStringAsync(value),
    decrypt: async (value) => (await safeStorage.decryptStringAsync(value)).result,
    backend: () => process.platform === 'linux' ? safeStorage.getSelectedStorageBackend() : process.platform
  });
  controlToken = randomBytes(32).toString('base64url');
  backend = new BackendManager({
    ...bundledBackendPaths(process.resourcesPath, process.platform),
    port: backendPort,
    desktopPaths,
    cryptoKey: secret.cryptoKey,
    controlToken,
    parentPid: process.pid,
    platform: process.platform,
    environment: process.env
  });
  await backend.start();
  await writeCliRuntimeEndpoint(desktopPaths, {
    pid: process.pid,
    port: backendPort,
    version: app.getVersion()
  });
  return secret.insecureLinuxFallback;
}

async function runSmokeTest() {
  console.log('桌面冒烟：验证首页、system/info、MCP 认证边界与内置 mdd CLI…');
  const home = await fetch(uiUrl);
  if (!home.ok || !(await home.text()).includes('MyDataDev')) throw new Error('桌面首页烟测失败。');
  const sysInfoRes = await fetch(`http://127.0.0.1:${backendPort}/api/system/info`);
  if (!sysInfoRes.ok) throw new Error(`/api/system/info 烟测失败，实际状态 ${sysInfoRes.status}。`);
  const sysInfo = await sysInfoRes.json() as { mode?: string; apiVersion?: number };
  if (sysInfo.mode !== 'desktop' || sysInfo.apiVersion !== 1) {
    throw new Error(`/api/system/info 返回异常：${JSON.stringify(sysInfo)}`);
  }
  const mcp = await fetch(`http://127.0.0.1:${UI_PORT}/mcp`);
  if (mcp.status !== 401) throw new Error(`MCP 未认证烟测失败，实际状态 ${mcp.status}。`);

  if (app.isPackaged) {
    const cliLauncher = bundledCliPaths(process.resourcesPath, process.platform).launcher;
    const cliEnv = { ...process.env, MYDATADEV_DESKTOP_HOME: desktopPaths.home };
    const statusRun = spawnSync(cliLauncher, ['status', '--format', 'json'], {
      env: cliEnv,
      encoding: 'utf8',
      shell: process.platform === 'win32',
      timeout: 20_000
    });
    if (statusRun.status !== 0 || !statusRun.stdout.includes('"mode": "desktop"')) {
      throw new Error(`内置 mdd status 烟测失败（exit=${statusRun.status}）：${statusRun.stderr || statusRun.stdout}`);
    }
    // 通过 API 建一条 H2 内存连接，再用内置 mdd REPL 选中该连接并连续执行两次只读查询
    const createdConn = await fetch(`http://127.0.0.1:${backendPort}/api/connections`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-User': 'admin' },
      body: JSON.stringify({
        name: 'desktop-smoke-h2',
        dbType: 'h2',
        environment: 'dev',
        jdbcUrl: 'jdbc:h2:mem:desktop_smoke_cli;DB_CLOSE_DELAY=-1',
        username: 'sa',
        password: '',
        readonly: false
      })
    });
    if (!createdConn.ok) throw new Error(`桌面冒烟创建 H2 连接失败：${createdConn.status}`);
    const replRun = spawnSync(cliLauncher, ['repl'], {
      env: cliEnv,
      input: 'conn ls\nconn use desktop-smoke-h2\nselect 1 as n;\nselect 2 as m;\nexit\n',
      encoding: 'utf8',
      shell: process.platform === 'win32',
      timeout: 25_000
    });
    if (replRun.status !== 0 || !replRun.stdout.includes('desktop-smoke-h2') || !replRun.stdout.includes('2')) {
      throw new Error(`内置 mdd repl 烟测失败（exit=${replRun.status}）：${replRun.stderr || replRun.stdout}`);
    }
  }

  quitting = true;
  // 关后端也在看门狗的覆盖范围内：卡在这一步同样是「冒烟永远不结束」，
  // 而且还会给 CI 机器留下一个没人回收的后端进程。所以先关干净，再撤看门狗。
  await stopBackend();
  clearTimeout(smokeTimeout);
  console.log('桌面冒烟通过。');
  app.exit(0);
}

/**
 * 启动后看一眼有没有新版本。
 *
 * <p>只查、只提示，不下载也不安装：自动更新要签名和更新服务器，而 macOS 目前是未签名发行，
 * 静默替换一个未签名的应用既做不到也不该做。</p>
 *
 * <p>整段吞掉异常，也不 await：查不到新版本是最正常不过的结果（离线、GitHub 不通、限流），
 * 为它弹一个错误框，或者让它拖慢启动，都是本末倒置。</p>
 */
async function checkForUpdates() {
  try {
    const response = await fetch(RELEASES_URL, {
      headers: { accept: 'application/vnd.github+json' },
      signal: AbortSignal.timeout(UPDATE_CHECK_TIMEOUT_MS)
    });
    if (!response.ok) return;
    const latest = parseLatestRelease(await response.json());
    if (!shouldNotify(app.getVersion(), latest, isDevelopment) || !latest) return;
    const answer = await dialog.showMessageBox({
      type: 'info',
      title: `${PRODUCT_NAME} 有新版本`,
      message: `发现新版本 ${latest.version}（当前 ${app.getVersion()}）。`,
      detail: '下载页面会在浏览器中打开，安装包需要手动下载安装。',
      buttons: ['稍后再说', '打开下载页'],
      defaultId: 1,
      cancelId: 0
    });
    if (answer.response === 1) await shell.openExternal(latest.url);
  } catch {
    // 查不到就算了：离线、限流、GitHub 不可达都属于正常情况，不值得打扰用户。
  }
}

async function bootstrap() {
  desktopPaths = resolveDesktopPaths(process.platform, process.env, app.getPath('home'));
  const insecureLinuxFallback = await startBackend();
  if (smokeTest) {
    await runSmokeTest();
    return;
  }
  createWindow();
  createTray();
  // 不 await：更新检查是旁路的，不该挡在窗口后面的任何一步前面。
  void checkForUpdates();
  if (insecureLinuxFallback) {
    await dialog.showMessageBox({
      type: 'warning',
      title: 'Linux 安全存储不可用',
      message: '当前桌面环境没有可用的 Secret Service。',
      detail: 'MyDataDev 已限制密钥文件权限，但建议启用 GNOME Keyring、KWallet 或兼容的 Secret Service。'
    });
  }
}

if (shouldStart) {
  app.on('second-instance', showWindow);
  app.on('activate', showWindow);
  app.on('window-all-closed', () => undefined);
  app.on('before-quit', (event) => {
    if (quitting) return;
    event.preventDefault();
    void requestQuit(true);
  });

  void app.whenReady().then(bootstrap).catch(async (error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    if (smokeTest) console.error(`桌面冒烟失败：${message}`);
    if (!smokeTest) {
      const response = await dialog.showMessageBox({
        type: 'error',
        title: 'MyDataDev 启动失败',
        message,
        detail: desktopPaths?.logs ? `日志目录：${desktopPaths.logs}` : undefined,
        buttons: desktopPaths?.logs ? ['退出', '打开日志目录'] : ['退出'],
        defaultId: 0
      });
      if (response.response === 1 && desktopPaths?.logs) await shell.openPath(desktopPaths.logs);
    }
    quitting = true;
    await stopBackend();
    app.exit(1);
  });
}
