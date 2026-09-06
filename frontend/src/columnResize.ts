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
 *
 * <p>拖完还要吞掉浏览器随后合成的那次 click。拖动手柄长在表头单元格里，而查询结果的表头
 * 整格都是「点一下排序」——不吞掉的话，松开鼠标的瞬间就等于点了表头：排序变了、offset 归零、
 * 服务端重新查一次。用户看到的是「拖一下列宽，网络面板里多出一条 query-page」。
 * 双击手柄恢复默认宽度更明显：两次 click 就是两次排序、两次请求。</p>
 *
 * <p>吞在 window 的捕获阶段：React 的事件监听挂在应用根节点上，捕获阶段的 window 早于它，
 * 所以 stopPropagation 之后 antd 的表头 onClick 根本不会被调用。只留一个宏任务的窗口 ——
 * 合成的 click 紧跟 pointerup 派发，而超出这一轮的任何点击都与这次拖动无关，不该被吞。</p>
 */
export function startColumnResizeInteraction({
  target,
  pointerId,
  startX,
  onMove,
  onFinish
}: ColumnResizeInteraction): () => void {
  let finished = false;
  /*
    指针事件按帧合并再更新宽度。

    pointermove 在高刷屏上一秒能来一百多次，而每次更新都要重建整份列定义并让表格重画一遍：
    40 列的结果集上，30 次移动就产生近千条 DOM 变更、最长一帧掉到 39ms —— 手上的感觉就是
    「拖不动」。一帧只可能呈现一个宽度，所以同一帧里的多次移动只取最后一次。
  */
  let frame = 0;
  let pendingDelta = 0;
  const flush = () => {
    frame = 0;
    if (!finished) onMove(pendingDelta);
  };
  const move = (event: Event) => {
    const pointer = event as PointerEvent;
    if (pointer.pointerId !== pointerId) return;
    pendingDelta = pointer.clientX - startX;
    if (frame) return;
    // 没有 rAF 的环境（测试、非浏览器）退回直接更新，行为与合并前一致。
    if (typeof requestAnimationFrame !== 'function') { flush(); return; }
    frame = requestAnimationFrame(flush);
  };
  const finish = (event?: Event) => {
    if (event?.type !== 'blur' && (event as PointerEvent | undefined)?.pointerId !== pointerId) return;
    cleanup();
  };
  const swallowClick = (event: Event) => {
    event.stopPropagation();
    event.preventDefault();
  };
  const cleanup = () => {
    if (finished) return;
    finished = true;
    // 收尾前把最后一次移动结算掉，否则松手时列宽会停在上一帧的位置。
    if (frame) {
      if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(frame);
      frame = 0;
      onMove(pendingDelta);
    }
    target.removeEventListener('pointermove', move);
    target.removeEventListener('pointerup', finish);
    target.removeEventListener('pointercancel', finish);
    target.removeEventListener('blur', finish);
    // 捕获阶段用对象写法而不是布尔第三参数：布尔形式在部分 EventTarget 实现上移除不掉，
    // 监听器会一直留着，把之后每一次点击都吞掉 —— 那比原来的 bug 更糟。
    const capture = { capture: true } as const;
    target.addEventListener('click', swallowClick, capture);
    setTimeout(() => target.removeEventListener('click', swallowClick, capture), 0);
    onFinish();
  };
  target.addEventListener('pointermove', move);
  target.addEventListener('pointerup', finish);
  target.addEventListener('pointercancel', finish);
  target.addEventListener('blur', finish);
  return cleanup;
}
