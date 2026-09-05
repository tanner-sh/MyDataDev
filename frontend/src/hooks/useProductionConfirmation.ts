import { useCallback, useRef, useState } from 'react';
import { validateProductionConfirmation } from '../productionConfirmation';

/**
 * 生产连接上的二次确认。
 *
 * <p>从 App.tsx 里搬出来的一组状态：一个待确认的请求、输入框的值，以及那个把弹窗变成
 * `await` 的 resolver。调用方写的是 `const confirmation = await request('执行 SQL')`，
 * 拿不到就直接 return —— 这条约定是这道闸门好用的全部原因，抽出来时不能改。</p>
 *
 * <p>校验交给 `productionConfirmation.ts` 的纯函数，错误文案由调用方决定怎么显示
 * （`onError`）：这个 hook 不该知道 toast 长什么样。</p>
 */
export type ProductionConfirmationRequest = { action: string; expected: string };

export function useProductionConfirmation(onError: (message: string) => void) {
  const [request, setRequest] = useState<ProductionConfirmationRequest | null>(null);
  const [input, setInput] = useState('');
  const resolverRef = useRef<((value: string | undefined) => void) | null>(null);

  const settle = useCallback((value: string | undefined) => {
    const resolve = resolverRef.current;
    resolverRef.current = null;
    setRequest(null);
    setInput('');
    resolve?.(value);
  }, []);

  /**
   * 发起一次确认。
   *
   * @param expected 生产连接名；传空表示这条连接不是生产连接，直接放行
   */
  const requestConfirmation = useCallback((action: string, expected?: string): Promise<string | undefined> => {
    if (!expected) return Promise.resolve(undefined);
    return new Promise((resolve) => {
      // 上一次还挂着的话先让它以「取消」收尾，否则那个 await 永远不会返回。
      resolverRef.current?.(undefined);
      resolverRef.current = resolve;
      setInput('');
      setRequest({ action, expected });
    });
  }, []);

  const cancel = useCallback(() => settle(undefined), [settle]);

  const confirm = useCallback(() => {
    if (!request) return;
    const result = validateProductionConfirmation(input, request.expected);
    if (!result.ok) {
      onError(result.message);
      return;
    }
    settle(result.value);
  }, [input, onError, request, settle]);

  return { request, input, setInput, requestConfirmation, cancel, confirm };
}
