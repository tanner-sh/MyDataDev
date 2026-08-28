import { randomBytes } from 'node:crypto';
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
import { bundledBackendPaths, resolveDesktopPaths, type DesktopPaths } from './paths.js';
import { loadOrCreateDesktopSecret } from './secret-store.js';

const PRODUCT_NAME = 'MyDataDev';
const UI_PORT = 5173;
const isDevelopment = Boolean(process.env.MYDATADEV_DESKTOP_DEV_SERVER_URL);
const backendPort = Number(process.env.MYDATADEV_DESKTOP_BACKEND_PORT || (isDevelopment ? 8080 : UI_PORT));
const uiUrl = process.env.MYDATADEV_DESKTOP_DEV_SERVER_URL || `http://127.0.0.1:${UI_PORT}`;
const smokeTest = process.argv.includes('--smoke-test');

let mainWindow: BrowserWindow | undefined;
let tray: Tray | undefined;
let trayAvailable = false;
let backend: BackendManager | undefined;
let desktopPaths: DesktopPaths;
let controlToken = process.env.MYDATADEV_DESKTOP_CONTROL_TOKEN || '';
let quitting = false;
let quitStarted = false;

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
    if (quitting) return;
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
  try {
    const response = await fetch(`http://127.0.0.1:${backendPort}/api/restores/operations/active`, {
      headers: { 'X-User': 'desktop' }
    });
    if (!response.ok) return false;
    const payload = await response.json() as { backups?: unknown[]; restores?: unknown[]; sqlFiles?: unknown[] };
    return Boolean(payload.backups?.length || payload.restores?.length || payload.sqlFiles?.length);
  } catch {
    return false;
  }
}

async function stopBackend() {
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
      message: '备份、恢复或 SQL 文件/CSV 导入任务仍在运行，退出可能中断任务。',
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
  quitting = true;
  await stopBackend();
  app.quit();
}

async function startBackend() {
  await mkdir(desktopPaths.home, { recursive: true });
  if (isDevelopment) {
    if (!controlToken) throw new Error('桌面开发模式缺少控制 Token。');
    await waitForHealthyBackend(backendPort);
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
  return secret.insecureLinuxFallback;
}

async function runSmokeTest() {
  const home = await fetch(uiUrl);
  if (!home.ok || !(await home.text()).includes('MyDataDev')) throw new Error('桌面首页烟测失败。');
  const mcp = await fetch(`http://127.0.0.1:${UI_PORT}/mcp`);
  if (mcp.status !== 401) throw new Error(`MCP 未认证烟测失败，实际状态 ${mcp.status}。`);
  await requestQuit(false);
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
