/**
 * 浏览器空闲时预取懒加载块。
 *
 * SQL 编辑器（Monaco）是全站最大的一个块，gzip 后 600+ KiB，而它要等用户第一次点进
 * 编辑器才开始下载 —— 也就是把整段下载时间摊在了用户的第一次交互上。首屏渲染完成后
 * 到用户选连接、点编辑器之间通常有好几秒空闲，足够悄悄下完。
 *
 * 用 requestIdleCallback 而不是直接 import()，是为了不和首屏渲染抢带宽与主线程；
 * 不支持的浏览器退回到一个固定延时。
 */

export const PREFETCH_IDLE_TIMEOUT_MS = 3_000;
export const PREFETCH_FALLBACK_DELAY_MS = 1_500;

export type IdlePrefetchHost = {
  requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number;
  cancelIdleCallback?: (handle: number) => void;
  setTimeout: (callback: () => void, delayMs: number) => number;
  clearTimeout: (handle: number) => void;
  navigator?: object;
};

/**
 * 安排一次空闲预取，返回取消函数（可直接用作 useEffect 的清理函数）。
 *
 * 预取失败会被吞掉：真正需要这个模块时还会再 import 一次，届时的失败由调用方处理。
 */
export function prefetchWhenIdle(load: () => Promise<unknown>, host: IdlePrefetchHost): () => void {
  let cancelled = false;
  const run = () => {
    if (cancelled || shouldAvoidPrefetch(host)) return;
    void load().catch(() => undefined);
  };

  if (typeof host.requestIdleCallback === 'function') {
    const handle = host.requestIdleCallback(run, { timeout: PREFETCH_IDLE_TIMEOUT_MS });
    return () => {
      cancelled = true;
      host.cancelIdleCallback?.(handle);
    };
  }

  const handle = host.setTimeout(run, PREFETCH_FALLBACK_DELAY_MS);
  return () => {
    cancelled = true;
    host.clearTimeout(handle);
  };
}

function shouldAvoidPrefetch(host: IdlePrefetchHost): boolean {
  const connection = (host.navigator as { connection?: { saveData?: boolean; effectiveType?: string } } | undefined)?.connection;
  return Boolean(connection?.saveData || ['slow-2g', '2g'].includes(connection?.effectiveType || ''));
}

/** 逐个安排多个预取，避免大型懒加载块同时争抢带宽和解压主线程。 */
export function prefetchAllWhenIdle(loaders: Array<() => Promise<unknown>>, host: IdlePrefetchHost): () => void {
  let cancelled = false;
  let cancelCurrent = () => { };
  const schedule = (index: number) => {
    if (cancelled || index >= loaders.length) return;
    cancelCurrent = prefetchWhenIdle(async () => {
      try {
        await loaders[index]();
      } finally {
        schedule(index + 1);
      }
    }, host);
  };
  schedule(0);
  return () => {
    cancelled = true;
    cancelCurrent();
  };
}
