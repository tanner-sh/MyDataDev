import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import type { AiStatus } from '../types';

/**
 * AI 可用性快照。
 *
 * 每个用户都要知道「这条连接上要不要显示 AI 按钮」，但配置本身是管理员的，所以这里读的是
 * 不含任何配置细节的 /ai/status。取不到就当功能关着 —— 界面少一个按钮，比在没配好的环境里
 * 让人点了才报错要好。
 */
export function useAiStatus(reloadKey?: unknown): { status?: AiStatus; reload: () => void } {
  const [status, setStatus] = useState<AiStatus>();

  const reload = useCallback(() => {
    void api<AiStatus>('/ai/status')
      .then(setStatus)
      .catch(() => setStatus({ enabled: false, sharedConnectionIds: [], sampledConnectionIds: [] }));
  }, []);

  useEffect(() => { reload(); }, [reload, reloadKey]);

  return { status, reload };
}
