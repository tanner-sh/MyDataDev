import { useCallback, useRef, useState } from 'react';
import { api } from '../api';
import {
  INITIAL_SQL_HISTORY_QUERY,
  sqlHistoryRequestParams,
  type SqlHistoryQuery
} from '../sqlHistoryQuery';
import type { SqlHistory } from '../types';
import { useStableEvent } from './useStableEvent';

/**
 * SQL 历史抽屉的取数与状态。
 *
 * 从 App 里拆出来的一块：五个状态、一个请求序号和三个取数函数，除了「当前是哪条连接」之外
 * 不依赖其余任何界面状态。留在 App 里时，一次关键字输入的去抖刷新会让整棵组件树重渲。
 *
 * 抽屉懒加载，所以 `featureLoaded` 与 `open` 是两件事：前者决定组件要不要挂载，后者决定
 * 挂上之后是否可见 —— 关掉抽屉不该把已经下载好的代码卸掉。
 */
export function useSqlHistory({ connectionId, onWarning, onError }: {
  connectionId?: number;
  /** 静默刷新失败：SQL 本身已经执行完了，只是历史没跟上。 */
  onWarning: (message: string) => void;
  onError: (message: string) => void;
}) {
  const [rows, setRows] = useState<SqlHistory[]>([]);
  const [query, setQuery] = useState<SqlHistoryQuery>(INITIAL_SQL_HISTORY_QUERY);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [featureLoaded, setFeatureLoaded] = useState(false);
  const requestSeqRef = useRef(0);
  const inFlightRef = useRef(0);
  const queryRef = useRef<SqlHistoryQuery>(INITIAL_SQL_HISTORY_QUERY);
  const connectionIdRef = useRef<number | undefined>(connectionId);
  const warn = useStableEvent(onWarning);
  const fail = useStableEvent(onError);

  connectionIdRef.current = connectionId;
  queryRef.current = query;

  const refresh = useCallback(async (nextQuery: SqlHistoryQuery = queryRef.current) => {
    const id = connectionIdRef.current;
    if (!id) {
      setRows([]);
      setQuery(INITIAL_SQL_HISTORY_QUERY);
      return;
    }
    const requestId = ++requestSeqRef.current;
    const result = await api<SqlHistory[]>(`/sql/history?${sqlHistoryRequestParams(id, nextQuery)}`);
    // 输入关键字时会连续发多个请求，只有最后一个的结果才作数。
    if (requestId !== requestSeqRef.current || connectionIdRef.current !== id) return;
    setRows(result);
    setQuery(nextQuery);
  }, []);

  const refreshQuietly = useCallback(async () => {
    try {
      await refresh();
    } catch {
      warn('SQL 已处理，但历史记录刷新失败，可稍后重新打开历史。');
    }
  }, [refresh, warn]);

  /** 关键字搜索与「加载更多」共用同一条取数路径，只是 query 不同。 */
  const load = useCallback(async (nextQuery: SqlHistoryQuery): Promise<boolean> => {
    // 不能用「已在加载就直接返回」来去重：搜索是去抖触发的，丢掉的那一次正好是用户
    // 最新输入的关键字。改成让请求都发出去，由 refresh 的序号丢弃过期响应。
    inFlightRef.current += 1;
    setLoading(true);
    try {
      await refresh(nextQuery);
      return true;
    } catch (error) {
      fail(error instanceof Error ? error.message : String(error));
      return false;
    } finally {
      inFlightRef.current -= 1;
      if (inFlightRef.current === 0) setLoading(false);
    }
  }, [fail, refresh]);

  /** 历史属于某一条连接，切换后必须清空，不能把上一条连接的记录留在抽屉里。 */
  const reset = useCallback(() => {
    requestSeqRef.current += 1;
    setRows([]);
    setQuery(INITIAL_SQL_HISTORY_QUERY);
  }, []);

  return { rows, query, open, loading, featureLoaded, setOpen, setFeatureLoaded, refresh, refreshQuietly, load, reset };
}
