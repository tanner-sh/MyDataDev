import { useEffect, useLayoutEffect, useRef } from 'react';

export type PollTask = () => void | Promise<void>;

export interface VisiblePollEnvironment {
  setTimer: (callback: () => void, delayMs: number) => number;
  clearTimer: (timerId: number) => void;
  isHidden: () => boolean;
  subscribeVisibility: (listener: () => void) => () => void;
}

export interface VisiblePoller {
  start: (immediate?: boolean) => void;
  stop: () => void;
}

export function createVisiblePoller(task: PollTask, intervalMs: number, environment: VisiblePollEnvironment): VisiblePoller {
  let timerId: number | null = null;
  let running = false;
  let stopped = true;
  let unsubscribe: () => void = () => undefined;

  const clearTimer = () => {
    if (timerId == null) return;
    environment.clearTimer(timerId);
    timerId = null;
  };
  const schedule = (delayMs: number) => {
    if (stopped || running || timerId != null || environment.isHidden()) return;
    timerId = environment.setTimer(() => {
      timerId = null;
      void run();
    }, delayMs);
  };
  const run = async () => {
    if (stopped || running || environment.isHidden()) return;
    running = true;
    try {
      await task();
    } catch {
      // Polling is best-effort; consumers keep their last known state and can
      // expose a manual refresh for errors that need user attention.
    } finally {
      running = false;
      schedule(intervalMs);
    }
  };
  const handleVisibility = () => {
    if (environment.isHidden()) {
      clearTimer();
    } else {
      schedule(0);
    }
  };

  return {
    start(immediate = false) {
      if (!stopped) return;
      stopped = false;
      unsubscribe = environment.subscribeVisibility(handleVisibility);
      schedule(immediate ? 0 : intervalMs);
    },
    stop() {
      if (stopped) return;
      stopped = true;
      clearTimer();
      unsubscribe();
      unsubscribe = () => undefined;
    }
  };
}

export function useVisiblePolling({
  enabled,
  intervalMs,
  task,
  resetKey,
  immediate = false
}: {
  enabled: boolean;
  intervalMs: number;
  task: PollTask;
  resetKey?: unknown;
  immediate?: boolean;
}) {
  const taskRef = useRef(task);

  useLayoutEffect(() => {
    taskRef.current = task;
  });

  useEffect(() => {
    if (!enabled) return;
    const poller = createVisiblePoller(
      () => taskRef.current(),
      intervalMs,
      {
        setTimer: (callback, delayMs) => window.setTimeout(callback, delayMs),
        clearTimer: (timerId) => window.clearTimeout(timerId),
        isHidden: () => document.hidden,
        subscribeVisibility: (listener) => {
          document.addEventListener('visibilitychange', listener);
          return () => document.removeEventListener('visibilitychange', listener);
        }
      }
    );
    poller.start(immediate);
    return poller.stop;
  }, [enabled, immediate, intervalMs, resetKey]);
}
