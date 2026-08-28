import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api';
import {
  backgroundTaskPolling,
  backgroundTaskStreamUrl,
  nextStreamState,
  parseBackgroundTaskEvent,
  type BackgroundStreamState
} from '../backgroundTaskStream';
import {
  backgroundTaskCompletionMessage,
  EMPTY_BACKGROUND_TASK_SUMMARY,
  sameBackgroundTaskSummary,
  summarizeBackgroundTasks,
  type BackgroundTaskSummary
} from '../backgroundTasks';
import type { ActiveOperations } from '../types';
import { useStableEvent } from './useStableEvent';
import { useVisiblePolling } from './useVisiblePolling';

/**
 * 一条连接上的后台任务状态。
 *
 * 这块逻辑原本长在 App 里：一个 useVisiblePolling、两处 summary 状态和一个 ref。它和界面
 * 其余部分没有耦合 —— 输入是「看哪条连接」，输出是计数和一份快照 —— 拆出来之后，轮询更新
 * 不再触发整棵组件树重渲，推送与降级的取舍也有了独立的落脚点。
 *
 * 后台任务会活得比开启它的抽屉久（定时备份甚至没有抽屉），所以只要选中了连接就一直订阅，
 * 而不是只在面板可见时。标签页切到后台时 useVisiblePolling 会自己停下。
 */
export function useBackgroundTasks({ connectionId, watchingTasks, onOperations, onCompletion }: {
  connectionId?: number;
  /** 备份抽屉开着或已知有任务在跑：降级轮询时用更快的节奏。 */
  watchingTasks: boolean;
  /** 每次拿到快照都会回调，用于刷新抽屉里的实时行。 */
  onOperations: (operations: ActiveOperations) => void;
  /** 有任务结束时的提示文案。 */
  onCompletion: (message: string) => void;
}) {
  const [summary, setSummary] = useState<BackgroundTaskSummary>(EMPTY_BACKGROUND_TASK_SUMMARY);
  const [streamState, setStreamState] = useState<BackgroundStreamState>('connecting');
  const summaryRef = useRef<BackgroundTaskSummary>(EMPTY_BACKGROUND_TASK_SUMMARY);
  const streamStateRef = useRef<BackgroundStreamState>('connecting');
  const connectionIdRef = useRef<number | undefined>(connectionId);
  const operationsEvent = useStableEvent(onOperations);
  const completionEvent = useStableEvent(onCompletion);

  connectionIdRef.current = connectionId;

  const apply = useCallback((operations: ActiveOperations) => {
    operationsEvent(operations);
    const next = summarizeBackgroundTasks(operations);
    const previous = summaryRef.current;
    if (sameBackgroundTaskSummary(previous, next)) return;
    summaryRef.current = next;
    setSummary(next);
    const completion = backgroundTaskCompletionMessage(previous, next);
    if (completion) completionEvent(completion);
  }, [completionEvent, operationsEvent]);

  /**
   * 计数属于某一条连接，换连接时必须清零，否则第一份快照会把上一条连接的任务播报成「已结束」。
   */
  const reset = useCallback(() => {
    summaryRef.current = EMPTY_BACKGROUND_TASK_SUMMARY;
    setSummary(EMPTY_BACKGROUND_TASK_SUMMARY);
  }, []);

  const advance = useCallback((event: Parameters<typeof nextStreamState>[1]) => {
    const next = nextStreamState(streamStateRef.current, event);
    if (next === streamStateRef.current) return;
    streamStateRef.current = next;
    setStreamState(next);
  }, []);

  useEffect(() => {
    if (!connectionId) return;
    streamStateRef.current = 'connecting';
    setStreamState('connecting');
    if (typeof EventSource === 'undefined') {
      advance('unsupported');
      return;
    }
    const source = new EventSource(backgroundTaskStreamUrl(connectionId));
    source.addEventListener('operations', (event) => {
      const operations = parseBackgroundTaskEvent((event as MessageEvent).data);
      // 快照到得晚、连接已经切走时直接丢掉，否则会把别人的任务算到当前连接头上。
      if (!operations || connectionIdRef.current !== connectionId) return;
      apply(operations);
    });
    source.onopen = () => advance('open');
    source.onerror = () => {
      // EventSource 自己会重连，只有它已经关闭了才算真的用不了。
      advance(source.readyState === EventSource.CLOSED ? 'closed' : 'retrying');
    };
    return () => source.close();
  }, [advance, apply, connectionId]);

  useVisiblePolling({
    enabled: Boolean(connectionId),
    intervalMs: backgroundTaskPolling(streamState, { watchingTasks }).intervalMs,
    resetKey: connectionId,
    immediate: true,
    task: async () => {
      const id = connectionId;
      if (!id) return;
      try {
        const active = await api<ActiveOperations>(`/restores/operations/active?connectionId=${id}`);
        if (connectionIdRef.current !== id) return;
        apply(active);
      } catch {
        // 轮询是尽力而为：保留上一次已知状态，用户还可以手动刷新。
      }
    }
  });

  return { summary, streamState, reset };
}
