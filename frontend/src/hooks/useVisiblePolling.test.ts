import { describe, expect, it, vi } from 'vitest';
import { createVisiblePoller, type VisiblePollEnvironment } from './useVisiblePolling';

function testEnvironment() {
  let hidden = false;
  let nextTimerId = 0;
  const timers = new Map<number, ReturnType<typeof setTimeout>>();
  const listeners = new Set<() => void>();
  const environment: VisiblePollEnvironment = {
    setTimer: (callback, delayMs) => {
      const timerId = ++nextTimerId;
      timers.set(timerId, setTimeout(() => {
        timers.delete(timerId);
        callback();
      }, delayMs));
      return timerId;
    },
    clearTimer: (timerId) => {
      const timer = timers.get(timerId);
      if (timer) clearTimeout(timer);
      timers.delete(timerId);
    },
    isHidden: () => hidden,
    subscribeVisibility: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    }
  };
  return {
    environment,
    setHidden(value: boolean) {
      hidden = value;
      listeners.forEach((listener) => listener());
    },
    listenerCount: () => listeners.size
  };
}

describe('createVisiblePoller', () => {
  it('waits for the current request before scheduling the next poll', async () => {
    vi.useFakeTimers();
    let release: (() => void) | undefined;
    const task = vi.fn(() => new Promise<void>((resolve) => { release = resolve; }));
    const test = testEnvironment();
    const poller = createVisiblePoller(task, 1000, test.environment);
    poller.start(true);

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(5000);
    expect(task).toHaveBeenCalledTimes(1);

    release?.();
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(999);
    expect(task).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(task).toHaveBeenCalledTimes(2);
    poller.stop();
    vi.useRealTimers();
  });

  it('pauses while hidden, resumes immediately, and cleans up', async () => {
    vi.useFakeTimers();
    const task = vi.fn(async () => undefined);
    const test = testEnvironment();
    const poller = createVisiblePoller(task, 1000, test.environment);
    poller.start();
    expect(test.listenerCount()).toBe(1);

    test.setHidden(true);
    await vi.advanceTimersByTimeAsync(5000);
    expect(task).not.toHaveBeenCalled();

    test.setHidden(false);
    await vi.advanceTimersByTimeAsync(0);
    expect(task).toHaveBeenCalledTimes(1);

    poller.stop();
    expect(test.listenerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(5000);
    expect(task).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });
});
