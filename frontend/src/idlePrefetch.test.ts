import { describe, expect, it, vi } from 'vitest';
import {
  PREFETCH_FALLBACK_DELAY_MS,
  PREFETCH_IDLE_TIMEOUT_MS,
  prefetchAllWhenIdle,
  prefetchWhenIdle,
  type IdlePrefetchHost
} from './idlePrefetch';

function idleHost() {
  const idleCallbacks: Array<() => void> = [];
  const cancelledIdle: number[] = [];
  const host: IdlePrefetchHost = {
    requestIdleCallback: (callback, options) => {
      expect(options?.timeout).toBe(PREFETCH_IDLE_TIMEOUT_MS);
      idleCallbacks.push(callback);
      return idleCallbacks.length;
    },
    cancelIdleCallback: (handle) => cancelledIdle.push(handle),
    setTimeout: () => {
      throw new Error('requestIdleCallback 可用时不应退回定时器');
    },
    clearTimeout: () => undefined
  };
  return { host, idleCallbacks, cancelledIdle, runIdle: () => idleCallbacks.forEach((callback) => callback()) };
}

function timerHost() {
  const timers: Array<{ callback: () => void; delayMs: number }> = [];
  const cleared: number[] = [];
  const host: IdlePrefetchHost = {
    setTimeout: (callback, delayMs) => {
      timers.push({ callback, delayMs });
      return timers.length;
    },
    clearTimeout: (handle) => cleared.push(handle)
  };
  return { host, timers, cleared, runTimers: () => timers.forEach((timer) => timer.callback()) };
}

describe('prefetchWhenIdle', () => {
  it('defers the import to an idle callback instead of running it immediately', () => {
    const load = vi.fn().mockResolvedValue(undefined);
    const { host, runIdle } = idleHost();

    prefetchWhenIdle(load, host);
    expect(load).not.toHaveBeenCalled();

    runIdle();
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('falls back to a timer when the browser has no idle callback', () => {
    const load = vi.fn().mockResolvedValue(undefined);
    const { host, timers, runTimers } = timerHost();

    prefetchWhenIdle(load, host);
    expect(timers).toHaveLength(1);
    expect(timers[0].delayMs).toBe(PREFETCH_FALLBACK_DELAY_MS);

    runTimers();
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('does not import after being cancelled, even if the callback still fires', () => {
    const load = vi.fn().mockResolvedValue(undefined);
    const { host, cancelledIdle, runIdle } = idleHost();

    prefetchWhenIdle(load, host)();
    expect(cancelledIdle).toEqual([1]);

    // 浏览器已经排入队列的回调仍可能执行，此时必须自己拦住。
    runIdle();
    expect(load).not.toHaveBeenCalled();
  });

  it('cancels the fallback timer too', () => {
    const load = vi.fn().mockResolvedValue(undefined);
    const { host, cleared, runTimers } = timerHost();

    prefetchWhenIdle(load, host)();

    expect(cleared).toEqual([1]);
    runTimers();
    expect(load).not.toHaveBeenCalled();
  });

  it('swallows a failed prefetch so it cannot surface as an unhandled rejection', async () => {
    const load = vi.fn().mockRejectedValue(new Error('offline'));
    const { host, runIdle } = idleHost();

    prefetchWhenIdle(load, host);
    runIdle();

    await expect(Promise.resolve()).resolves.toBeUndefined();
    expect(load).toHaveBeenCalledTimes(1);
  });
});

describe('prefetchAllWhenIdle', () => {
  it('schedules every loader and cancels them together', () => {
    const first = vi.fn().mockResolvedValue(undefined);
    const second = vi.fn().mockResolvedValue(undefined);
    const { host, cancelledIdle, runIdle } = idleHost();

    const cancel = prefetchAllWhenIdle([first, second], host);
    cancel();
    runIdle();

    expect(cancelledIdle).toEqual([1, 2]);
    expect(first).not.toHaveBeenCalled();
    expect(second).not.toHaveBeenCalled();
  });

  it('runs every loader when left to fire', () => {
    const first = vi.fn().mockResolvedValue(undefined);
    const second = vi.fn().mockResolvedValue(undefined);
    const { host, runIdle } = idleHost();

    prefetchAllWhenIdle([first, second], host);
    runIdle();

    expect(first).toHaveBeenCalledTimes(1);
    expect(second).toHaveBeenCalledTimes(1);
  });
});
