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
});
