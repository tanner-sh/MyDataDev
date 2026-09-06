import { describe, expect, it, vi } from 'vitest';
import { startColumnResizeInteraction } from './columnResize';

function pointerEvent(type: string, pointerId: number, clientX = 0) {
  return Object.assign(new Event(type), { pointerId, clientX });
}

describe('列宽拖动生命周期', () => {
  it('只处理当前指针，并在窗口失焦后移除全部监听器', () => {
    const target = new EventTarget();
    const onMove = vi.fn();
    const onFinish = vi.fn();
    const cleanup = startColumnResizeInteraction({ target, pointerId: 7, startX: 100, onMove, onFinish });

    target.dispatchEvent(pointerEvent('pointermove', 8, 120));
    target.dispatchEvent(pointerEvent('pointermove', 7, 135));
    target.dispatchEvent(new Event('blur'));
    target.dispatchEvent(pointerEvent('pointermove', 7, 160));
    cleanup();

    expect(onMove).toHaveBeenCalledOnce();
    expect(onMove).toHaveBeenCalledWith(35);
    expect(onFinish).toHaveBeenCalledOnce();
  });

  it('在 pointercancel 时执行幂等清理', () => {
    const target = new EventTarget();
    const onFinish = vi.fn();
    const cleanup = startColumnResizeInteraction({
      target,
      pointerId: 3,
      startX: 0,
      onMove: vi.fn(),
      onFinish
    });

    target.dispatchEvent(pointerEvent('pointercancel', 3));
    target.dispatchEvent(pointerEvent('pointerup', 3));
    cleanup();

    expect(onFinish).toHaveBeenCalledOnce();
  });

  it('吞掉拖动后合成的那次 click，但不影响下一轮的点击', () => {
    vi.useFakeTimers();
    try {
      const target = new EventTarget();
      const cleanup = startColumnResizeInteraction({
        target, pointerId: 1, startX: 0, onMove: vi.fn(), onFinish: vi.fn()
      });
      target.dispatchEvent(pointerEvent('pointerup', 1));

      // 松开鼠标之后浏览器补的这次 click 落在表头上就等于点了排序，必须拦下。
      const synthesized = new Event('click', { bubbles: true, cancelable: true });
      const stopped = vi.spyOn(synthesized, 'stopPropagation');
      target.dispatchEvent(synthesized);
      expect(stopped).toHaveBeenCalledOnce();

      // 一个宏任务之后就撤掉：再点表头是用户真的想排序。
      vi.runAllTimers();
      const later = new Event('click', { bubbles: true, cancelable: true });
      const stoppedLater = vi.spyOn(later, 'stopPropagation');
      target.dispatchEvent(later);
      expect(stoppedLater).not.toHaveBeenCalled();
      cleanup();
    } finally {
      vi.useRealTimers();
    }
  });

  it('同一帧里的多次移动只更新一次，收尾时结算最后一次', () => {
    const frames: FrameRequestCallback[] = [];
    const originalRequest = globalThis.requestAnimationFrame;
    const originalCancel = globalThis.cancelAnimationFrame;
    globalThis.requestAnimationFrame = ((callback: FrameRequestCallback) => frames.push(callback)) as typeof requestAnimationFrame;
    globalThis.cancelAnimationFrame = (() => undefined) as typeof cancelAnimationFrame;
    try {
      const target = new EventTarget();
      const onMove = vi.fn();
      const cleanup = startColumnResizeInteraction({ target, pointerId: 2, startX: 100, onMove, onFinish: vi.fn() });

      target.dispatchEvent(pointerEvent('pointermove', 2, 110));
      target.dispatchEvent(pointerEvent('pointermove', 2, 130));
      target.dispatchEvent(pointerEvent('pointermove', 2, 160));
      expect(onMove).not.toHaveBeenCalled();

      frames.shift()?.(0);
      expect(onMove).toHaveBeenCalledOnce();
      expect(onMove).toHaveBeenCalledWith(60);

      // 又移动了但这一帧还没到，松手时不能把它丢掉。
      target.dispatchEvent(pointerEvent('pointermove', 2, 180));
      target.dispatchEvent(pointerEvent('pointerup', 2));
      expect(onMove).toHaveBeenCalledTimes(2);
      expect(onMove).toHaveBeenLastCalledWith(80);
      cleanup();
    } finally {
      globalThis.requestAnimationFrame = originalRequest;
      globalThis.cancelAnimationFrame = originalCancel;
    }
  });
});
