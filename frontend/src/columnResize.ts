export type ColumnResizeInteraction = {
  target: EventTarget;
  pointerId: number;
  startX: number;
  onMove: (deltaX: number) => void;
  onFinish: () => void;
};

/**
 * 管理一次列宽拖动的全局监听器。pointerup 之外，窗口失焦、系统取消手势和组件卸载都走
 * 同一个幂等清理函数，避免 body 永久停留在禁止文本选择的状态。
 */
export function startColumnResizeInteraction({
  target,
  pointerId,
  startX,
  onMove,
  onFinish
}: ColumnResizeInteraction): () => void {
  let finished = false;
  const move = (event: Event) => {
    const pointer = event as PointerEvent;
    if (pointer.pointerId === pointerId) onMove(pointer.clientX - startX);
  };
  const finish = (event?: Event) => {
    if (event?.type !== 'blur' && (event as PointerEvent | undefined)?.pointerId !== pointerId) return;
    cleanup();
  };
  const cleanup = () => {
    if (finished) return;
    finished = true;
    target.removeEventListener('pointermove', move);
    target.removeEventListener('pointerup', finish);
    target.removeEventListener('pointercancel', finish);
    target.removeEventListener('blur', finish);
    onFinish();
  };
  target.addEventListener('pointermove', move);
  target.addEventListener('pointerup', finish);
  target.addEventListener('pointercancel', finish);
  target.addEventListener('blur', finish);
  return cleanup;
}
