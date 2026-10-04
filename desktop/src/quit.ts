export interface QuitWindow {
  onClosed(listener: () => void): () => void;
  onPreventUnload(listener: (event: { preventDefault(): void }) => void): () => void;
  close(): void;
}

interface UnloadEvent { preventDefault(): void; }
interface QuitWindowSource {
  once(event: 'closed', listener: () => void): unknown;
  removeListener(event: 'closed', listener: () => void): unknown;
  webContents: {
    on(event: 'will-prevent-unload', listener: (event: UnloadEvent) => void): unknown;
    removeListener(event: 'will-prevent-unload', listener: (event: UnloadEvent) => void): unknown;
  };
  close(): void;
}

export function quitWindowGate(window: QuitWindowSource): QuitWindow {
  // BrowserWindow 销毁后读取 webContents getter 会抛错；清理必须使用关闭前保存的引用。
  const contents = window.webContents;
  return {
    onClosed(listener) {
      window.once('closed', listener);
      return () => { window.removeListener('closed', listener); };
    },
    onPreventUnload(listener) {
      contents.on('will-prevent-unload', listener);
      return () => { contents.removeListener('will-prevent-unload', listener); };
    },
    close: () => window.close()
  };
}

/** 后端只能在页面接受退出且窗口实际关闭之后停止。 */
export async function quitAfterWindowConsent(
  window: QuitWindow | undefined,
  confirmUnsaved: () => boolean,
  stopBackend: () => Promise<void>,
  quit: () => void
): Promise<boolean> {
  if (window) {
    const accepted = await new Promise<boolean>((resolve, reject) => {
      let removeClosed = () => {};
      let removePreventUnload = () => {};
      const cleanup = () => { removeClosed(); removePreventUnload(); };
      removeClosed = window.onClosed(() => { cleanup(); resolve(true); });
      removePreventUnload = window.onPreventUnload((event) => {
        if (confirmUnsaved()) {
          // Electron 的 preventDefault 在此事件中表示忽略页面的 beforeunload 拦截。
          event.preventDefault();
        } else {
          cleanup();
          resolve(false);
        }
      });
      try { window.close(); } catch (error) { cleanup(); reject(error); }
    });
    if (!accepted) return false;
  }
  await stopBackend();
  quit();
  return true;
}
