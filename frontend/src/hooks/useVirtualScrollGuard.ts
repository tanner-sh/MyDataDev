import { useEffect, type RefObject } from 'react';

/**
 * 挡住浏览器对虚拟表格的原生横向滚动。
 *
 * <p>antd 的虚拟表格（rc-virtual-list）横向滚动不靠 scrollLeft，而是自己记一个偏移量，同时
 * 拿它去同步表头。可只要表体里有元素获得焦点 —— 双击一格进入编辑、Tab 走到下一格 —— 而那一格
 * 有一部分在可视区右边，浏览器就会把 overflow:hidden 的容器原生地滚过去「让它露出来」。表格
 * 不知道这件事：表体挪了，表头没挪，数据整体错开几百像素对到别的列名下面，左侧几列被挡住
 * 看不见。用户反馈的「双击进入后单元格显示不完整、被遮挡」就是这个。</p>
 *
 * <p>做法是在捕获阶段盯住表体里的 scroll 事件：发现容器被原生滚动了，先归零，再把同样的距离
 * 当成一次横向滚轮交还给虚拟列表 —— 这条路会同时推动表头，两边重新对齐，而那一格照样滚进
 * 了可视区。</p>
 */
export function useVirtualScrollGuard(viewportRef: RefObject<HTMLElement | null>, enabled = true) {
  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport || !enabled) return;
    const handleScroll = (event: Event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement) || target.scrollLeft === 0) return;
      if (!target.closest('.ant-table-tbody-virtual')) return;
      // 虚拟列表自己的横向滚动从不写 scrollLeft，所以这里看到的非零值只能来自浏览器。
      if (getComputedStyle(target).overflowX !== 'hidden') return;
      const delta = target.scrollLeft;
      target.scrollLeft = 0;
      const holder = target.closest<HTMLElement>('.ant-table-tbody-virtual-holder') ?? target;
      holder.dispatchEvent(new WheelEvent('wheel', { deltaX: delta, deltaY: 0, bubbles: true, cancelable: true }));
    };
    viewport.addEventListener('scroll', handleScroll, true);
    return () => viewport.removeEventListener('scroll', handleScroll, true);
  }, [enabled, viewportRef]);
}
